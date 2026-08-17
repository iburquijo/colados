# ADR-0011 — Sin Kafka: PostgreSQL como log de eventos

- **Estado:** Aceptado
- **Fecha:** 2026-08-17
- **Sustituye a:** [ADR-0002](0002-kafka-como-backbone.md)

## Contexto

[ADR-0002](0002-kafka-como-backbone.md) aceptaba Kafka como backbone, difiriéndolo a la
fase 3. Su justificación técnica principal era el **estado de ventana recuperable**: el
motor de resolución mantenía una ventana deslizante por bobina —cientos de entradas en
memoria— y perderlas al reiniciar dejaba un agujero de cobertura. Kafka Streams, con sus
*state stores* respaldados por *changelog topics*, resolvía eso de serie.

[ADR-0010](0010-lector-en-la-maquina.md) traslada el lector a la máquina y **ese
argumento desaparece**. El estado del motor de resolución pasa a ser una máquina de
estados por lector de máquina: **cuatro entradas**, reconstruibles releyendo el último
minuto de lecturas.

Con los números actualizados, ningún argumento técnico sostiene ya la decisión:

| | ADR-0002 asumía | Realidad tras ADR-0010 |
|---|---|---|
| Lecturas | ~6.000/s constantes | ~300/s en punta, **0 con las máquinas paradas** |
| Filas crudas/día | 518 millones (~60 GB) | ~13 millones (**~1 GB**) |
| Claves de partición | 300 bobinas | **7 lectores** |
| Estado en ventana | ~300 entradas | **4 entradas** |
| Eventos de dominio | — | ~600/día (**0,007/s**) |

## Decisión

**No se usa Kafka.** El log de eventos vive en PostgreSQL y la comunicación entre
módulos es en proceso.

MQTT **se mantiene sin cambios** ([ADR-0001](0001-mqtt-como-protocolo-de-campo.md)): es
el protocolo de campo y no tiene nada que ver con esta decisión.

| Pieza | Implementación |
|---|---|
| Lecturas crudas | Tabla `raw_read`, particionada por día, retención 7 d |
| Observaciones | Tabla `observation` |
| Log de eventos | Tabla `coil_event`, append-only, retención infinita |
| Proyecciones | Tablas derivadas, reconstruibles |
| Fan-out entre módulos | `ApplicationEventPublisher` de Spring, en proceso |
| Replay | Lectura ordenada por lotes de `raw_read` / `coil_event` |
| Empuje al navegador | WebSocket/STOMP, sin cambios |

## Razones

1. **Ningún argumento técnico sobrevive.** Contrapresión, escalado por partición,
   consumer groups independientes y ventaneo en *streaming* resolvían problemas que este
   sistema ya no tiene. Con 7 claves de partición, más de 7 particiones no aportan nada.
2. **Postgres ya hace el replay.** `SELECT * FROM raw_read ORDER BY id` recorre la
   historia en orden. Es la misma capacidad, con SQL en lugar de offsets.
3. **1 GB/día es trivial.** Particionar por día y tirar particiones viejas es una línea
   de configuración.
4. **Desaparece el problema de la escritura dual.** Con Kafka hacía falta separar un
   `projector`, porque `tracking` no podía escribir en Kafka y en Postgres de forma
   atómica. Sin Kafka, `tracking` escribe el evento y actualiza las proyecciones **en la
   misma transacción**. Es más simple *y* estrictamente más correcto: no hay ventana en
   la que los dos almacenes puedan divergir.
5. **YAGNI.** Añadir infraestructura por una necesidad prevista que no ha llegado es
   cómo mueren los proyectos personales. Si llega, se añade entonces.

## Qué se pierde, sin adornos

Kafka enseña cosas que este montaje no:

- Particionado y sus consecuencias
- Consumer groups y gestión de *offsets*
- Compactación de log
- Schema Registry y compatibilidad de esquemas
- *Lag* de consumidor como métrica operativa
- Kafka Streams

Son competencias reales. **Se renuncia a ellas conscientemente**, no por descuido.

Lo que **no** se pierde, y es la mayor parte de lo que se quería aprender: ingesta push
por MQTT con QoS, LWT e idempotencia; event sourcing con log inmutable y proyecciones
desechables; reconstrucción por replay; eventos de corrección en vez de `UPDATE`;
entrega *at-least-once*, duplicados, llegadas tardías y relojes desfasados. Eso **es**
arquitectura orientada a eventos, y Kafka no es requisito para ninguna de esas cosas.

## Cómo se deja la puerta abierta

Migrar a Kafka debe ser **añadir un productor, no reescribir el modelo**. Para eso se
mantienen, aunque hoy no hagan falta:

1. **Esquemas versionados** en los payloads (`"schema": "colados.x.v1"`) y en el módulo
   `contracts/`.
2. **Clave de partición decidida y documentada** para cada flujo, aunque solo se use
   como índice: `readerId` para lecturas y observaciones, `coilId` para eventos de
   dominio ([ADR-0010](0010-lector-en-la-maquina.md)).
3. **Fan-out por eventos, no por llamadas directas**, entre módulos. Hoy los entrega
   Spring en proceso; mañana los entregaría un broker. Los módulos no notan la
   diferencia si nunca se llaman entre sí por método.
4. **Consumidores idempotentes**: proyecciones con `upsert` por clave y
   `last_processed_event_id`. Es lo que hace seguro el replay hoy y lo que haría seguro
   el reproceso desde un offset mañana.

## Cuándo revisar esta decisión

Disparadores concretos. Si se cumple alguno, se reabre:

- Hace falta **más de un proceso** consumiendo el mismo flujo (se rompe el monolito
  modular de [ADR-0003](0003-monolito-modular.md)).
- El volumen sostenido supera las **~10.000 lecturas/s**, donde el `INSERT` empieza a
  ser el cuello de botella.
- Se quiere ejecutar **varios resolutores en paralelo** sobre el mismo flujo en vivo,
  comparando precisión. Con Kafka son dos consumer groups; con Postgres hay que
  montárselo a mano.
- Aparece **más de una planta**, y con ella particionado real.
- Se decide, explícitamente y como objetivo propio, **aprender Kafka**. En ese caso el
  camino honesto es la fase 6: migrar un sistema que ya funciona, que además enseña más
  que construirlo con Kafka desde el principio.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Kafka completo con Streams | Ventaneo y *state stores* para cuatro entradas en un mapa. Herramienta desproporcionada. |
| Kafka sin Streams | Punto medio razonable: conservaba particionado, offsets y compactación. Se descarta por YAGNI — la infraestructura se paga todos los días y el beneficio era solo formativo. |
| Redis Streams | Más ligero que Kafka, pero sigue siendo una pieza más para un problema que Postgres ya resuelve. |
| RabbitMQ | Cola, no log. No aporta replay. |
| `LISTEN/NOTIFY` de Postgres para el fan-out | **Descartado como mecanismo fiable**: pierde mensajes si no hay ningún oyente conectado en ese instante y tiene límite de payload. Sirve como aviso para refrescar, nunca como transporte del evento. |

## Consecuencias

**Positivas:** una pieza menos de infraestructura (~1 GB de RAM y el Schema Registry
fuera del Compose); desaparece el problema de la escritura dual y con él el componente
`projector`; el `docker compose up` arranca en segundos; se avanza más rápido hacia el
dominio, que es donde está el valor del proyecto.

**Negativas:** se renuncia a aprender Kafka en este proyecto (mitigado: queda como
migración opcional en la fase 6); el replay es SQL por lotes en vez de reproceso desde
un offset, que es menos elegante y más manual; el fan-out en proceso no da
contrapresión, así que un consumidor lento bloquea al productor — irrelevante a
0,007 eventos/s, pero conviene saberlo.

**Riesgo:** que la comunicación en proceso invite a saltarse la frontera entre módulos y
llamarse por método en lugar de por evento. Los tests de ArchUnit de
[ADR-0003](0003-monolito-modular.md) son lo que impide que eso pase, y ahora importan
más que antes.
