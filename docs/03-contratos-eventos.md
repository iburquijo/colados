# 03 — Contratos: topics y esquemas de evento

Fuente de verdad: módulo `contracts/`. De ahí se generan los POJOs de Java y los
tipos de TypeScript. Nadie define un evento a mano en su servicio.

## 1. Capas de evento

Tres niveles, y **no se deben mezclar**:

| Nivel | Ejemplo | Quién lo emite | Contenido |
|---|---|---|---|
| **Crudo** | `TagReadBatch` | Lector (simulador) | Solo lo que el hardware puede saber |
| **Limpio** | `Observation` | Módulo `ingest` | Lecturas colapsadas en intervalos, con el EPC ya clasificado y resuelto |
| **Dominio** | `CoilPlaced` | Módulo `tracking` | Hecho de negocio inferido, con confianza |

Que el nivel crudo no contenga `coilId` ni `slotId` no es purismo: es lo que impide
que el sistema se autoengañe. Un lector físico ve un EPC y una potencia de señal.
Nada más.

## 2. Topics MQTT (campo → planta)

```
colados/{plantId}/reader/{readerId}/reads       QoS 1   lecturas de tag
colados/{plantId}/reader/{readerId}/status      QoS 1, retained   heartbeat + LWT
colados/{plantId}/machine/{machineId}/telemetry QoS 0   posición, carga, estado
colados/{plantId}/gate/{gateId}/events          QoS 1   paso de portal, báscula
colados/{plantId}/sim/control                   QoS 1   ← comandos hacia el simulador
colados/{plantId}/sim/groundtruth               QoS 0   verdad física (solo evaluación)
```

Decisiones:

- **QoS 1 (*at-least-once*)** para lecturas. QoS 2 duplica el número de viajes de red
  y en RFID de alto volumen no compensa: es más barato ser idempotente en el consumidor.
- **Last Will and Testament** en `/status`: si un lector pierde la conexión, el broker
  publica automáticamente `{"status":"OFFLINE"}`. Así la caída de un lector es un
  evento del sistema y no un silencio que nadie interpreta. Esto es exactamente lo
  que le faltaba al prototipo de ThingSpeak con polling.
- **Mensaje retenido** en `/status`: quien se suscribe conoce el estado actual sin esperar.
- El topic **incluye `readerId`**, así que no hace falta repetirlo en el payload...
  pero se repite igualmente. Un mensaje debe ser interpretable **fuera de su topic**,
  porque en cuanto se guarda en una tabla o se reenvía, el topic desaparece.

### Payload `TagReadBatch`

El lector **no publica un mensaje por lectura**, sino un **informe de inventario** cada
200 ms con todas las lecturas de ese periodo. Es lo que hacen los lectores UHF reales:
una máquina activa genera ~80 lecturas/s que se resumen en 5 mensajes.

```json
{
  "schema": "colados.tagreadbatch.v1",
  "plantId": "PLANT-01",
  "readerId": "RDR-MACH-CTR02",
  "readerType": "MACHINE",
  "batchSeq": 918273,
  "windowStart": "2026-08-17T09:14:23.000Z",
  "windowEnd": "2026-08-17T09:14:23.200Z",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "reads": [
    { "epc": "E280116060000208C7A4B1F3", "rssi": -41.2, "readAt": "2026-08-17T09:14:23.184Z" },
    { "epc": "E280116060000208C7A4B1F3", "rssi": -40.8, "readAt": "2026-08-17T09:14:23.121Z" },
    { "epc": "E28011606000020911B7C2A0", "rssi": -63.4, "readAt": "2026-08-17T09:14:23.043Z" }
  ]
}
```

| Campo | Nota |
|---|---|
| `schema` | Versionado explícito desde el primer día. Migrar sin versión en el payload es doloroso. |
| `batchSeq` | Contador monótono **por lector**. Clave de idempotencia `(readerId, batchSeq)` y detección de huecos: si falta el 918272, hubo pérdida. |
| `windowStart/End` | Periodo que cubre el lote. Un lote **vacío es información válida**: "he mirado y no había nada", distinto de no haber publicado. |
| `readAt` | Reloj **del lector**. Puede ir desfasado o hacia atrás. |
| `rssi` | dBm. Imprescindible: separa la bobina que va a bordo (~−40) de un tag de ubicación al pasar (~−60) o de la carga de otra máquina cercana. |
| `traceId` | W3C trace context, propagado hasta el WebSocket. |

En este ejemplo, el lector de la carretilla CTR-02 ve dos EPC distintos y **no sabe qué
son**: uno a −41 dBm (la bobina que lleva encima) y otro a −63 (un tag de ubicación por
el que está pasando). Clasificarlos es trabajo de `ingest`, no del dispositivo.

`readerType` ∈ `MACHINE` | `GATE` | `HANDHELD` ([ADR-0010](adr/0010-lector-en-la-maquina.md)).

Un lote ronda los 1–4 KB, muy por debajo de los límites prácticos de MQTT. Si un lector
llegara a superarlos con muchos tags a la vista, se parte en varios lotes con el mismo
`windowStart`.

Lo que **no** lleva ninguna lectura: `coilId`, `zoneId`, `slotId`, `event`. Un lector
físico no conoce ninguna de esas cosas ([ADR-0006](adr/0006-simulador-emite-solo-lecturas-crudas.md)).

### De lecturas a observaciones

Un tag que permanece al alcance se lee ~20 veces por segundo. Una bobina transportada
durante un trayecto de cinco minutos son ~6.000 lecturas que dicen exactamente lo mismo:
"sigue a bordo". Y cada tag de ubicación por el que pasa la máquina deja decenas de
lecturas más. Almacenarlas una a una es inviable e inútil.

El módulo `ingest` las colapsa en **observaciones con intervalo**, que es lo que hace
el middleware RFID real (el estándar EPCglobal ALE define justo estas transiciones
*observed / new / gone*):

```json
{
  "schema": "colados.observation.v1",
  "readerId": "RDR-MACH-CTR02",
  "epc": "E280116060000208C7A4B1F3",
  "epcKind": "COIL_TAG",
  "coilId": "COIL-2026-004471",
  "firstSeen": "2026-08-17T09:14:23.043Z",
  "lastSeen":  "2026-08-17T09:47:11.782Z",
  "readCount": 19842,
  "rssiP75": -58.3,
  "maxGapMs": 1840,
  "open": false
}
```

Una fila en lugar de veinte mil. El colapso se hace **en `ingest`, nunca en el lector**:
si el dispositivo entregara observaciones ya resueltas estaría regalando parte del
problema que el sistema debe resolver.

`maxGapMs` se conserva porque una observación con huecos grandes es menos fiable que
una continua, y el motor de resolución lo usa.

La observación **sí** puede llevar `coilId`: ya es la capa *limpia*, y resolver
`epc → coilId` con la asignación vigente es precisamente el trabajo de `ingest`. Lo que
sigue sin llevar es `zoneId`, `slotId` ni `event`: dónde está y qué significa lo decide
el motor de resolución, no la ingesta.

### Payload `ReaderStatus`

```json
{
  "schema": "colados.readerstatus.v1",
  "readerId": "RDR-MACH-CTR02",
  "status": "ONLINE",
  "uptimeS": 84213,
  "readsLastMinute": 412,
  "firmware": "2.3.1",
  "at": "2026-08-17T09:14:20.000Z"
}
```

LWT configurado: `{"schema":"colados.readerstatus.v1","readerId":"...","status":"OFFLINE"}`.

## 3. Flujos internos y almacenamiento

Sin Kafka ([ADR-0011](adr/0011-sin-kafka-de-momento.md)), lo que en otra arquitectura
serían topics aquí son tablas y eventos en proceso. Se documentan igualmente con su
**clave de agrupación**, porque es una decisión de arquitectura y porque es lo que haría
viable meter un broker más adelante sin rehacer el modelo.

| Flujo | Clave | Dónde vive | Retención |
|---|---|---|---|
| Lecturas crudas | `readerId` | tabla `raw_read`, particionada por día | 7 d |
| Observaciones | `readerId` | tabla `observation` | larga |
| Eventos de dominio | `coilId` | tabla `coil_event`, append-only | **infinita** |
| Estado por bobina | `coilId` | proyección `coil_location` | actual |
| Ocupación por hueco | `slotId` | proyección `slot_occupancy` | actual |
| Alertas | `alertType` | tabla `alert` | 90 d |
| Rechazos | `readerId` | tabla `rejected_read` **con motivo** | 90 d |

Notas:

- **`coil_event` con retención infinita** es lo que sostiene la trazabilidad y el replay.
  Es un log de auditoría: qué se supo, cuándo se supo y por qué se dedujo.
- **La clave de las lecturas es `readerId`, no `epc`.** Con el lector embarcado en la
  máquina, el razonamiento es "todo lo que ve una máquina": el tag de la bobina y los
  tags de ubicación deben procesarse juntos y en orden. Agruparlos por `epc` rompería la
  correlación entre el depósito y el hueco.
- **Los rechazos guardan el motivo**, no solo el mensaje. Una cola de rechazos sin
  diagnóstico es un cementerio.
- `raw_read` se particiona por día y se tiran las particiones viejas. A ~13 millones de
  filas y ~1 GB diarios, siete días son ~7 GB: nada para Postgres.

### Entrega entre módulos

El fan-out lo hace `ApplicationEventPublisher` de Spring, en proceso y dentro de la
transacción del productor. Reglas que lo mantienen sano:

1. **Un módulo nunca llama a otro por método**, solo publica hechos. Verificado con
   ArchUnit ([ADR-0003](adr/0003-monolito-modular.md)).
2. **Los consumidores son idempotentes**: `upsert` por clave y `last_processed_event_id`.
   Es lo que hace seguro el replay.
3. **`LISTEN/NOTIFY` no se usa como transporte.** Pierde mensajes si no hay oyente
   conectado. Sirve como aviso para refrescar, nunca para entregar el evento.

## 4. Eventos de dominio

Envoltura común:

```json
{
  "schema": "colados.<tipo>.v1",
  "eventId": "01J...",
  "coilId": "COIL-2026-004471",
  "occurredAt": "2026-08-17T09:14:31.902Z",
  "recordedAt": "2026-08-17T09:14:32.115Z",
  "confidence": 0.94,
  "evidence": { "readIds": ["..."], "rule": "DWELL_CONFIRMED" },
  "traceId": "0af7651916cd43dd8448eb211c80319c"
}
```

Tres campos que rara vez se ponen y que aquí son obligatorios:

- **`occurredAt` vs `recordedAt`**: cuándo pasó en la planta vs cuándo lo supo el
  sistema. Sin esa distinción no se puede analizar la latencia ni procesar eventos
  que llegan tarde.
- **`confidence`**: el sistema afirma con un grado de certeza, no con fe.
- **`evidence`**: qué lecturas y qué regla produjeron esta conclusión. Cuando el
  motor se equivoque —y se va a equivocar—, esto es lo que permite entender por qué.

### Catálogo

| Evento | Cuándo | Payload adicional |
|---|---|---|
| `CastCompleted` | Fin de colada | `castId, alloy, coilCount` |
| `CoilProduced` | Bobina bobinada | `castId, weightKg, widthMm, thicknessMm` |
| `TagCommissioned` | EPC asociado a bobina | `epc, from` |
| `TagDecommissioned` | Tag roto/retirado | `epc, reason` |
| `CoilPickedUp` | Transición VACÍA→CARGADA del lector de máquina | `machineId, fromSlotId` |
| `CoilPlaced` | Transición CARGADA→VACÍA, hueco identificado | `slotId, stackLevel, candidates[]` |
| `CoilPlacedUnknownLocation` | Depósito sin tag de ubicación legible | `machineId, lastKnownRow` |
| `CoilRelocated` | De un hueco a otro | `fromSlotId, toSlotId` |
| `CoilLocationCorrected` | Al recoger, el hueco real no era el esperado | `expectedSlotId, actualSlotId, discoveredBy` |
| `CoilLocationStale` | Confianza caducada sin confirmar | `lastConfirmedAt, ageDays` |
| `CoilLocationResolved` | Un inventario encuentra una bobina perdida | `slotId, sweepId` |
| `InventorySweepStarted` / `Completed` | Recorrido con lector de mano | `sweepId, slotsCovered, discrepancies` |
| `InventoryDiscrepancy` | Lo leído no cuadra con lo esperado | `slotId, expected[], found[]` |
| `CoilReserved` | Asignada a pedido | `orderId` |
| `CoilSplit` | Corte parcial | `parentCoilId, childCoilIds[], consumedKg` |
| `CoilLoaded` | En camión | `shipmentId, truckPlate` |
| `ShipmentDeparted` | Camión sale | `shipmentId, coilIds[]` |
| `InvariantViolated` | Regla rota | `invariant, details` |
| `ReaderOffline` / `ReaderRecovered` | Salud de lector | `readerId, downtimeS` |

## 5. Idempotencia y duplicados

Tres tipos de duplicado, con tres tratamientos distintos. Confundirlos es un error clásico:

| Tipo | Origen | Tratamiento |
|---|---|---|
| **De transporte** | MQTT QoS 1 reenvía el lote | Deduplicar por `(readerId, batchSeq)` en `ingest`. Se descarta el lote entero. |
| **De lectura** | El tag se lee 20 veces/s por la misma antena | **No se descarta**: es señal legítima. Se agrega en ventana en `tracking`. La frecuencia de lectura *es información* (cerca vs lejos). |
| **De reproceso** | Replay deliberado desde `raw_read` o `coil_event` | Se procesa normalmente contra proyecciones idempotentes (upsert por clave). |

El segundo caso es donde se equivoca casi todo el mundo: filtrar lecturas repetidas
en la ingesta parece limpieza y en realidad tira la señal que necesitas para
distinguir una bobina almacenada de una que pasó por delante.

## 6. Versionado de esquemas

- Confluent Schema Registry con compatibilidad **BACKWARD**: los consumidores nuevos
  leen datos viejos. Obligatorio si `coil.events` es infinito.
- Cambio compatible: añadir campo opcional. Incompatible: renombrar, cambiar tipo,
  quitar campo → nueva versión `v2` y periodo de convivencia.
- Formato: **JSON Schema** en lugar de Avro. Avro es más eficiente, pero JSON permite
  leer un topic con `kcat` y entender lo que pasa. En un proyecto de aprendizaje,
  la depurabilidad gana a los bytes. Revisable si el volumen lo justifica.
