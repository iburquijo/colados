# ADR-0002 — Kafka como backbone de eventos (a partir de la fase 3)

- **Estado:** Aceptado, con introducción diferida a la fase 3
- **Fecha:** 2026-08-17

## Contexto

MQTT transporta, pero no almacena: una vez entregado el mensaje, desaparece. El
sistema necesita histórico, reproceso y varios consumidores independientes sobre el
mismo flujo. La pregunta es si eso lo cubre PostgreSQL solo o hace falta un log
distribuido.

## Decisión

Introducir **Apache Kafka** (modo KRaft, sin ZooKeeper) como log de eventos entre la
ingesta y el resto de módulos. **Pero no en la fase 1**: las fases 1 y 2 van con
MQTT → PostgreSQL directo. Kafka entra en la fase 3, cuando ya hay dominio real que
reprocesar.

## Razones

La justificación honesta se reduce a una: **replay**.

1. **Reprocesar la historia con un algoritmo nuevo.** El motor de resolución de
   ubicación es heurístico y se va a equivocar. Con las lecturas en Kafka se puede
   corregir la regla y **reconstruir todas las proyecciones desde el principio**.
   Sin log, ese conocimiento está perdido para siempre. Este es el argumento; los
   demás son secundarios.
2. **Comparar dos algoritmos sobre los mismos datos.** Dos *consumer groups* sobre
   `rfid.reads.raw`, cada uno con un resolutor distinto, y se mide cuál acierta más.
   Trivial con Kafka, imposible de forma limpia sin él.
3. **Varios consumidores desacoplados.** Tracking, alertas, inventario y analítica
   leen el mismo flujo a su ritmo, sin acoplarse entre sí.
4. **Topics compactados como caché de estado.** `coil.state` compactado permite a un
   consumidor nuevo reconstruir la foto del patio sin tocar la base de datos.
5. **Kafka Streams para el ventaneo.** La agregación por ventana deslizante y clave
   (`epc`) que necesita el motor de resolución es exactamente para lo que existe
   Kafka Streams. Escribirla a mano sobre Postgres es reinventarla peor.
6. **Contrapresión.** Si el consumidor va lento, se acumula lag y se ve en una
   métrica. Con MQTT directo a la base de datos se pierden mensajes o se bloquea el broker.

## Por qué diferida a la fase 3

Meter Kafka en la fase 1 aporta cero funcionalidad visible y añade una pieza
considerable de operación. La secuencia MQTT → Postgres es suficiente hasta que exista
un dominio que merezca la pena reprocesar. Los contratos de evento se diseñan **desde
el día 1** pensando en Kafka (clave por `epc`, esquemas versionados), de modo que la
migración sea añadir un productor, no reescribir el modelo.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Solo PostgreSQL como event store | Viable y más simple. Pierde el ventaneo en *streaming*, la contrapresión y los consumer groups. Es el **plan B** si Kafka lastra el proyecto. |
| RabbitMQ | Cola, no log: al consumir se borra. Sin replay ni reproceso desde offset. |
| Redis Streams | Ligero, con consumer groups y más simple que Kafka. Retención acotada por memoria y ecosistema de stream processing pobre. Buena opción intermedia si Kafka resulta excesivo. |
| Apache Pulsar | Técnicamente atractivo (retención por niveles, multi-tenancy), pero ecosistema y material de aprendizaje mucho menores. |
| MQTT con sesiones persistentes | Retención pensada para reconexión de clientes, no para reproceso histórico. No es un log. |

## Consecuencias

**Positivas:** replay y reproceso; comparación objetiva de algoritmos; consumidores
desacoplados; ventaneo con Kafka Streams; aprendizaje de la herramienta.

**Negativas:** pieza pesada en el Compose (~1 GB de RAM con Schema Registry);
curva de aprendizaje de Kafka Streams; la gestión de esquemas añade fricción; hay que
razonar sobre particiones y ordenación desde el principio.

**Regla de salida:** si al llegar a la fase 3 Kafka está frenando el avance más de lo
que aporta, se ejecuta el plan B (event store en Postgres + notificaciones con
`LISTEN/NOTIFY`) y se revisa este ADR. Ceder aquí no invalida el diseño: el modelo de
eventos es el mismo.
