# ADR-0006 — El simulador emite únicamente lecturas crudas de tag

- **Estado:** Aceptado
- **Fecha:** 2026-08-17
- **Importancia:** La decisión más consecuente del proyecto

## Contexto

No hay hardware. Toda la entrada del sistema la produce un simulador escrito por
nosotros. Eso plantea una pregunta que decide si el proyecto vale algo o no:

**¿Qué nivel de abstracción emite el simulador?**

Como el simulador conoce la verdad física —sabe que la bobina 4471 está en el hueco
C3-12 porque él mismo la puso ahí—, sería trivial publicar directamente
`{"evento":"BOBINA_ALMACENADA","bobina":"4471","hueco":"C3-12"}`. El backend
guardaría el dato y el mapa del patio funcionaría perfectamente el primer día.

## Decisión

**El simulador publica exclusivamente `TagRead`:**

```json
{ "readerId": "...", "antennaId": 2, "epc": "E280...", "rssi": -58.5, "readAt": "...", "seq": 918273 }
```

Prohibido en el payload: `coilId`, `slotId`, `zoneId`, `machineId`, `event`, `action`.
Un lector RFID físico no conoce ninguna de esas cosas.

La verdad física se publica **aparte**, en `sim/groundtruth`, y **el backend no la
consume**. Solo la usa el arnés de evaluación para medir la precisión.

## Razones

1. **Si el simulador emite eventos de dominio, no queda proyecto.** El backend se
   reduce a un CRUD que copia mensajes a una tabla. Todo lo interesante —resolución de
   ubicación, ventanas, histéresis, confianza, ambigüedad, estados `MISSING` y
   `DISPUTED`— desaparece porque el problema ya viene resuelto desde fuera.
2. **Es la única simulación honesta.** El sistema real recibe lecturas, no verdades.
   Un simulador que entrega verdades simula un mundo que no existe y valida un
   sistema que no funcionaría.
3. **Sustituibilidad por hardware real.** En la fase 6, un ESP32 con un lector
   publicando en el mismo topic con el mismo esquema funciona sin tocar una línea del
   backend. Esa sustituibilidad **es la prueba** de que la frontera está bien puesta.
   Con eventos de dominio, ningún lector real podría reemplazar al simulador jamás.
4. **Obliga a modelar el fallo.** Al no poder emitir la verdad, hay que modelar
   lecturas perdidas, fantasmas, solapamiento y desfase de reloj. Eso da una batería
   de escenarios adversos gratis y convierte el sistema en algo evaluable.
5. **Permite medir de verdad.** Con la verdad en un canal separado que el backend
   ignora, se obtiene una métrica objetiva de precisión. Si el backend consumiera la
   verdad, cualquier medición sería circular.

## La regla operativa

> **Antes de añadir un campo a un mensaje del simulador, preguntarse:
> ¿podría un lector RFID físico conocer este dato?**
> Si la respuesta es no, el campo no va ahí.

## Cómo se hace cumplir

1. **Test de arquitectura (ArchUnit):** el módulo `simulator` no puede depender de los
   paquetes de dominio del backend.
2. **Validación de esquema:** `contracts` define `TagRead` con
   `additionalProperties: false`. Un campo de más falla la validación.
3. **Test de aislamiento:** el backend arranca en los tests de integración **sin
   suscribirse** a `sim/groundtruth`. Si alguien lo conecta, el test de precisión
   pasa al 100 % y salta un aserto que exige que sea < 100 %. Un resultado perfecto
   es, aquí, síntoma de trampa.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Emitir eventos de dominio directamente | Elimina el problema interesante; imposible sustituir por hardware real; convierte el backend en un CRUD. |
| Nivel intermedio ("tag X visto en zona Y") | Tentador y aparentemente inofensivo, pero regala la resolución espacial, que es justo la parte difícil. Un lector conoce su antena, no su significado. |
| Simulador dentro del backend | Rompe la frontera de proceso, invita a atajos y hace imposible medir de forma independiente. |
| Trazas grabadas de un dataset RFID público | Interesante como complemento y previsto en modo `replay`, pero no permite explorar escenarios ni tener verdad física asociada. |

## Consecuencias

**Positivas:** el proyecto tiene un problema real que resolver; sustituible por
hardware; evaluación objetiva; modos de fallo modelados explícitamente.

**Negativas:** mucho más trabajo — hay que escribir un motor RF, un modelo de ruido y
un motor de resolución que en la alternativa fácil no existirían; la fase 1 tarda más
en enseñar algo bonito; hay que resistir la tentación, cada vez que algo no cuadre,
de "pasar el `coilId` solo para esta prueba".

**La tentación es real y volverá.** Este ADR existe para que, cuando vuelva, la
respuesta ya esté escrita.
