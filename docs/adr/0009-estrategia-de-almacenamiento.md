# ADR-0009 — Cada tipo de dato en su sitio

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Contexto

Pregunta de partida: *"MQTT ya hace de broker, ¿es correcto guardar esto en Postgres?
¿O es mejor algo orientado a logs, tipo Elastic/OpenSearch?"*

Primero hay que deshacer una ambigüedad de vocabulario, porque *broker* significa dos
cosas distintas:

- **Broker MQTT** — centralita. Recibe, reenvía a los suscriptores y **olvida**. Los
  mensajes retenidos y las colas de sesión offline sirven para que un cliente que se
  reconecta se ponga al día: son segundos o minutos, no un histórico.
- **Broker Kafka** — log distribuido. Recibe, **escribe en disco** y conserva durante
  el tiempo de retención configurado.

Mosquitto es una tubería, no un archivo. Así que sí, hace falta persistencia. La
pregunta real es **dónde va cada cosa**, porque estábamos metiendo en el mismo saco
cuatro tipos de dato que no se parecen en nada.

## Decisión

| Dato | Volumen | Dónde | Retención |
|---|---|---|---|
| **Lecturas crudas** | alto | **Kafka** `rfid.reads.raw` (fase 3). Antes: tabla en Postgres | 7 d / 48 h |
| **Observaciones** | bajo | **PostgreSQL** | larga |
| **Eventos de dominio** | bajo | **PostgreSQL** + Kafka `coil.events` | infinita |
| **Proyecciones** | ~300 filas | **PostgreSQL** | actual |
| **Métricas** | continuo | **Prometheus** | 15 d |
| **Logs de aplicación** | medio | ficheros + `docker logs` | corta |

**Las lecturas crudas no se copian a ninguna base de datos.** Kafka ya es un log: la
retención de 7 días cubre depuración y reproceso reciente, y pasado ese plazo se
descartan.

**Elastic / OpenSearch no entra en el proyecto.**

## Razones

### Por qué las lecturas crudas no van a una base de datos

1. **Kafka ya es exactamente eso.** Un log append-only con retención y consumidores
   con offset propio. Copiarlo a una tabla es duplicar el mismo dato en dos sitios,
   con dos políticas de borrado que se pueden desincronizar.
2. **El volumen no lo justifica.** Tras el colapso en observaciones
   ([ADR-0008](0008-lotes-y-observaciones.md)), la información que hay que conservar de
   verdad ya está en las observaciones. Lo crudo solo sirve para forense a corto plazo:
   *"¿por qué el sistema concluyó eso a las 09:14?"*.
3. **El simulador con semilla fija es el archivo.** Ventaja que un sistema real no
   tiene: para reproducir un escenario de hace tres semanas no hace falta conservar
   500 millones de filas — se relanza el simulador con la misma semilla y salen las
   mismas lecturas, bit a bit. Retener a largo plazo sería pagar por algo regenerable.

### Por qué Postgres para lo demás

4. **Los invariantes son relacionales.** Ocupación de huecos, reservas contra pedido,
   linaje de sobrantes, una bobina en un solo sitio. Es exactamente para lo que sirve
   una base de datos relacional, y las restricciones las impone el motor y no el
   código de aplicación.
5. **Transaccionalidad entre evento y proyección.** Escribir `coil_event` y actualizar
   `slot_occupancy` en la misma transacción no es negociable si el inventario tiene que
   ser fiable.
6. **Volumen trivial.** Unos miles de filas al día. Postgres ni se entera.

### Por qué Elastic no

7. **Es un motor de búsqueda, y aquí no hay nada que buscar.** Elastic construye
   índices invertidos para texto libre. Nuestros datos son un identificador exacto y un
   número. Pagaríamos coste de indexación y almacenamiento por una capacidad que no
   se usa, con peor compresión y peor agregación numérica que las alternativas.
8. **Una pieza más que operar** para un beneficio que ya cubren Prometheus (métricas)
   y `docker logs` (aplicación).
9. Si algún día hiciera falta análisis masivo sobre lecturas crudas, la respuesta no
   sería Elastic sino **ClickHouse** o Parquet en almacenamiento de objetos: columnar,
   compresión de 10-20× y agregaciones mucho más rápidas sobre datos numéricos.

## Regla práctica

> Cada dato a un solo sitio, elegido por **cómo se consulta**, no por dónde es cómodo
> escribirlo.

- *"¿Dónde está la bobina 4471?"* → proyección (Postgres)
- *"¿Qué le pasó a la bobina 4471?"* → `coil_event` (Postgres)
- *"¿Por qué el sistema creyó que la dejó en C5-08?"* → `rfid.reads.raw` (Kafka)
- *"¿Cuántas lecturas/s da el lector de la carretilla 2?"* → Prometheus
- *"¿Por qué petó el consumidor anoche?"* → logs

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Todo en Postgres, incluidas las lecturas crudas | Con antenas fijas eran 518M filas/día; con lector embarcado ([ADR-0010](0010-lector-en-la-maquina.md)) son ~25M/día, que ya cabrían. Se mantiene la decisión igualmente: Kafka **ya es** el log y copiarlo a una tabla es duplicar el dato con dos políticas de borrado que se desincronizan. Sigue siendo el plan hasta la fase 3, con 48 h de retención y solo para depurar. |
| Elastic/OpenSearch como almacén de lecturas | Índice invertido para datos sin texto libre. Coste alto, encaje malo. |
| Elastic solo para logs de aplicación | Defendible en la fase 5, pero una pieza más para algo que `docker logs` ya resuelve en un proyecto personal. |
| TimescaleDB | Buen encaje técnico (hypertables, compresión, agregados continuos) y sin salir de Postgres. Innecesario una vez que las observaciones reducen el volumen; sería la primera opción si se decidiera conservar lo crudo a largo plazo. |
| ClickHouse | Excelente para esto, pero resuelve un problema de escala que este proyecto ya no tiene tras ADR-0008. |
| MongoDB | Los invariantes del dominio son relacionales. `jsonb` de Postgres cubre la parte flexible. |

## Consecuencias

**Positivas:** una sola base de datos que operar; sin duplicación entre almacenes;
volumen manejable; cada pregunta tiene un sitio claro donde responderse.

**Negativas:** las lecturas crudas más antiguas de 7 días **no se pueden consultar**
—hay que regenerarlas con el simulador—, lo que resta comodidad al análisis forense de
incidentes lejanos; el análisis ad-hoc sobre lo crudo se hace con `kcat` y herramientas
de Kafka en lugar de SQL, que es menos cómodo.

**Riesgo asumido:** si en la fase 3 Kafka se descartara (regla de salida de
[ADR-0002](0002-kafka-como-backbone.md)), las lecturas crudas se quedarían sin almacén.
En ese caso vuelven a Postgres particionado con retención corta, que es lo que ya se
hace en las fases 1 y 2.
