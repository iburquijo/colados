# ADR-0008 — Lotes de lectura y colapso en observaciones

- **Estado:** Aceptado
- **Fecha:** 2026-08-17 · *reescrito sobre las premisas de [ADR-0010](0010-lector-en-la-maquina.md)*

## Contexto

Un tag dentro del alcance de un lector se lee **~20 veces por segundo**. Con el lector
embarcado en la máquina ([ADR-0010](0010-lector-en-la-maquina.md)) eso significa:

- ~80 lecturas/s por máquina activa; ~300/s en punta con las cuatro; **cero cuando están
  paradas**.
- Una bobina transportada durante un trayecto de cinco minutos deja **~6.000 lecturas
  idénticas** que dicen lo mismo: "sigue a bordo".
- Cada tag de ubicación por el que pasa la máquina deja decenas más.

Casi todo es **repetición**. Emitir un mensaje MQTT por lectura y guardar una fila por
lectura sería trabajar mucho para almacenar lo mismo miles de veces.

## Decisión

Dos cambios, en dos sitios distintos:

1. **El lector publica lotes.** Un **informe de inventario** cada 200 ms con todas las
   lecturas del periodo (`TagReadBatch`), en lugar de un mensaje por lectura. Pasa de
   ~300 a ~20 mensajes/s.
2. **`ingest` colapsa las lecturas en observaciones.** Lecturas continuas del mismo EPC
   en el mismo lector se agregan en un intervalo (`Observation`) con `firstSeen`,
   `lastSeen`, `readCount`, `rssiP75` y `maxGapMs`.

## Razones

1. **Es lo que hace el hardware real.** Los lectores UHF industriales no emiten lecturas
   sueltas: agrupan en informes periódicos. Y el estándar **EPCglobal ALE** (*Application
   Level Events*) define exactamente el segundo paso: convertir el flujo crudo en
   transiciones *observed / new / gone*. No es un atajo nuestro, es lo que existe.
2. **Un lote vacío es información.** "He mirado durante 200 ms y no había ningún tag" es
   un dato distinto de "no he publicado". Con mensajes por lectura, la ausencia era
   indistinguible del silencio por avería — y con lectores que solo emiten cuando la
   máquina se mueve, distinguirlo importa.
3. **`batchSeq` monótono detecta pérdidas.** Si falta el lote 918272, hubo un hueco, y el
   sistema lo sabe en lugar de intuirlo.
4. **La señal se conserva; solo se tira la repetición.** `readCount` y `maxGapMs`
   mantienen lo que distingue "cerca y estable" de "lejos e intermitente", que es
   exactamente lo que necesita el motor de resolución para decidir si una bobina va a
   bordo o solo pasaba cerca.

## Dónde se hace cada cosa, y por qué importa

| Paso | Dónde | Por qué ahí |
|---|---|---|
| Agrupar en lotes | **En el lector** | Es una optimización de transporte. No interpreta nada. |
| Colapsar en observaciones | **En `ingest`, nunca en el lector** | Es *inferencia*: decidir que miles de lecturas son una misma presencia continua. |

La segunda línea es la importante. Si el dispositivo entregara observaciones ya
resueltas, estaría regalando parte del problema que el sistema debe resolver, y se
violaría [ADR-0006](0006-simulador-emite-solo-lecturas-crudas.md). El lector agrupa por
eficiencia; interpretar es trabajo del backend.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Un mensaje por lectura | ~300 mensajes/s de los que el 99 % son repetición, y una fila por cada uno. |
| Que el lector filtre lecturas repetidas | Parece limpieza y es pérdida: la frecuencia de lectura *es* la señal que distingue proximidad. |
| Que el lector emita observaciones | Rompe ADR-0006 y elimina el problema interesante. |
| Muestreo (1 de cada N lecturas) | Descarta información de forma ciega; el ruido y las lecturas perdidas dejarían de ser fieles al fenómeno real. |

## Consecuencias

**Positivas:** volumen manejable sin esfuerzo; contrato más fiel al hardware real; lotes
vacíos y `batchSeq` dan detección de pérdidas gratis; el almacenamiento deja de ser un
problema ([ADR-0009](0009-estrategia-de-almacenamiento.md)).

**Negativas:** el esquema es más complejo (lista anidada en lugar de mensaje plano); la
idempotencia se lleva al lote, así que un lote parcialmente duplicado se descarta entero;
`ingest` gana estado (las observaciones abiertas), lo que obliga a pensar qué pasa al
reiniciar; añade hasta 200 ms de latencia, que hay que tener en cuenta al fijar `T_gone`
en el motor de resolución.

**Riesgo:** una observación abierta que nunca se cierra —el tag desaparece y el lector
también— requiere un barrido periódico que las cierre por caducidad. Sin eso quedan
observaciones abiertas para siempre.
