# ADR-0003 — Monolito modular en Spring Boot, no microservicios

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Contexto

El sistema tiene módulos claramente distinguibles: ingesta, tracking, inventario,
expedición, alertas y API. La tentación evidente en un proyecto de aprendizaje sobre
arquitectura de eventos es desplegarlos como microservicios independientes.

## Decisión

**Un único despliegue de Spring Boot** con módulos Gradle/Maven separados y fronteras
explícitas. El simulador sí es un proceso aparte, por motivos de diseño
(ver [ADR-0006](0006-simulador-emite-solo-lecturas-crudas.md)).

## Razones

1. **Las fronteras del dominio aún no están estabilizadas.** Repartir en servicios
   antes de saber dónde están las costuras produce microservicios acoplados: lo peor
   de los dos mundos. El monolito modular permite mover una frontera con un *refactor*,
   no con una migración de despliegue.
2. **El proyecto ya enseña lo que interesa.** El aprendizaje que se busca es
   arquitectura orientada a eventos, ingesta de datos sucios y tiempo real. Nada de
   eso requiere separar procesos: la ingesta va por MQTT y el log de eventos vive en
   PostgreSQL igualmente.
3. **Coste operativo.** Seis servicios significan seis pipelines, seis imágenes, seis
   configuraciones y descubrimiento de servicios. En un proyecto personal eso es
   tiempo que no se dedica al problema interesante.
4. **Transacciones locales.** Actualizar la ocupación de un hueco y la ubicación de
   una bobina en la misma transacción es trivial en un monolito y es una saga
   distribuida en microservicios. Los invariantes del documento 01 son más fáciles de
   sostener así.
5. **Depurar es viable.** Un único proceso, un breakpoint, una traza de pila.
6. **La modularidad se puede verificar.** ArchUnit impone las reglas de dependencia
   entre módulos en un test. La frontera se defiende con CI, no con buena voluntad.

## Reglas de modularidad

Que sea un despliegue no significa que sea un plato de espaguetis:

1. Cada módulo expone una **API pública** (paquete `api`) y oculta el resto (`internal`).
2. **Ningún módulo accede a las tablas de otro.** Solo a través de su API o de eventos.
3. La comunicación entre módulos es preferentemente **por eventos**, no por llamada directa.
4. Ningún ciclo de dependencias entre módulos.
5. `api` (REST/WS) depende de todos; nadie depende de `api`.
6. `contracts` no depende de nada.
7. Cada regla anterior se comprueba con un test de ArchUnit.

Si estas reglas se cumplen, extraer un módulo a servicio propio el día que haga falta
es una tarde de trabajo. Si no se cumplen, ningún despliegue separado va a salvar el diseño.

## Cuándo se revisaría

Se extraería un módulo a servicio independiente si:

- `ingest` necesita escalar horizontalmente por volumen de lecturas, o
- `tracking` necesita su propio ciclo de despliegue y estado, o
- un módulo tiene un perfil de recursos radicalmente distinto al resto.

`ingest` y `tracking` son los candidatos naturales, en ese orden.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Microservicios desde el día 1 | Coste desproporcionado; fronteras aún inestables; sagas para invariantes que podrían ser transacciones. |
| Monolito sin módulos | Se convierte en espagueti; no se puede extraer nada después; no enseña diseño. |
| Serverless | Mal encaje con consumidores de larga duración y estado en ventana. |

## Consecuencias

**Positivas:** velocidad de desarrollo; transacciones locales; depuración simple;
un despliegue; camino de extracción abierto.

**Negativas:** no se practica comunicación entre servicios ni sagas distribuidas
(aceptable: se practica mensajería igualmente); escalado todo-o-nada; riesgo de que
las fronteras se erosionen si los tests de ArchUnit se relajan.
