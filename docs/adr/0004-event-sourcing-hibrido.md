# ADR-0004 — Event sourcing híbrido: hechos inmutables + proyecciones

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Contexto

En el sistema de 2021 el registro de una bobina era una fila mutable y editable a
mano. Cuando algo salía mal, no había forma de saber qué se supo, cuándo se supo ni
por qué se concluyó lo que se concluyó. Para un sistema cuyo valor de negocio es la
**trazabilidad**, eso es una contradicción de raíz.

Por otro lado, el event sourcing puro (reconstruir el estado leyendo eventos en cada
consulta) hace caras las preguntas que más se van a hacer: "enséñame el mapa del
patio" o "cuánto stock libre hay de esta aleación".

## Decisión

**Híbrido:**

1. **Hechos inmutables, append-only** — `observation` y `coil_event`. Nunca se
   actualizan ni se borran. Son la fuente de verdad. Las lecturas crudas de las que
   derivan viven en Kafka con retención corta, no en la base de datos
   ([ADR-0009](0009-estrategia-de-almacenamiento.md)).
2. **Proyecciones mutables** — `coil_location`, `slot_occupancy`, `stock_summary`,
   etc. Se actualizan al procesar eventos y sirven todas las consultas. **Son
   desechables y reconstruibles.**

Las correcciones se hacen **añadiendo un evento de corrección**, jamás editando uno
anterior.

## Razones

1. **La trazabilidad es el producto.** "¿Qué bobinas de la colada 2026-0412 salieron
   y a qué cliente?" solo se responde con certeza si existe un registro inmutable.
2. **Auditoría de las decisiones del sistema.** Cada evento lleva `confidence` y
   `evidence` (qué lecturas y qué regla lo produjeron). Cuando el motor se equivoque,
   se puede reconstruir su razonamiento.
3. **Reconstruibilidad.** Corregir el algoritmo y regenerar todas las proyecciones es
   una operación rutinaria, no un desastre.
4. **Consultas rápidas.** El mapa del patio es un `SELECT` sobre una tabla de
   proyección, no un plegado de un millón de eventos.
5. **`occurredAt` ≠ `recordedAt`.** Distinguir cuándo pasó de cuándo se supo es
   imprescindible con relojes desfasados y llegadas tardías. Un modelo mutable no
   tiene sitio para esa distinción.

## Alcance: qué es y qué no es un evento de dominio

No todo merece ser un evento. Criterio:

- **Sí:** todo lo relativo al ciclo de vida de la bobina, su ubicación, sus reservas,
  sus cortes y su expedición. Es decir: lo que tiene valor de trazabilidad.
- **No:** datos maestros (zonas, huecos, lectores, clientes). Son CRUD normal con
  auditoría básica. Hacerlos event sourced es ceremonia sin beneficio.

Aplicar event sourcing a todo el sistema es el error más común al adoptarlo.

## Implementación

```sql
-- Hecho inmutable
CREATE TABLE coil_event (
    id           BIGSERIAL PRIMARY KEY,
    coil_id      TEXT        NOT NULL,
    type         TEXT        NOT NULL,
    schema_ver   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    confidence   REAL,
    evidence     JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    caused_by    BIGINT REFERENCES coil_event(id),   -- corrección o causalidad
    trace_id     TEXT
);
CREATE INDEX ON coil_event (coil_id, occurred_at);
CREATE INDEX ON coil_event (type, occurred_at);
-- Sin UPDATE ni DELETE: revocado por permisos y verificado en test.
```

- `observation` sustituye a una hipotética tabla de lecturas: el colapso en intervalos
  ([ADR-0008](0008-lotes-y-observaciones.md)) baja el volumen de ~500M filas/día a unos
  pocos miles, así que no hace falta particionar ni gestionar retención.
- Las proyecciones se actualizan con `upsert` por clave → idempotentes → el replay es seguro.
- Cada proyección guarda el `last_processed_event_id` para poder reanudar.
- Comando `./gradlew rebuildProjections` que las vacía y las regenera. **Debe ejecutarse
  en CI**: una reconstrucción que solo funciona en teoría no funciona.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| CRUD puro | Es lo que falló en 2021. Sin auditoría, sin reconstruibilidad, sin trazabilidad. |
| Event sourcing puro con CQRS estricto | Complejidad alta; consultas caras; sin agregados con reglas de negocio complejas no compensa. |
| Tablas temporales de Postgres (`system_versioned`) | Dan histórico de filas, pero no la *intención* ni la evidencia. Se sabe qué cambió, no por qué. |
| Framework de event sourcing (Axon, Eventuate) | Se lleva por delante el aprendizaje: el objetivo es entender el mecanismo, no configurarlo. |

## Consecuencias

**Positivas:** trazabilidad completa; auditoría del razonamiento del sistema;
proyecciones desechables; consultas rápidas; encaja con Kafka de forma natural.

**Negativas:** dos representaciones del mismo hecho que pueden divergir (se mitiga
reconstruyendo en CI); más volumen de almacenamiento; hay que resistir la tentación
de "arreglar" un dato con un `UPDATE` directo; consistencia eventual entre el evento
y su proyección (mitigada porque el frontend recibe el cambio por WebSocket).
