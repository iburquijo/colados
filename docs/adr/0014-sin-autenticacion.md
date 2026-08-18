# ADR-0014 — Sin autenticación, pero con identidad como dato

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Contexto

El roadmap planteaba **Keycloak con OIDC** en la fase 5, con el argumento de que "es lo
que se usa de verdad y el aprendizaje es transferible".

Es un mal argumento para este proyecto. Esto es una simulación que corre en `localhost`
con `docker compose up`: no hay datos reales, no hay usuarios reales y no hay superficie
expuesta. Meter un proveedor de identidad —otro contenedor, un *realm* que configurar,
clientes, *scopes*, redirecciones— para proteger un patio inventado es coste puro.

## Decisión

**No hay autenticación.** Ni en la fase 1 ni en la 5.

Si algún día el proyecto sale de `localhost`, la respuesta es un **JWT firmado con
Spring Security**: un filtro, una clave y tres roles. No un proveedor de identidad.

Pero se mantiene una cosa que **no es seguridad y sí hace falta**: la **identidad como
dato**.

## Autenticación ≠ identidad

Son dos cosas distintas y aquí solo hace falta una:

| | Qué resuelve | ¿Hace falta? |
|---|---|---|
| **Autenticación** | Demostrar que eres quien dices | **No.** Nadie ataca un patio simulado en localhost |
| **Identidad como dato** | Registrar **quién** hizo qué | **Sí**, y es dominio, no seguridad |

El campo `confirmed_by` de `placement_confirmation` no está para controlar accesos: está
porque **sin él no se puede detectar al operario que confirma sin mirar**
([ADR-0012](0012-terminal-y-gestion-por-excepcion.md)). Un patrón de discrepancias
concentrado en una persona es información valiosa; sin registrar quién confirmó, esa
señal no existe.

Lo mismo con el inventario: `InventorySweep` necesita saber quién lo hizo para poder
comparar recorridos.

**Implementación:** un desplegable de "¿quién eres?" en el terminal y en la vista de
inventario, que guarda un `operatorId` en el evento. Cero infraestructura, cero
contraseñas, y el dato de trazabilidad queda registrado.

## Los roles, de momento, son un filtro de UI

`OPERARIO`, `SUPERVISOR` y `ADMIN` siguen existiendo como concepto —determinan qué
pantallas se ven— pero **no se hacen cumplir en el servidor**. Es una decisión consciente
y hay que decirla en voz alta: cualquiera que llame a la API puede hacer cualquier cosa.

En un sistema real eso sería un fallo grave. Aquí es correcto, porque el modelo de
amenaza es literalmente *nadie*.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Keycloak + OIDC | Un contenedor, un realm, clientes y redirecciones para proteger datos inventados en localhost. El aprendizaje es transferible, pero no es el objetivo de este proyecto (arquitectura de eventos) y se paga en cada arranque. |
| JWT casero desde el principio | Menos coste que Keycloak, pero sigue siendo trabajo y ceremonia en cada petición para proteger algo que no necesita protección. Queda como el camino si hiciera falta. |
| Basic Auth | Trivial de añadir, pero da la falsa sensación de que algo está protegido. Mejor sin nada y consciente. |
| Sin identidad ninguna | Sería más simple, pero se pierde `confirmed_by` y con él la detección de confirmaciones automáticas — que es una de las cosas interesantes del ADR-0012. |

## Cuándo revisar

- El proyecto se despliega en un VPS o queda accesible desde internet.
- Se enseña a terceros con datos que no sean de juguete.
- Alguien más colabora y conviene distinguir quién hizo qué de verdad, no por convenio.

En cualquiera de esos casos: **JWT con Spring Security**, no Keycloak, salvo que el
objetivo explícito pase a ser aprender OIDC.

## Consecuencias

**Positivas:** un contenedor menos; no hay que configurar nada para arrancar; la fase 5
adelgaza; la identidad que sí importa (quién confirmó) se resuelve con un desplegable.

**Negativas:** la API está completamente abierta, así que **el proyecto no es desplegable
tal cual** fuera de una red de confianza; no se practica OIDC ni la integración con un
proveedor de identidad, que es una carencia real de cara a un portfolio.

**Riesgo:** que alguien despliegue esto en un servidor sin leer este documento. Se
mitiga escribiéndolo en el README, no con código.
