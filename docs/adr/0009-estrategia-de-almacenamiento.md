# ADR-0009 — Cada tipo de dato en su sitio

- **Estado:** Aceptado
- **Fecha:** 2026-08-17 · *reescrito tras [ADR-0011](0011-sin-kafka-de-momento.md)*

## Contexto

Pregunta de partida: *"MQTT ya hace de broker, ¿es correcto guardar esto en Postgres? ¿O
es mejor algo orientado a logs, tipo Elastic/OpenSearch?"*

Antes de responder hay que deshacer una ambigüedad: **broker MQTT** es una centralita —
recibe, reenvía a los suscriptores y **olvida**. Los mensajes retenidos y las colas de
sesión sirven para que un cliente que se reconecta se ponga al día: segundos, no
histórico. Mosquitto es una tubería, no un archivo, así que sí hace falta persistencia.

La pregunta real es **dónde va cada cosa**, porque se estaban metiendo en el mismo saco
cuatro tipos de dato que no se parecen en nada.

## Decisión

| Dato | Volumen | Dónde | Retención |
|---|---|---|---|
| **Lecturas crudas** | ~13M filas, ~1 GB/día | `raw_read`, particionada por día | 7 d |
| **Observaciones** | miles/día | `observation` | larga |
| **Eventos de dominio** | ~600/día | `coil_event`, append-only | **infinita** |
| **Proyecciones** | decenas de filas | `coil_location`, `slot_occupancy`… | actual |
| **Métricas** | continuo | **Prometheus** | 15 d |
| **Logs de aplicación** | medio | ficheros + `docker logs` | corta |

Todo lo de dominio en **una sola base de datos**. **Elastic / OpenSearch no entra en el
proyecto.**

## Razones

### Por qué las lecturas crudas duran solo 7 días

1. **Lo que hay que conservar ya está en las observaciones**
   ([ADR-0008](0008-lotes-y-observaciones.md)). Lo crudo solo sirve para forense a corto
   plazo: *"¿por qué el sistema concluyó que la dejó en C5-08?"*.
2. **El simulador con semilla fija es el archivo.** Ventaja que un sistema real no tiene:
   para reproducir un escenario de hace tres semanas no hace falta conservarlo — se
   relanza el simulador con la misma semilla y salen las mismas lecturas, bit a bit.
   Retener a largo plazo sería pagar por algo regenerable.
3. **Particionar por día hace el borrado gratis.** `DROP PARTITION` en lugar de un
   `DELETE` masivo que fragmenta la tabla.

### Por qué PostgreSQL para el resto

4. **Los invariantes son relacionales.** Ocupación de huecos, reservas contra pedido,
   linaje de sobrantes, una bobina en un solo sitio. Las restricciones las impone el
   motor, no el código de aplicación.
5. **Transaccionalidad entre evento y proyección.** Escribir `coil_event` y actualizar
   `slot_occupancy` atómicamente no es negociable si el inventario tiene que ser fiable.
6. **El volumen es trivial** y no justifica ninguna pieza adicional.

### Por qué Elastic no

7. **Es un motor de búsqueda, y aquí no hay nada que buscar.** Elastic construye índices
   invertidos para texto libre. Estos datos son un identificador exacto y un número. Se
   pagaría indexación y almacenamiento por una capacidad que no se usa, con peor
   compresión y peor agregación numérica que las alternativas.
8. **Una pieza más que operar** para algo que ya cubren Prometheus y `docker logs`.
9. Si algún día hiciera falta análisis masivo sobre lo crudo, la respuesta no sería
   Elastic sino **ClickHouse** o Parquet: columnar, compresión de 10-20× y agregaciones
   mucho más rápidas sobre datos numéricos.

## Regla práctica

> Cada dato a un solo sitio, elegido por **cómo se consulta**, no por dónde es cómodo
> escribirlo.

- *"¿Dónde está la bobina 4471?"* → proyección
- *"¿Qué le pasó a la bobina 4471?"* → `coil_event`
- *"¿Por qué el sistema creyó que la dejó en C5-08?"* → `raw_read` (7 días)
- *"¿Cuántas lecturas/s da el lector de la carretilla 2?"* → Prometheus
- *"¿Por qué petó anoche?"* → logs

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Elastic/OpenSearch como almacén de lecturas | Índice invertido para datos sin texto libre. Coste alto, encaje malo. |
| Elastic solo para logs de aplicación | Defendible en la fase 5, pero una pieza más para algo que `docker logs` ya resuelve. |
| TimescaleDB | Buen encaje técnico y sin salir de Postgres. Innecesario a este volumen; sería la primera opción si se decidiera conservar lo crudo a largo plazo. |
| ClickHouse | Excelente para esto, pero resuelve un problema de escala que este proyecto no tiene. |
| MongoDB | Los invariantes del dominio son relacionales. `jsonb` cubre la parte flexible. |

## Consecuencias

**Positivas:** una sola base de datos que operar; sin duplicación entre almacenes; cada
pregunta tiene un sitio claro donde responderse.

**Negativas:** las lecturas crudas de más de 7 días **no se pueden consultar** — hay que
regenerarlas con el simulador, lo que resta comodidad al análisis forense de incidentes
lejanos.

**Riesgo asumido:** si el volumen creciera mucho (más máquinas, más plantas), `raw_read`
sería lo primero en apretar. La salida sería particionar por hora, acortar la retención
o, si de verdad hiciera falta, TimescaleDB — no cambiar de motor.
