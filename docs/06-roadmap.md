# 06 — Roadmap

Criterio: **cada fase termina en algo que funciona y se puede enseñar.** Nada de
"la fase 3 es cuando por fin arranca". Si el proyecto se abandona en la fase 2,
lo hecho hasta ahí tiene valor por sí solo.

---

## Fase 0 — Diseño *(actual)*

- [x] Documentar el problema original y sus limitaciones
- [x] Lenguaje ubicuo y modelo de dominio
- [x] Arquitectura y ADRs
- [x] Contratos de evento
- [x] Diseño del simulador y del motor de resolución
- [ ] Cerrar las decisiones abiertas ([`decisiones-abiertas.md`](decisiones-abiertas.md))

**Entregable:** este repositorio de documentación.

---

## Fase 1 — El bucle mínimo que funciona

Objetivo: una lectura simulada llega al navegador. Sin Kafka todavía.

- `infra/`: Docker Compose con Mosquitto y PostgreSQL
- `contracts/`: esquema `TagRead` v1 + generación de tipos Java/TS
- `simulator/`: 1 zona, 1 calle, 1 máquina, 1 colada. Publica `TagRead` por MQTT
- `backend/`: módulo `ingest` (MQTT→Postgres) + `api` (REST + WebSocket)
- `web/`: tabla de lecturas en vivo

**Entregable demostrable:** el simulador genera lecturas y se ven aparecer en el
navegador en tiempo real. Ya supera al prototipo de 2021 (sin polling, sin terceros).

---

## Fase 2 — El dominio de verdad

Objetivo: dejar de mostrar lecturas y empezar a mostrar **dónde está cada bobina**.

- Patio completo: zonas, calles, huecos, coberturas solapadas
- Simulador completo: varias máquinas, coladas, cola de tareas, motor RF
- Modelo de ruido con todas las perillas
- **Motor de resolución de ubicación** (el módulo `tracking`)
- Máquina de estados de la bobina, incluidos `MISSING` y `DISPUTED`
- Event store en Postgres + proyecciones
- Web: **mapa de patio 2D en vivo** + ficha de bobina con línea de tiempo
- `sim/groundtruth` y cálculo de precisión de ubicación

**Entregable demostrable:** mapa del patio actualizándose solo, con bobinas que se
mueven, se pierden y se recuperan. **Aquí el proyecto ya cuenta una historia completa.**
Si solo se llega hasta aquí, el proyecto está justificado.

---

## Fase 3 — Kafka y el replay

Objetivo: convertir el sistema en algo reproducible y auditable.

- Kafka (KRaft) + Schema Registry en el Compose
- `ingest` publica en `rfid.reads.raw`; `tracking` pasa a Kafka Streams
- `coil.events` con retención infinita; topics compactados de estado
- **Reconstrucción de proyecciones desde cero por replay** ← el hito de la fase
- DLQ con motivo
- Consola del simulador en la web (velocidad, ruido, escenarios)

**Entregable demostrable:** borrar las tablas de proyección, relanzar el replay y ver
el patio reconstruirse solo. Y: cambiar un parámetro del algoritmo, reprocesar la
misma historia y **comparar precisiones**. Eso es lo que Kafka compra aquí.

---

## Fase 4 — Negocio completo

- Módulo `inventory`: stock por aleación/espesor/ancho, libre vs reservado
- **Sobrantes** con linaje (`CoilSplit`) y su stock propio
- Módulo `shipping`: pedidos, reservas, preparación, carga, camión, albarán
- Trazabilidad completa: "¿qué bobinas de la colada 2026-0412 se enviaron y a quién?"
- Módulo `alerting`: invariantes, lectores caídos, discrepancias de ubicación

**Entregable:** las preguntas de negocio del documento 00 tienen respuesta.

---

## Fase 5 — Producción-*ish*

- Observabilidad: Prometheus, Grafana, trazas OpenTelemetry de extremo a extremo
- Autenticación con Keycloak: roles operario / supervisor / admin
- Tests de carga: ¿cuántas lecturas/s aguanta antes de acumular lag?
- CI en GitHub Actions, incluido el **test de regresión de precisión**
- Despliegue: Kubernetes o un VPS con Compose (por decidir)

---

## Fase 6 — Optativos

Ninguno necesario; todos interesantes:

- **Hardware real**: un ESP32 + lector RC522/UHF publicando en el mismo topic MQTT.
  El backend no distingue si el `TagRead` viene del simulador o de un lector físico
  — que sea así es la prueba de que la frontera del simulador estaba bien puesta.
  Cierre poético con el TFG de 2021.
- App móvil / PWA para el lector de mano.
- Resolución de ubicación con filtro bayesiano y comparación contra la heurística.
- Optimización de asignación de huecos (qué bobina dónde para minimizar recorridos).
- Gemelo digital 3D del patio (three.js).

---

## Qué no se va a hacer

Escrito para no perder el tiempo más adelante:

- Integración real con un ERP.
- Multiplanta / multitenancy.
- Microservicios separados salvo que un módulo lo pida a gritos.
- Alta disponibilidad real (Kafka replicado, Postgres en HA).
- App móvil nativa.
