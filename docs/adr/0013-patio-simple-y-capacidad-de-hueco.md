# ADR-0013 — Patio simple, capacidad de hueco y desambiguación al recoger

- **Estado:** Aceptado
- **Fecha:** 2026-08-17
- **Cierra:** las decisiones abiertas 1 (alcance del patio) y 3 (apilamiento)

## Contexto

El patio documentado hasta ahora tenía 5 zonas, ~300 huecos y 4 máquinas. Es un tamaño
realista para una demo final, pero **pesado para empezar a programar**: obliga a tener
el motor de resolución, el planificador de tareas y el mapa funcionando antes de poder
ver nada.

Y quedaba abierto el apilamiento: si una posición admite varias bobinas, hay que decidir
cuánta física se modela (orden LIFO, apantallamiento, "para sacar la de abajo hay que
mover la de arriba").

## Decisión

**El hueco tiene una capacidad configurable, y por defecto es 1.**

```yaml
# infra/plant-layout.yaml — perfil por defecto
yard:
  profile: simple
  zones:
    - id: A
      rows: [A1, A2, A3]
      slotsPerRow: 10          # 30 huecos en total
      slotCapacity: 1          # ← una bobina por posición
  machines: 1                  # una carretilla con lector embarcado
  gates: [LINE_EXIT]
```

Subir `slotCapacity` a 2 o 3 **no cambia el modelo de datos ni el algoritmo**: solo hace
que un hueco pueda contener varias bobinas y que aparezca ambigüedad al recoger.

**La ambigüedad al recoger se resuelve preguntando, no infiriendo.** Si al cargar el
lector ve dos tags de bobina con señal fuerte, el terminal pregunta *"¿cuál te llevas?"*
y el operario lo dice. Es exactamente el mecanismo de
[ADR-0012](0012-terminal-y-gestion-por-excepcion.md): **maquinaria cero, un caso más en
la tabla de excepciones.**

**No se modela la física del apilamiento.** Ni orden LIFO, ni obligación de mover la de
arriba para sacar la de abajo, ni apantallamiento entre bobinas apiladas. Un hueco es un
**conjunto de hasta N bobinas**, sin orden.

## Razones

1. **Se puede empezar a programar ya.** 30 huecos y una carretilla caben en la cabeza y
   en una pantalla. La fase 1 deja de necesitar media arquitectura para enseñar algo.
2. **Capacidad 1 elimina un problema entero sin cerrar la puerta.** Con una bobina por
   hueco, la ocupación es un booleano y la ambigüedad al recoger no existe. Y el día que
   se suba a 2, el único cambio es que aparece una pregunta más en el terminal.
3. **Preguntar es más barato y más honesto que inferir.** Distinguir por RSSI cuál de
   dos bobinas apiladas va a bordo es poco fiable: están a menos de un metro una de otra.
   Hay un humano delante que lo sabe con certeza; preguntarle cuesta un toque.
4. **La física del apilamiento no aporta al objetivo.** Modelar LIFO y apantallamiento es
   trabajo de simulación que no enseña nada sobre arquitectura de eventos, que es el
   objetivo del proyecto. Si el operario tiene que mover la de arriba, lo hace, y eso
   genera sus propios `CoilPickedUp` / `CoilPlaced` de forma natural: **la realidad queda
   registrada aunque el modelo no la imponga.**
5. **El invariante que importa se mantiene**: la ocupación de un hueco nunca supera su
   capacidad, y lo garantiza una restricción en la base de datos.

## Los dos perfiles

| | `simple` (por defecto) | `full` (demo y evaluación) |
|---|---|---|
| Zonas | 1 | 5 |
| Huecos | 30 | ~300 |
| Capacidad por hueco | 1 | 1–2 según zona |
| Máquinas | 1 | 4 |
| Portales | salida de línea | línea, báscula, expedición |
| Bobinas por colada | 4–6 | 8–20 |

**Mismo código, misma topología, distinto tamaño.** El perfil es un fichero YAML, no una
rama del código: si `simple` y `full` divergieran en comportamiento, el perfil dejaría de
ser configuración y pasaría a ser una variante que mantener por duplicado.

## Nuevo caso en la tabla de excepciones

Al **recoger**, además de los casos ya previstos:

| Situación | Qué hace el sistema |
|---|---|
| Un solo tag de bobina con señal de "a bordo" | `CoilPickedUp`. **No pregunta.** |
| **Dos o más tags de bobina con señal fuerte** | `PickupNeedsConfirmation`: el terminal ofrece las bobinas del hueco y el operario elige |
| Ninguno reconocido | Se espera; si persiste, queda sin resolver hasta el inventario |

Con `slotCapacity: 1` el segundo caso solo puede venir de contaminación entre máquinas,
así que en el patio simple es raro. Al subir la capacidad se vuelve rutinario, y ahí es
donde el caso se gana su sitio.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| Patio completo desde el principio | Retrasa el primer resultado visible y obliga a tener el planificador de tareas antes de poder probar nada. |
| Sin apilamiento nunca | Cerraría una posibilidad realista por nada: la capacidad es un campo y la ambigüedad ya tiene mecanismo. |
| Modelar LIFO y apantallamiento | Trabajo de simulación considerable que no sirve al objetivo de aprendizaje. Los movimientos reales quedan registrados igual. |
| Inferir por RSSI cuál de las apiladas se lleva | Poco fiable a esa distancia, y hay una fuente humana fiable a un toque. |
| Dos configuraciones con código distinto | El perfil debe ser datos. Dos ramas de código serían dos sistemas que mantener. |

## Consecuencias

**Positivas:** la fase 1 arranca con 30 huecos y una carretilla; la ocupación con
capacidad 1 es trivial; el apilamiento queda disponible sin rehacer nada; la ambigüedad
reutiliza el terminal en vez de inventar mecanismo.

**Negativas:** el patio simple **no ejercita** el solapamiento entre calles, la
saturación ni la contaminación entre máquinas, así que hay que pasar a `full` antes de
creerse las métricas; al no modelar LIFO, el sistema no puede avisar de "no puedes servir
esa bobina sin mover dos", que era una regla de negocio con gracia.

**Riesgo:** que `full` se quede sin usar por comodidad y todo se valide contra un patio
de juguete. El antídoto es que el arnés de evaluación y el test de regresión de precisión
corran **siempre sobre `full`**, aunque el desarrollo diario se haga en `simple`.
