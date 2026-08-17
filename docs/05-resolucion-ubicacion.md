# 05 — Resolución de ubicación: de lecturas sucias a inventario

Este es el núcleo del proyecto. Todo lo demás (MQTT, Kafka, Spring, Next.js) es
infraestructura al servicio de este módulo.

## 1. El problema

Entrada: un flujo de tuplas `(readerId, antennaId, epc, rssi, readAt)`, del orden de
cientos por segundo, con las siguientes propiedades desagradables:

1. **Redundante**: un tag quieto bajo una antena genera ~20 lecturas por segundo.
2. **Ambiguo**: antenas de calles contiguas leen el mismo tag simultáneamente.
3. **Incompleto**: entre el 10 % y el 40 % de las lecturas esperadas no llegan.
4. **Desordenado**: relojes desfasados y red asíncrona rompen el orden temporal.
5. **Engañoso**: una bobina que *pasa por delante* de una antena produce lecturas
   indistinguibles, en primera instancia, de una bobina *depositada ahí*.

Salida deseada: para cada bobina, en cada instante, un estado y una ubicación con
un grado de confianza — o una admisión explícita de ignorancia.

**El punto 5 es el que decide todo el diseño.** Distinguir "pasa por" de "está en"
no se resuelve con una lectura; se resuelve con **tiempo de permanencia** y con
**quién más está leyendo el tag**.

## 2. Precedencia de lectores

No todos los lectores dicen lo mismo ni valen lo mismo:

| Tipo | Precedencia | Semántica |
|---|---|---|
| `HANDHELD` | 1 (máxima) | Un humano apuntó deliberadamente. Verdad casi absoluta. |
| `MACHINE` | 2 | El tag está **encima de la máquina**. Alcance corto, sin ambigüedad. |
| `GATE` | 3 | El tag **cruzó** un punto. Evento de tránsito, no de ubicación. |
| `ZONE` | 4 | El tag está **en algún sitio dentro** del alcance. Ambiguo por diseño. |

Regla capital:

> **Mientras un `MACHINE` lea el tag, ninguna lectura `ZONE` puede establecer ubicación.**

Con esa sola regla desaparece la mayoría de las lecturas fantasma: la bobina que
cruza la calle C montada en una carretilla no se ubica en C, porque el lector de la
carretilla está reclamándola con mayor precedencia.

## 3. Algoritmo

Implementado como topología de Kafka Streams con estado por clave (`epc`), o como
consumidor con estado si Kafka se pospone.

```
Para cada EPC, mantener una ventana deslizante de 15 s de lecturas.

1. RESOLVER IDENTIDAD
   epc → coilId usando la asignación VIGENTE en readAt (no la actual).
   Sin asignación vigente → evento TagUnknown, a cuarentena. No se descarta.

2. AGRUPAR POR CANDIDATO
   Agrupar las lecturas de la ventana por (readerType, locationRef).
   Por cada candidato calcular:
     - n        = número de lecturas
     - rssiP75  = percentil 75 del RSSI (robusto frente a picos)
     - span     = readAt(última) − readAt(primera)
     - recency  = ahora − readAt(última)

3. PUNTUAR
   score = w1·normalizar(n)
         + w2·normalizar(rssiP75)
         + w3·normalizar(span)
         − w4·normalizar(recency)
         + bonusPrecedencia(readerType)

4. APLICAR PRECEDENCIA
   Si existe candidato MACHINE con n ≥ 3 → estado IN_TRANSIT(machineId). FIN.
   Si no, los candidatos ZONE compiten entre sí.

5. HISTÉRESIS
   No cambiar de ubicación establecida salvo que:
     score(nuevo) > score(actual) · (1 + margen)   con margen = 0,25
   Evita el parpadeo entre dos calles contiguas con cobertura solapada.

6. CONFIRMACIÓN POR PERMANENCIA (dwell)
   Para pasar a STORED se exigen las tres condiciones:
     a) ninguna lectura MACHINE en los últimos 20 s
     b) span del candidato ≥ 30 s
     c) n ≥ 10
   Esto es lo que separa "pasó por aquí" de "está aquí".

7. DETECTAR CONTRADICCIÓN
   Si dos candidatos con precedencia ZONE tienen score comparable (ratio < 1,15)
   y son huecos incompatibles → CoilLocationDisputed. NO se elige uno en silencio.

8. DECAIMIENTO DE CONFIANZA
   confianza = confianza_base · exp(−(ahora − último_visto) / τ)   con τ = 300 s
   Bajo 0,3 → CoilLocationUncertain.  Sin lecturas > 30 min → CoilMissing.
```

### Por qué percentil 75 y no media de RSSI

El RSSI tiene picos por multitrayecto (reflexiones en el aluminio). La media se los
come; el máximo se deja engañar por un único rebote afortunado. El percentil 75 es
robusto y sigue reflejando "las lecturas buenas".

### Por qué el decaimiento exponencial

Porque la información caduca. Una bobina vista hace 5 s y una vista hace 2 h no
merecen la misma afirmación, aunque la última posición conocida sea idéntica. En el
sistema de 2021 —y en la mayoría de los ERP— ambas se muestran igual, y esa es
precisamente la mentira que hace que nadie se fíe del inventario.

## 4. Reconciliación con la intención

El sistema conoce dos cosas que puede contrastar:

- **Lo planificado**: la tarea decía "llevar la bobina 4471 al hueco C3-12".
- **Lo observado**: el motor de resolución dice que está en C3-15.

Discrepancia → `PlacementDiscrepancy`, alerta al supervisor y corrección automática
del inventario **hacia lo observado**, no hacia lo planificado. El patio real manda
sobre el plan.

Esta capacidad es literalmente la solución al problema de 2021: no se le pide nada
al operario y aun así el sistema sabe dónde está la bobina de verdad.

## 5. Late arrivals y reproceso

Las lecturas que llegan fuera de la ventana (reloj desfasado, red recuperada tras
un corte) no se tiran:

- Se aceptan hasta un *grace period* de 60 s y se reprocesa la ventana afectada.
- Más allá de eso, van a `rfid.reads.late`, se persisten y se marcan para reproceso
  por lotes.
- Un `CoilPlaced` emitido y luego invalidado por datos tardíos genera un
  **evento de corrección** (`CoilLocationCorrected`), no una modificación silenciosa
  del evento original. El log es inmutable; se corrige añadiendo, no editando.

## 6. Cómo se prueba

| Nivel | Qué |
|---|---|
| Unitario | Puntuación, histéresis y decaimiento con secuencias sintéticas de lecturas |
| Propiedad | Invariantes del dominio bajo entradas aleatorias (jqwik) |
| Integración | Testcontainers: Mosquitto + Kafka + Postgres reales |
| Aceptación | Escenarios del simulador con semilla fija → precisión esperada |
| Regresión | Traza grabada; si un cambio de algoritmo baja la precisión, falla el build |

El último es el interesante: **la precisión de ubicación es una métrica versionada
en CI**. Cada cambio del algoritmo se mide contra la verdad del simulador, y una
regresión rompe la build igual que un test rojo.

## 7. Ampliaciones posibles (fase tardía)

- **Filtro de Kalman / Bayes** sobre la posición, en lugar de puntuación heurística.
- **Trilateración con RSSI** de varias antenas para posición continua.
- **Aprendizaje de las curvas de cobertura** a partir de datos históricos, en vez de
  configurarlas a mano.
- **Comparación de estrategias**: ejecutar dos resolutores en paralelo sobre el mismo
  flujo y comparar precisión. Kafka lo permite de forma trivial: dos consumer groups
  sobre el mismo topic. Este es, concretamente, el argumento que justifica Kafka
  frente a "MQTT y ya está".
