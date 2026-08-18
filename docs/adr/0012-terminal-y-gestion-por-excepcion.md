# ADR-0012 — Terminal en la máquina y gestión por excepción

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Contexto

Los documentos anteriores diseñaban el sistema como si tuviera que deducirlo todo por
radiofrecuencia, sin que ninguna persona tocara nada. Eso es más difícil de lo necesario
y no se parece a lo que se instala en una planta.

En las carretillas de bobinas reales hay un **terminal montado en cabina**: una pantalla
que muestra al operario qué lleva encima y adónde tiene que llevarlo. Existe porque la
identificación automática y la ubicación automática **no son igual de fiables**:

- **Qué bobina es** → el RFID lo resuelve solo, sin error y sin esfuerzo. Aquí es donde
  estaban las equivocaciones de transcripción de 2021.
- **Dónde se ha dejado** → depende de un tag de suelo que puede estar sucio, tapado por
  otra bobina o simplemente no leerse en el instante justo.

## Decisión

**El RF propone y el terminal confirma, pero solo por excepción.** El operario no toca
la pantalla mientras todo va según lo previsto.

```
1. El sistema asigna una tarea      "coge la 4471 y llévala a C5-08"
2. El operario la carga             → RFID detecta la carga. CoilPickedUp.
3. El terminal muestra              "4471 · aleación 5754 · destino C5-08"
4. La deja en C5-08                 → el tag de suelo confirma. CoilPlaced. Sin tocar nada.

4'. Si algo se desvía —no cabía, ningún tag legible, el tag leído no es el esperado—
    el terminal pregunta: "¿dónde la has dejado?" y el operario lo confirma.
```

Se añaden dos conceptos al dominio: la **tarea de movimiento** (lo planificado) y la
**confirmación del operario** (lo declarado), que conviven con la **observación RF** (lo
medido).

## Razones

1. **No reintroduce el problema de 2021.** El fallo de entonces no era que hubiera un
   humano: era que anotaba en papel y lo tecleaba horas después, o nunca. Un toque en la
   pantalla **en el instante de la acción**, con la bobina ya identificada sola, no se
   parece en nada a aquello. Lo que se automatiza es justo donde estaban los errores.
2. **Esfuerzo cero en el caso normal.** Gestión por excepción: el operario solo
   interviene cuando el sistema no está seguro. Un sistema que pide confirmación en cada
   movimiento se acaba pulsando sin mirar, y entonces los datos valen menos que nada.
3. **Da un plan B a `LOCATION_UNKNOWN`.** Deja de ser un caso perdido a la espera del
   próximo inventario y pasa a resolverse en el momento, preguntando.
4. **Reformula el objetivo del motor de resolución, y a mejor.** Ya no es "adivinar la
   ubicación": es **molestar al operario lo menos posible**. La métrica pasa de
   "precisión de ubicación" a **"porcentaje de movimientos resueltos sin preguntar"**,
   que significa algo para alguien de planta y no solo para quien programó el algoritmo.
5. **Dos fuentes que se cruzan.** Cuando la confirmación del operario y la lectura RF no
   coinciden, eso **es información**, no un problema: señala tags de suelo defectuosos,
   huecos mal mapeados o confirmaciones automáticas sin mirar.

## Consecuencia de diseño: un canal nuevo, y no es MQTT

El terminal **no es un sensor**. Necesita recibir la tarea, mostrarla y devolver una
respuesta: es una sesión bidireccional con una persona delante, no telemetría.

| Canal | Quién | Transporte |
|---|---|---|
| Lecturas de tag | Lectores RFID | **MQTT** → `ingest` |
| Tarea y confirmación | Terminal de la máquina | **REST + WebSocket** → `api` |

Es la misma separación que existe en la realidad: los lectores hablan MQTT con la
plataforma; los terminales de vehículo hablan HTTP con el sistema de gestión.

### Matiz sobre ADR-0006

[ADR-0006](0006-simulador-emite-solo-lecturas-crudas.md) dice que el simulador solo
publica lecturas crudas por MQTT y **nunca** llama a la API del backend. Con un operario
en el bucle hay que precisar esa regla, porque el simulador pasa a tener dos papeles
distintos:

| Papel | Qué simula | Cómo habla con el sistema |
|---|---|---|
| **Planta física** | Lectores RFID | MQTT, solo lecturas crudas. **ADR-0006 intacto.** |
| **Operario** | Una persona con el terminal | La **API pública**, exactamente la que usa la pantalla real |

No es una grieta en ADR-0006: lo que aquel prohíbe es **colar la verdad física por el
canal de los sensores**. Un operario simulado que llama a la misma API que usaría una
persona no cuela nada — está haciendo justo lo que hace un cliente cualquiera.

Y sí, el operario simulado **conoce la verdad**: sabe dónde ha dejado la bobina. Eso es
correcto, porque un operario de verdad también lo sabe. Es una fuente de información
cara y de alta calidad, que es exactamente su papel en el sistema.

Guardarraíles, que se comprueban con tests:

1. El agente operario usa **solo endpoints públicos**, los mismos que la pantalla. Nada
   de acceso privilegiado ni a la base de datos.
2. **Solo responde cuando se le pregunta.** No publica ubicaciones por iniciativa propia.
3. **Se equivoca**, con perillas propias (ver más abajo). Un operario simulado perfecto
   volvería a hacer trampa por la puerta de atrás.

## Perillas nuevas del simulador

| Perilla | Por defecto | Qué reproduce |
|---|---|---|
| `operatorConfirmDelayS` | 5–60 | Tarda en contestar; sigue conduciendo y confirma luego |
| `operatorRubberStampRate` | 0,10 | **Confirma el destino propuesto sin mirar**, aunque la haya dejado en otro sitio |
| `operatorIgnoreRate` | 0,05 | No contesta: la pregunta queda pendiente |
| `operatorMistapRate` | 0,02 | Selecciona el hueco de al lado en la pantalla |

`operatorRubberStampRate` es la interesante y la más incómoda: produce datos
**internamente coherentes y falsos**, porque el sistema recibe una confirmación humana
—la fuente que considera más fiable— que es mentira. Solo se detecta cruzándola con la
lectura RF o con un inventario posterior. Es el argumento cuantitativo de por qué
conviene preguntar poco: **cuanto más preguntas, más se pulsa sin mirar.**

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Solo RF, sin terminal | Lo documentado hasta ahora. Máximo problema de inferencia, pero sin plan B cuando un tag no se lee, y lejos de cómo se instala esto de verdad. |
| Solo terminal, RF solo para identificar | Lo más simple y fiable, pero se lleva por delante casi todo el motor de resolución, que es el proyecto. |
| Confirmar **todos** los movimientos | Elimina la ambigüedad sobre el papel y la reintroduce en la práctica: la confirmación sistemática se convierte en un reflejo y los datos se degradan. Es lo que mide `operatorRubberStampRate`. |
| El terminal por MQTT | Encajaría a la fuerza una sesión interactiva en un protocolo de telemetría. El terminal necesita recibir tareas y responder: eso es REST y WebSocket. |

## Consecuencias

**Positivas:** mucho más realista; `LOCATION_UNKNOWN` deja de ser un callejón sin
salida; la métrica del sistema pasa a ser comprensible para alguien de planta; aparece
una segunda interfaz con criterios de diseño propios (guantes, sol, movimiento), que es
un ejercicio interesante por sí mismo; dos fuentes independientes que se cruzan.

**Negativas:** parte del problema de inferencia puro se ablanda — con un humano de
respaldo, un depósito sin tag legible deja de ser dramático; aparece un canal más
(REST/WS desde la máquina) y con él una vista más que construir; el simulador se
complica con un agente operario y sus modos de fallo.

**Riesgo:** que la pantalla se convierta en la muleta y el motor de resolución se
descuide, porque "total, ya lo confirma el operario". La defensa es la métrica: si el
porcentaje de movimientos resueltos sin preguntar no sube, el motor no está mejorando,
por muy bien que funcione el sistema en conjunto.
