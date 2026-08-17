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
- [x] Fijar el objetivo de aprendizaje: **arquitectura orientada a eventos**
- [x] Simulador en Java + Spring Boot ([ADR-0007](adr/0007-tecnologia-del-simulador.md))
- [x] Sin Kafka: PostgreSQL como log de eventos ([ADR-0011](adr/0011-sin-kafka-de-momento.md))
- [x] Lector embarcado en la máquina + tags de ubicación ([ADR-0010](adr/0010-lector-en-la-maquina.md))
- [ ] Decisiones abiertas restantes ([`decisiones-abiertas.md`](decisiones-abiertas.md)) — ninguna bloquea la fase 1

**Entregable:** este repositorio de documentación.

---

## Fase 1 — El bucle mínimo que funciona

Objetivo: una lectura simulada llega al navegador.

- `infra/`: Docker Compose con Mosquitto y PostgreSQL
- `contracts/`: esquema `TagReadBatch` v1 + generación de tipos Java/TS
- `simulator/`: 1 calle con sus tags de ubicación, 1 máquina con lector embarcado,
  1 colada. Publica `TagReadBatch` por MQTT
- `backend/`: módulo `ingest` (MQTT→Postgres) + `api` (REST + WebSocket)
- `web/`: tabla de lecturas en vivo

**Entregable demostrable:** el simulador genera lecturas y se ven aparecer en el
navegador en tiempo real. Ya supera al prototipo de 2021 (sin polling, sin terceros).

---

## Fase 2 — El dominio de verdad

Objetivo: dejar de mostrar lecturas y empezar a mostrar **dónde está cada bobina**.

- Patio completo: zonas, calles, huecos y **tags de ubicación**
- Simulador completo: varias máquinas con lector embarcado, coladas, cola de tareas, motor RF
- Modelo de ruido con todas las perillas
- **Motor de resolución de ubicación** (el módulo `tracking`): máquina de estados de la
  carga y localización del depósito
- Máquina de estados de la bobina, incluidos `LOCATION_UNKNOWN` y `STALE`
- Event store en Postgres + proyecciones
- Web: **mapa de patio 2D en vivo** + ficha de bobina con línea de tiempo
- `sim/groundtruth` y cálculo de precisión de ubicación

**Entregable demostrable:** mapa del patio actualizándose solo, con máquinas que
recogen, circulan y depositan, y bobinas que a veces acaban en `LOCATION_UNKNOWN`.
**Aquí el proyecto ya cuenta una historia completa.**
Si solo se llega hasta aquí, el proyecto está justificado.

---

## Fase 3 — Reproducibilidad y evaluación ⭐

**El corazón del proyecto.** Las fases 1 y 2 construyen el dominio; esta lo hace
reproducible, auditable y **medible**. Si hay que apretar en algún sitio, es aquí.

- **Reconstrucción de proyecciones desde cero** (`rebuildProjections`) ← el hito de la
  fase, y se ejecuta en CI: una reconstrucción que solo funciona en teoría no funciona
- Reproceso desde `raw_read` con un algoritmo corregido, y **eventos de corrección**
  en lugar de modificar el histórico
- Arnés de evaluación contra `sim/groundtruth`: precisión de ubicación, error de
  vecindad, latencia de convergencia, deriva acumulada
- **Comparación de algoritmos**: misma traza, dos resolutores, dos precisiones
- Barrido de parámetros, empezando por `T_gone`, que es el que más pesa
- Test de regresión de precisión en CI
- Consola del simulador en la web (velocidad, perillas de ruido, escenarios)
- Cola de rechazos con motivo

**Entregable demostrable:** borrar las tablas de proyección, relanzar la reconstrucción
y ver el patio rehacerse solo. Y una gráfica de precisión frente a ruido que convierte
"parece que funciona" en un número defendible.

---

## Fase 4 — Negocio completo

- Módulo `inventory`: stock por aleación/espesor/ancho, libre vs reservado
- **Sobrantes** con linaje (`CoilSplit`) y su stock propio
- Módulo `shipping`: pedidos, reservas, preparación, carga, camión, albarán
- Trazabilidad completa: "¿qué bobinas de la colada 2026-0412 se enviaron y a quién?"
- Módulo `alerting`: invariantes, lectores caídos, discrepancias de ubicación
- **Inventario con lector de mano**: recorrido, confirmaciones, `InventoryDiscrepancy`
  y resolución de bobinas en `LOCATION_UNKNOWN`. Es lo que cierra el círculo abierto
  por [ADR-0010](adr/0010-lector-en-la-maquina.md): sin él, la deriva del inventario no
  se detecta nunca.

**Entregable:** las preguntas de negocio del documento 00 tienen respuesta.

---

## Fase 5 — Producción-*ish*

- Observabilidad: Prometheus, Grafana, trazas OpenTelemetry de extremo a extremo
- Autenticación con Keycloak: roles operario / supervisor / admin
- Tests de carga: ¿cuántas lecturas/s aguanta antes de que el `INSERT` sea el cuello
  de botella? El disparador de [ADR-0011](adr/0011-sin-kafka-de-momento.md) son ~10.000/s
- CI en GitHub Actions, incluido el **test de regresión de precisión**
- Despliegue: Kubernetes o un VPS con Compose (por decidir)

---

## Fase 6 — Optativos

Ninguno necesario; todos interesantes:

- **Migrar a Kafka**: sustituir el log en Postgres por un broker, ya con el dominio
  funcionando. Migrar un sistema que anda enseña más que construirlo con Kafka desde el
  principio, y los disparadores para hacerlo están escritos en
  [ADR-0011](adr/0011-sin-kafka-de-momento.md).
- **Hardware real**: un ESP32 + lector RC522/UHF publicando en el mismo topic MQTT.
  El backend no distingue si el `TagReadBatch` viene del simulador o de un lector físico
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
- Alta disponibilidad real (Postgres en HA).
- App móvil nativa.
