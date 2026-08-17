# ADR-0005 — Stack: Spring Boot en backend, Next.js en frontend

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Contexto

Propuesta inicial: Spring Boot en backend y Node.js en frontend. Hay que concretarla:
"Node.js" es un runtime, no una decisión de arquitectura de frontend.

## Decisión

- **Backend:** Java 21 + Spring Boot 3.
- **Frontend:** Next.js (App Router) + TypeScript + React.
- **Tiempo real:** WebSocket con STOMP.

## Backend: Java 21 + Spring Boot 3

**A favor:**

1. **Continuidad con el TFG.** El original era Java de escritorio. Que la versión de
   2026 siga siendo Java hace que la comparación 2021↔2026 sea sobre *arquitectura*
   y no sobre lenguaje. La narrativa del proyecto gana.
2. **Ecosistema de mensajería insuperable.** Spring Integration MQTT es de primera
   categoría, y si algún día entrara un broker
   ([ADR-0011](0011-sin-kafka-de-momento.md)) Spring Kafka está al lado. Este proyecto
   es, esencialmente, ingesta y eventos.
3. **Java 21:** *virtual threads* (miles de conexiones concurrentes sin programación
   reactiva), *records* para los eventos inmutables, *pattern matching* para
   despachar por tipo de evento, clases selladas para modelar la máquina de estados.
4. **Testcontainers** con soporte de primera para Mosquitto y Postgres.
5. **ArchUnit** para hacer cumplir las fronteras del monolito modular ([ADR-0003](0003-monolito-modular.md)).
6. Es el stack habitual en entornos industriales/ERP, que es el contexto del dominio.

**En contra, reconocido:** más verboso; arranque más lento que Go; JVM pesada en el
Compose. Ninguno decisivo.

**Alternativas:** Kotlin (mejor lenguaje, mismo ecosistema; se descarta solo por
continuidad con el TFG y por no añadir una variable más — sería una elección
perfectamente defendible); Go (excelente para la ingesta, ecosistema de datos más
pobre); Node en todo el stack (un solo lenguaje, pero mensajería y concurrencia
más flojas para este caso); Python/FastAPI (bueno para simular, flojo para el backbone).

## Frontend: Next.js + TypeScript

"Node.js en frontend" se concreta así:

1. **Next.js con App Router.** SSR para las vistas de consulta (stock, fichas) y
   componentes de cliente para el mapa en vivo.
2. **TypeScript obligatorio**, con los tipos **generados desde `contracts/`**. Si el
   backend cambia un evento, el frontend deja de compilar. Esa es la única forma
   realista de que tres módulos no deriven entre sí.
3. **Mapa del patio:** SVG con React para empezar (declarativo, inspeccionable,
   accesible). Si el rendimiento con cientos de huecos actualizándose lo exige, se
   pasa a Canvas o a PixiJS solo en ese componente.
4. **Estado de servidor:** TanStack Query para REST + un store ligero (Zustand) para
   el estado que llega por WebSocket. No Redux.
5. **Estilos:** Tailwind CSS.
6. **Gráficas:** Recharts para las series de precisión y ocupación.

**Alternativas:** React con Vite (más simple, sin SSR — opción válida si Next.js
resulta excesivo); Vue/Nuxt o SvelteKit (buenos, menor familiaridad); Angular
(pesado); Thymeleaf server-side (rompería el objetivo de tiempo real).

## Tiempo real: WebSocket con STOMP

Frente a SSE, que sería más simple:

- La consola del simulador necesita **canal de vuelta** (cambiar velocidad, inyectar
  ruido). Con SSE harían falta dos mecanismos.
- STOMP da **suscripción por topic** de serie: un cliente se suscribe solo a la zona
  que está mirando en vez de recibir todo el patio.
- Spring lo integra con `@MessageMapping` y broker en memoria, sin infraestructura extra.

**Coste asumido:** más complejo que SSE; hay que gestionar reconexión y resincronización
en el cliente. Al reconectar, el cliente pide una foto completa por REST y luego
reanuda el flujo — con SSE y `Last-Event-ID` esto sería más elegante.

## Consecuencias

**Positivas:** stack maduro y demandado; tipos compartidos de extremo a extremo;
continuidad narrativa con el TFG; excelente soporte de mensajería y de tests.

**Negativas:** dos toolchains (Gradle y npm) — se mitiga con Docker Compose y tareas
de Gradle que envuelven la build del frontend; JVM + Postgres + Mosquitto + Next.js
piden una máquina con RAM decente, aunque bastante menos desde que no hay broker de por
medio.
