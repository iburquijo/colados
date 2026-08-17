# 03 — Contratos: topics y esquemas de evento

Fuente de verdad: módulo `contracts/`. De ahí se generan los POJOs de Java y los
tipos de TypeScript. Nadie define un evento a mano en su servicio.

## 1. Capas de evento

Tres niveles, y **no se deben mezclar**:

| Nivel | Ejemplo | Quién lo emite | Contenido |
|---|---|---|---|
| **Crudo** | `TagRead` | Lector (simulador) | Solo lo que el hardware puede saber |
| **Limpio** | `TagReadClean` | Módulo `ingest` | Validado, deduplicado, con EPC resuelto a bobina |
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
  pero se repite igualmente. Un mensaje debe ser interpretable fuera de su topic,
  porque al pasar a Kafka el topic se pierde.

### Payload `TagRead`

```json
{
  "schema": "colados.tagread.v1",
  "plantId": "PLANT-01",
  "readerId": "RDR-ZONE-C3",
  "readerType": "ZONE",
  "antennaId": 2,
  "epc": "E280116060000208C7A4B1F3",
  "rssi": -58.5,
  "readAt": "2026-08-17T09:14:23.184Z",
  "seq": 918273,
  "traceId": "0af7651916cd43dd8448eb211c80319c"
}
```

| Campo | Nota |
|---|---|
| `schema` | Versionado explícito desde el primer día. Migrar sin versión en el payload es doloroso. |
| `rssi` | dBm. Imprescindible: es lo que desempata antenas solapadas. |
| `readAt` | Reloj **del lector**. Puede ir desfasado o hacia atrás. |
| `seq` | Contador monótono por lector → clave de idempotencia `(readerId, seq)` y detección de huecos. |
| `traceId` | W3C trace context, propagado hasta el WebSocket. |

Lo que **no** lleva: `coilId`, `zoneId`, `slotId`, `event`. Si algún día aparecen ahí,
alguien está haciendo trampa.

### Payload `ReaderStatus`

```json
{
  "schema": "colados.readerstatus.v1",
  "readerId": "RDR-ZONE-C3",
  "status": "ONLINE",
  "uptimeS": 84213,
  "readsLastMinute": 412,
  "firmware": "2.3.1",
  "at": "2026-08-17T09:14:20.000Z"
}
```

LWT configurado: `{"schema":"colados.readerstatus.v1","readerId":"...","status":"OFFLINE"}`.

## 3. Topics Kafka

| Topic | Clave | Particiones | Retención | Contenido |
|---|---|---|---|---|
| `rfid.reads.raw` | `epc` | 6 | 30 d | Todas las lecturas validadas |
| `rfid.reads.clean` | `epc` | 6 | 7 d | Deduplicadas, EPC→bobina resuelto |
| `coil.events` | `coilId` | 6 | **infinita** | Eventos de dominio — el libro mayor |
| `coil.state` | `coilId` | 6 | **compactado** | Último estado conocido por bobina |
| `yard.slot.state` | `slotId` | 6 | compactado | Ocupación por hueco |
| `alerts` | `alertType` | 3 | 90 d | Alertas |
| `dlq.rfid.reads` | `readerId` | 1 | 90 d | Lecturas rechazadas + motivo |

Notas:

- **`coil.events` con retención infinita** es la decisión que sostiene el replay.
  Es un log de auditoría: qué se supo, cuándo se supo y por qué se dedujo.
- **Topics compactados** para el estado: un consumidor nuevo (o el frontend al
  arrancar) reconstruye la foto completa del patio leyendo el topic desde el principio,
  sin tocar la base de datos.
- **La DLQ guarda el motivo**, no solo el mensaje. Una DLQ sin diagnóstico es un
  cementerio.

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
| `CoilPickedUp` | Máquina la coge | `machineId, fromSlotId` |
| `CoilPlaced` | Depositada en hueco | `slotId, stackLevel` |
| `CoilRelocated` | De un hueco a otro | `fromSlotId, toSlotId` |
| `CoilLocationUncertain` | Confianza bajo umbral | `candidates[], reason` |
| `CoilMissing` | Sin lecturas > umbral | `lastSeenAt, lastSlotId` |
| `CoilReappeared` | Vuelve a leerse | `slotId, missingForS` |
| `CoilLocationDisputed` | Contradicción | `claims[]` |
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
| **De transporte** | MQTT QoS 1 reenvía | Deduplicar por `(readerId, seq)` en `ingest`. Se descarta. |
| **De lectura** | El tag se lee 20 veces/s por la misma antena | **No se descarta**: es señal legítima. Se agrega en ventana en `tracking`. La frecuencia de lectura *es información* (cerca vs lejos). |
| **De reproceso** | Replay deliberado desde Kafka | Se procesa normalmente contra proyecciones idempotentes (upsert por clave). |

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
