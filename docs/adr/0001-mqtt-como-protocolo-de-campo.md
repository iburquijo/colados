# ADR-0001 — MQTT como protocolo de campo

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Contexto

Los lectores RFID (simulados ahora, potencialmente reales después) tienen que
entregar sus lecturas al sistema. En 2021 se hizo con HTTP contra ThingSpeak y
*polling* desde el cliente, con latencia alta, dependencia de un tercero y ninguna
detección de caída de dispositivo.

## Decisión

Usar **MQTT** (Eclipse Mosquitto en local; EMQX si hacen falta funciones avanzadas)
como protocolo entre los dispositivos de campo y la plataforma.

## Razones

1. **Es el protocolo real de este dominio.** Cualquier lector UHF industrial o
   pasarela IoT habla MQTT. Usar otra cosa haría la simulación menos transferible.
2. **Last Will and Testament.** El broker publica automáticamente el estado `OFFLINE`
   de un dispositivo que pierde la conexión. La caída de un lector se convierte en un
   evento del sistema en vez de un silencio que nadie interpreta. Esto es exactamente
   lo que le faltaba al montaje de 2021.
3. **QoS por mensaje.** QoS 1 para lecturas, QoS 0 para telemetría de alta frecuencia
   que no importa perder. Granularidad que HTTP no da.
4. **Push, no polling.** Latencia de milisegundos, sin desperdiciar peticiones.
5. **Ligero.** Cabecera de 2 bytes, sesiones persistentes, corre en un ESP32.
   Relevante si en la fase 6 se conecta hardware real.
6. **Mensajes retenidos.** Un suscriptor nuevo conoce el estado actual de cada lector
   sin esperar al siguiente heartbeat.
7. **Jerarquía de topics con comodines.** `colados/+/reader/+/reads` suscribe a todos
   los lectores de todas las plantas de una vez.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| HTTP POST a la API | Sin QoS, sin LWT, sin sesión persistente. Caro en dispositivos limitados. Es lo que se hizo en 2021. |
| Kafka directo desde el lector | No hay cliente Kafka viable en microcontroladores; sin QoS por mensaje ni LWT; asume red fiable. Kafka no es un protocolo de campo. |
| AMQP / RabbitMQ | Más pesado, menos habitual en IoT industrial. |
| OPC UA | Es el estándar de facto en automatización industrial y sería más "correcto" para PLCs. Se descarta por complejidad desproporcionada y porque los lectores RFID de patio suelen exponerse por MQTT o LLRP, no por OPC UA. |
| CoAP | Pensado para redes muy restringidas; ecosistema mucho menor. |

## Consecuencias

**Positivas:** realismo industrial; caída de dispositivo detectable; camino directo a
hardware real sin tocar el backend.

**Negativas:** una pieza de infraestructura más que operar; MQTT no retiene histórico
(por eso hace falta Kafka o la base de datos detrás — ver [ADR-0002](0002-kafka-como-backbone.md));
sin esquemas nativos, la validación hay que hacerla en la ingesta.

**Riesgo asumido:** QoS 1 es *at-least-once*, así que **habrá duplicados**. La
idempotencia por `(readerId, seq)` en el módulo `ingest` no es opcional.
