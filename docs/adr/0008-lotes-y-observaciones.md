# ADR-0008 — Lotes de lectura y colapso en observaciones

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Contexto

El contrato original definía un mensaje MQTT por cada lectura de tag. Al estimar el
volumen aparece el problema:

- Un tag dentro del alcance de una antena se lee **~20 veces por segundo**.
- Con ~300 bobinas activas vistas por ~2 antenas cada una: **~6.000 lecturas/s**.
- Un mensaje por lectura = 6.000 mensajes MQTT/s y **518 millones de filas al día**
  (~60 GB con índices).

Y lo peor no es el volumen, es que **casi todo es redundante**: una bobina almacenada
tres semanas en la misma calle genera 18 millones de filas que dicen exactamente lo
mismo, "sigue ahí".

## Decisión

Dos cambios, en dos sitios distintos:

1. **El lector publica lotes.** En lugar de un mensaje por lectura, un **informe de
   inventario** cada 200 ms con todas las lecturas del periodo (`TagReadBatch`).
   Pasa de ~6.000 a ~150 mensajes/s.
2. **`ingest` colapsa las lecturas en observaciones.** Lecturas continuas del mismo
   EPC en el mismo lector se agregan en un intervalo (`Observation`) con
   `firstSeen`, `lastSeen`, `readCount`, `rssiP75` y `maxGapMs`. De veinte mil filas
   a una.

## Razones

1. **Es lo que hace el hardware real.** Los lectores UHF industriales no emiten
   lecturas sueltas: agrupan en informes periódicos. Y el estándar **EPCglobal ALE**
   (*Application Level Events*) define exactamente el segundo paso: convertir el flujo
   crudo en transiciones *observed / new / gone*. No estamos inventando un atajo,
   estamos implementando lo que existe.
2. **Baja dos órdenes de magnitud la presión sobre todo lo demás** — red, broker,
   almacenamiento y motor de resolución— sin perder nada de información útil.
3. **Un lote vacío es información.** "He mirado durante 200 ms y no había ningún tag"
   es un dato distinto de "no he publicado". Con mensajes por lectura, la ausencia era
   indistinguible del silencio por avería.
4. **`batchSeq` monótono detecta pérdidas.** Si falta el lote 918272, hubo un hueco, y
   el sistema lo sabe en lugar de intuirlo.
5. **La frecuencia de lectura se conserva.** `readCount` y `maxGapMs` mantienen la
   señal que distingue "cerca y quieta" de "lejos e intermitente", que es justo lo que
   necesita el motor de resolución. Agregar no es tirar información: es tirar
   *repetición*.

## Dónde se hace cada cosa, y por qué importa

| Paso | Dónde | Por qué ahí |
|---|---|---|
| Agrupar en lotes | **En el lector** | Es una optimización de transporte. No interpreta nada. |
| Colapsar en observaciones | **En `ingest`, nunca en el lector** | Es *inferencia*: decidir que veinte mil lecturas son una misma presencia continua. |

La segunda línea es la importante. Si el dispositivo entregara observaciones ya
resueltas, estaría regalando parte del problema que el sistema debe resolver, y se
violaría [ADR-0006](0006-simulador-emite-solo-lecturas-crudas.md). El lector agrupa por
eficiencia; interpretar es trabajo del backend.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Un mensaje por lectura | 6.000 mensajes/s y 518M filas/día de las cuales el 99,99 % son repetición. |
| Que el lector filtre lecturas repetidas | Parece limpieza y es pérdida: la frecuencia de lectura *es* la señal que distingue proximidad. |
| Que el lector emita observaciones | Rompe ADR-0006 y elimina el problema interesante. |
| Muestreo (1 de cada N lecturas) | Descarta información de forma ciega; el ruido y las lecturas perdidas ya no serían fieles al fenómeno real. |

## Nota posterior: ADR-0010 cambia las cifras, no la decisión

Este ADR se escribió asumiendo antenas fijas en el patio (~6.000 lecturas/s, 518M
filas/día). [ADR-0010](0010-lector-en-la-maquina.md) traslada el lector a la máquina y
esas cifras bajan a **~80 lecturas/s por máquina activa, ~300/s en punta y cero con las
máquinas paradas**.

**Las dos decisiones siguen en pie**, y por las mismas razones:

- Agrupar en lotes es lo que hace el hardware real, y 300 mensajes/s sueltos seguirían
  siendo absurdos frente a 20 lotes/s.
- El colapso en observaciones sigue siendo la abstracción correcta: una bobina
  transportada cinco minutos son ~6.000 lecturas idénticas, y cada tag de ubicación por
  el que pasa la máquina deja decenas más.

Lo que cambia es que el problema ya no es de **supervivencia** —antes el volumen hacía
inviable el almacenamiento— sino de **higiene**.

## Consecuencias

**Positivas:** volumen manejable; contrato más fiel al hardware real; lotes vacíos y
`batchSeq` dan detección de pérdidas gratis; el almacenamiento deja de ser un problema
([ADR-0009](0009-estrategia-de-almacenamiento.md)).

**Negativas:** el esquema es más complejo (lista anidada en lugar de mensaje plano);
la idempotencia se lleva al lote, así que un lote parcialmente duplicado se descarta
entero; `ingest` gana estado (las observaciones abiertas), lo que obliga a pensar qué
pasa al reiniciar; añade hasta 200 ms de latencia, que hay que tener en cuenta al fijar
el criterio de ausencia del motor de resolución.

**Riesgo:** una observación abierta que nunca se cierra (el tag desaparece y el lector
también) requiere un barrido periódico que las cierre por caducidad. Sin eso quedan
observaciones abiertas para siempre.
