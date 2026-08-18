# Registro de decisiones de arquitectura (ADR)

Un ADR documenta **una decisión, su contexto y sus consecuencias**. Sirve sobre todo
para el futuro: dentro de seis meses, para no volver a discutir lo ya discutido, y
para saber qué habría que reevaluar si cambian las circunstancias.

Un ADR no se edita cuando se cambia de opinión: se marca como **Sustituido** y se
escribe uno nuevo que lo reemplaza.

## Índice

| # | Decisión | Estado |
|---|---|---|
| [0001](0001-mqtt-como-protocolo-de-campo.md) | MQTT como protocolo de campo | Aceptado |
| [0003](0003-monolito-modular.md) | Monolito modular, no microservicios | Aceptado |
| [0004](0004-event-sourcing-hibrido.md) | Event sourcing híbrido: hechos + proyecciones | Aceptado |
| [0005](0005-stack-backend-y-frontend.md) | Spring Boot + Gradle + Next.js | Aceptado |
| [0006](0006-simulador-emite-solo-lecturas-crudas.md) | **El simulador solo emite lecturas crudas** | Aceptado |
| [0007](0007-tecnologia-del-simulador.md) | Simulador en Java + Spring Boot | Aceptado |
| [0008](0008-lotes-y-observaciones.md) | Lotes de lectura y colapso en observaciones | Aceptado |
| [0009](0009-estrategia-de-almacenamiento.md) | Cada tipo de dato en su sitio | Aceptado |
| [0010](0010-lector-en-la-maquina.md) | **El lector viaja con la máquina, no con el patio** | Aceptado |
| [0011](0011-sin-kafka-de-momento.md) | Sin Kafka: PostgreSQL como log de eventos | Aceptado |
| [0012](0012-terminal-y-gestion-por-excepcion.md) | Terminal en la máquina y gestión por excepción | Aceptado |
| [0013](0013-patio-simple-y-capacidad-de-hueco.md) | Patio simple, capacidad de hueco y desambiguación al recoger | Aceptado |

## Numeración

El **0002 (Kafka como backbone) fue retirado**, no renumerado: los números de ADR no se
reutilizan ni se reordenan, porque otros documentos y commits los citan. Un hueco en la
secuencia es información, no un descuido.

## Estados

- **Propuesto** — en discusión, no implementar todavía
- **Aceptado** — decidido, vinculante
- **Sustituido por ADR-XXXX** — ya no vigente
- **Rechazado** — se consideró y se descartó (se conserva por el razonamiento)

## Plantilla

```markdown
# ADR-XXXX — Título

- **Estado:** Propuesto | Aceptado | Sustituido por ADR-YYYY | Rechazado
- **Fecha:** AAAA-MM-DD

## Contexto
Qué problema o fuerza obliga a decidir.

## Decisión
Qué se decide, en presente y sin ambigüedad.

## Razones
Por qué. Numeradas.

## Alternativas consideradas
Tabla: alternativa | por qué no.

## Consecuencias
Positivas, negativas y riesgos asumidos. Las negativas son obligatorias:
un ADR sin desventajas es un ADR que no se ha pensado.
```
