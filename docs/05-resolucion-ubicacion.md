# 05 — Resolución de ubicación: de lecturas sucias a inventario

Este es el núcleo del proyecto. Todo lo demás (MQTT, Spring, PostgreSQL, Next.js) es
infraestructura al servicio de este módulo.

Se implementa como un **consumidor con estado en memoria**: una máquina de estados por
lector de máquina. Son cuatro entradas, reconstruibles releyendo el último minuto de
`raw_read` tras un reinicio — por eso no hace falta un motor de *streaming*
([ADR-0011](adr/0011-sin-kafka-de-momento.md)).

Parte de la arquitectura de [ADR-0010](adr/0010-lector-en-la-maquina.md): el lector va
embarcado en la máquina y lee dos tipos de tag —el de la bobina que transporta y los de
ubicación por los que pasa—, y los **dos** eventos que el sistema debe producir se
deducen de las **transiciones** de ese flujo. La trayectoria intermedia no es un evento:
es telemetría en vivo para pintar la carretilla en el mapa, y no merece guardarse como
hecho de negocio.

Y de [ADR-0012](adr/0012-terminal-y-gestion-por-excepcion.md): hay un **terminal en la
cabina**, así que cuando el motor no está seguro puede preguntar en vez de adivinar.

## 1. El problema

Entrada: lotes de lecturas de los lectores de máquina. Cada lectura es
`(machineReaderId, epc, rssi, readAt)`, y el EPC puede ser de una bobina, de una
ubicación, o de algo desconocido — **el lector no lo sabe**; lo resuelve el backend
contra el registro de tags.

Lo que hay que producir son dos hechos por movimiento:

```
CoilPickedUp   (qué bobina, qué máquina, de qué hueco)
   ...trayectoria...
CoilPlaced     (en qué hueco, con qué confianza)
```

Y las propiedades desagradables de la entrada:

1. **Redundante**: la bobina transportada se lee ~20 veces/s durante todo el trayecto.
2. **Difusa en los bordes**: el tag no aparece ni desaparece de golpe, **se desvanece**.
   El instante exacto de la recogida y del depósito hay que *decidirlo*.
3. **Ambigua en el destino**: al depositar puede haber varios tags de ubicación al
   alcance a la vez.
4. **Incompleta**: tags de ubicación sucios, tapados por otra bobina o rotos.
5. **Contaminada**: dos máquinas trabajando cerca se leen los tags la una a la otra.

**El punto 2 es el que decide el diseño.** Toda la ubicación del sistema depende de un
único instante —cuándo dejó de verse el tag de la bobina— y equivocarse en él por dos
segundos coloca la bobina en el hueco de al lado.

## 2. La máquina de estados de la carga

Antes de resolver *dónde*, hay que resolver *si lleva algo*. Por cada lector de máquina
se mantiene un estado:

```
VACÍA ──(tag de bobina visto n≥N durante ≥T)──> CARGADA(coilId)
CARGADA ──(sin lecturas del tag durante ≥T_gone)──> VACÍA
```

- **Entrada en `CARGADA`**: se exige persistencia (`n ≥ 10` lecturas en ≥ 3 s con RSSI
  alto) para no cargarse una bobina por pasar a su lado.
- **Salida a `VACÍA`**: se exige ausencia continuada (`T_gone = 5 s` sin ninguna
  lectura). Es el criterio de *gone*, y es el parámetro más delicado del sistema:
  - demasiado corto → un hueco momentáneo de lecturas se interpreta como un depósito
    falso a mitad de trayecto;
  - demasiado largo → el depósito se registra tarde, la máquina ya ha avanzado y el
    tag de ubicación que se atribuye es el equivocado.

La transición `VACÍA → CARGADA` emite `CoilPickedUp`. La transición inversa emite
`CoilPlaced`. **Los dos eventos que se esperaban del sistema son, literalmente, las dos
transiciones de esta máquina de estados.**

## 3. Localizar la recogida y el depósito

En el instante de cada transición se mira la ventana de tags de **ubicación** leídos por
esa misma máquina:

```
Ventana: [t_transición − 10 s, t_transición + 5 s]

Por cada tag de ubicación candidato:
  n        = número de lecturas
  rssiP75  = percentil 75 del RSSI
  span     = duración de la observación
  persiste = ¿se sigue leyendo DESPUÉS de la transición?

score = w1·norm(n) + w2·norm(rssiP75) + w3·norm(span) + w4·(persiste ? 1 : 0)
```

El factor `persiste` es el discriminante bueno para el depósito: la máquina que acaba de
soltar una bobina **se queda un momento donde la ha soltado** antes de irse. Los tags de
ubicación de los que solo pasó de largo dejan de leerse; el del hueco de destino sigue
ahí unos segundos más.

Reglas de decisión:

- **Un solo candidato claro** (`score` del mejor > 1,5 × el segundo) → `CoilPlaced` con
  confianza alta.
- **Dos candidatos parejos** → `CoilPlaced` con confianza baja y los dos huecos como
  `candidates[]` en la evidencia. No se elige en silencio.
- **Ningún tag de ubicación en la ventana** → `CoilPlacedUnknownLocation`. La bobina
  pasa a `LOCATION_UNKNOWN` y entra en la cola del próximo inventario.

Esta última es importante: **el sistema debe admitir que no sabe dónde la ha dejado**.
Inventar el hueco más probable a partir de la trayectoria es exactamente cómo se
corrompe un inventario en silencio.

## 4. Cuando no está claro: preguntar en vez de adivinar

Hay un terminal en la cabina ([ADR-0012](adr/0012-terminal-y-gestion-por-excepcion.md)),
así que el motor tiene una salida mejor que elegir a ciegas:

| Situación | Qué hace el sistema |
|---|---|
| Candidato claro **y** coincide con el destino planificado | `CoilPlaced`. **No pregunta.** |
| Candidato claro pero **distinto** del planificado | `CoilPlaced` + `PlacementDiscrepancy`. Pregunta solo si la confianza es media. |
| Dos candidatos parejos | `PlacementNeedsConfirmation`: el terminal ofrece los dos huecos |
| Ningún tag de ubicación legible | `PlacementNeedsConfirmation`: el terminal pide el hueco |
| Se preguntó y no hubo respuesta | `CoilPlacedUnknownLocation` → cola de inventario |

Y al **recoger**, con huecos de capacidad > 1
([ADR-0013](adr/0013-patio-simple-y-capacidad-de-hueco.md)):

| Situación | Qué hace el sistema |
|---|---|
| Un solo tag de bobina con señal de "a bordo" | `CoilPickedUp`. **No pregunta.** |
| **Dos o más tags de bobina con señal fuerte** | `PickupNeedsConfirmation`: el terminal ofrece las bobinas del hueco y el operario elige |

Distinguir por RSSI cuál de dos bobinas apiladas va a bordo es poco fiable —están a menos
de un metro— y hay un humano delante que lo sabe con certeza. Con la capacidad por defecto
de 1, este caso solo aparece por contaminación entre máquinas.

**El objetivo del motor deja de ser adivinar y pasa a ser preguntar poco.** La métrica
de cabecera del sistema es el **porcentaje de movimientos resueltos sin preguntar**, y es
la que dice si el algoritmo mejora de verdad.

Y hay una razón dura para no abusar de la pregunta, no solo de comodidad: **cuanto más
preguntas, más se confirma sin mirar**. Un operario al que se le pide confirmación en
cada movimiento acaba pulsando el botón por reflejo, y entonces llegan confirmaciones
humanas —la fuente que el sistema considera más fiable— que son falsas y perfectamente
coherentes. El simulador lo modela con `operatorRubberStampRate`, y solo se detecta
cruzando la confirmación con la lectura RF o con un inventario posterior.

Por eso la confirmación **nunca sobrescribe la observación RF**: se guardan las dos y,
si no coinciden, queda un `PlacementDiscrepancy`. Un patrón de discrepancias en la misma
calle delata un tag de ubicación defectuoso o un hueco mal mapeado; concentrado en un
operario, delata a alguien que confirma sin mirar.

## 5. La reconfirmación gratuita

Al **recoger** una bobina, la máquina lee el tag de ubicación del hueco del que la saca.
Si no coincide con donde el sistema creía que estaba:

```
El sistema creía: COIL-4471 en C5-08
Se recoge desde:  C5-11
→ CoilLocationCorrected(from: C5-08, to: C5-11, discoveredAt: pickup)
→ y C5-08 se marca como libre (llevaba ocupado indebidamente desde vete a saber cuándo)
```

Es la única reconfirmación pasiva que existe en esta arquitectura y no cuesta nada:
sale de un movimiento que iba a ocurrir de todos modos. Conviene explotarla al máximo.

## 6. Confianza que caduca

Con lectores fijos, una bobina se reconfirmaba cada segundo. Aquí **nadie vuelve a
mirarla** hasta que algo la mueva o pase un inventario. Por tanto la confianza no puede
ser un valor fijo:

```
confianza(t) = confianza_inicial · exp(−(t − últimaConfirmación) / τ)     τ = 7 días
```

- **> 0,7** — ubicación fiable
- **0,3 – 0,7** — probable, conviene verificar (candidata a inventario)
- **< 0,3** — `STALE`: el sistema sigue diciendo dónde cree que está, pero avisa de que
  hace mucho que nadie lo comprueba

La UI **nunca** muestra un hueco a secas. Muestra *"C5-08 · confirmado hace 3 días"*.
Esa diferencia es la que separa un inventario en el que la gente confía de uno que
acaba ignorándose, que es lo que pasó con el sistema de 2021.

## 7. El inventario que cierra el círculo

Un operario recorre el patio con el lector de mano. Cada lectura es de máxima
precedencia: hay un humano apuntando deliberadamente a un sitio concreto.

```
Por cada hueco recorrido:
  esperado = lo que dice la proyección
  leído    = tags de bobina leídos allí

  esperado == leído          → InventoryConfirmed. Confianza a 1,0, reloj a cero.
  esperado ∌ leído           → InventoryDiscrepancy: hay algo que no debería estar
  esperado ∌ vacío           → InventoryDiscrepancy: falta lo que debería estar
  bobina en LOCATION_UNKNOWN
     aparece en un hueco     → CoilLocationResolved. Se cierra el caso abierto.
```

Además de corregir, el inventario **mide**: el porcentaje de huecos que cuadran es la
tasa de acierto real del sistema en producción, no una estimación. Es la métrica que
justifica el proyecto entero ante alguien de planta.

## 8. Contaminación entre máquinas

Dos carretillas trabajando en la misma calle pueden leerse los tags la una a la otra.
Criterios, por orden:

1. **RSSI**: la bobina propia está a ~1 m (−40 dBm); la de la otra máquina a 5-10 m
   (−65 dBm o menos). Umbral absoluto de RSSI para considerar "a bordo".
2. **Exclusividad**: una bobina no puede estar cargada en dos máquinas a la vez
   (invariante 3). Si dos lectores la reclaman, gana el de mayor RSSI sostenido.
3. **Continuidad**: la máquina que la lleva la ve sin interrupción; la de al lado la ve
   a ráfagas mientras se cruzan.

## 9. Late arrivals y reproceso

Las lecturas que llegan fuera de la ventana (reloj desfasado, red recuperada tras un
corte) no se tiran:

- Se aceptan hasta un *grace period* de 60 s y se reprocesa la ventana afectada.
- Más allá, van a `rfid.reads.late`, se persisten y se marcan para reproceso por lotes.
- Un `CoilPlaced` invalidado después por datos tardíos genera un **evento de
  corrección** (`CoilLocationCorrected`), nunca una modificación silenciosa del evento
  original. El log es inmutable: se corrige añadiendo, no editando.

## 10. Cómo se prueba

| Nivel | Qué |
|---|---|
| Unitario | Máquina de estados de la carga y puntuación de candidatos con secuencias sintéticas |
| Propiedad | Invariantes del dominio bajo entradas aleatorias (jqwik) |
| Integración | Testcontainers: Mosquitto + Postgres reales |
| Aceptación | Escenarios del simulador con semilla fija → precisión esperada |
| Regresión | Traza grabada; si un cambio baja la precisión, falla el build |

El último es el interesante: **la precisión de ubicación es una métrica versionada en
CI**. Cada cambio del algoritmo se mide contra la verdad del simulador y una regresión
rompe la build igual que un test rojo.

Los parámetros a barrer experimentalmente, en este orden de importancia:

1. `T_gone` — el criterio de ausencia. Es el que más impacto tiene, con diferencia.
2. Los pesos `w1..w4` de la puntuación de candidatos.
3. El umbral de RSSI para "a bordo".
4. `τ`, la constante de decaimiento de confianza.

## 11. Ampliaciones posibles (fase tardía)

- **Odometría de la máquina**: si el simulador publica también la telemetría de
  velocidad, se puede estimar cuánto avanzó entre el último tag de ubicación y el
  depósito, y corregir el desplazamiento.
- **Trayectoria como contexto**: la secuencia de tags de ubicación dice por qué calle
  iba; un destino incoherente con la trayectoria es sospechoso.
- **Filtro bayesiano** sobre la posición en lugar de puntuación heurística.
- **Comparación de estrategias**: reprocesar la misma traza de `raw_read` con dos
  resolutores distintos y comparar su precisión contra la verdad del simulador. Es una
  ejecución por lotes; hacerlo sobre el flujo en vivo sí requeriría un broker con
  consumer groups, y es uno de los disparadores para reabrir
  [ADR-0011](adr/0011-sin-kafka-de-momento.md).
