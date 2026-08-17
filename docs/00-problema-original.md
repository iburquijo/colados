# 00 — El problema original (TFG, 2021)

Este documento fija el problema de negocio que motiva el proyecto. Sin él, el resto
de la arquitectura es una demo de tecnología sin razón de ser.

## 1. Contexto industrial

Planta de producción de aluminio. El flujo relevante:

1. **Colada** (*heat / cast*): se funde y se cuela una carga de aluminio con una
   composición y aleación determinadas. Una colada es la unidad de trazabilidad de
   calidad: si aparece un defecto metalúrgico, el reclamo se hace *sobre la colada*,
   no sobre una pieza suelta.
2. **Laminación / bobinado**: el material sale en **bobinas** (rollos) de varias
   toneladas cada una. Una colada produce N bobinas.
3. **Etiquetado**: cada bobina recibe una etiqueta de salida con su identificación
   (colada, aleación, espesor, ancho, peso).
4. **Almacenamiento en patio**: una máquina (carretilla de bobinas, puente grúa o
   pórtico) traslada la bobina desde la salida de línea hasta un hueco del patio.
   El patio está organizado en zonas / calles / huecos, con apilamiento en algunos casos.
5. **Movimientos internos**: reubicaciones, entrada a proceso posterior, cortes
   parciales que generan **sobrantes**.
6. **Expedición**: se carga en un camión contra un pedido y sale de planta.

## 2. El proceso *as-is* y dónde se rompe

| Paso | Cómo se hacía | Fallo |
|---|---|---|
| Ubicar bobina | El operario anota en papel "bobina 4471 → calle C, hueco 12" | Letra, prisas, ruido, guantes |
| Volcado a sistema | Al final del turno, si da tiempo, se teclea en el ERP | **Aquí está el problema real**: se pospone, se hace mal o no se hace |
| Reubicación | Rara vez se anota | El sistema apunta a un hueco donde ya no está la bobina |
| Sobrantes | Se conocen "de memoria" por los veteranos | Stock fantasma: se fabrica material que ya existía |
| Expedición | Albarán manual | Trazabilidad bobina↔camión reconstruida a posteriori |

Consecuencias medibles:

- **Tiempo de búsqueda**: un operario recorriendo el patio buscando una bobina.
- **Stock fantasma / stock oculto**: sobrantes que no se venden porque nadie sabe
  que están ahí; sobreproducción por no ver el disponible.
- **Doble ocupación**: una máquina llega a un hueco marcado como libre y está ocupado.
- **Trazabilidad rota**: ante una reclamación de calidad de un cliente, no se puede
  responder con certeza qué bobinas de qué colada se le enviaron.
- **Latencia de información**: el estado del patio en el sistema tiene horas de
  desfase respecto al patio real. Cualquier decisión de planificación parte de datos falsos.

> **La raíz del problema no es la falta de un sistema informático: es que la captura
> del dato depende de un humano que tiene otras cosas que hacer.** La solución tiene
> que capturar el dato *sin pedirle nada al operario*.

## 3. La solución propuesta en el TFG (2021)

Identificación por radiofrecuencia (RFID / UHF EPC Gen2) en tres puntos:

- **Tag en la etiqueta de salida de colada** → cada bobina lleva un identificador
  leíble sin contacto ni línea de visión directa.
- **Lector embarcado en la máquina** que mueve la bobina → se sabe *qué* bobina lleva
  encima y *cuándo* la coge y la suelta.
- **Antenas / referencias en las zonas del patio** → se sabe *dónde* está.

Con eso: ubicación en tiempo real de cada bobina, control de sobrantes, stock de lo
libre / reservado / expedido, y trazabilidad bobina↔camión.

## 4. Qué se implementó realmente en 2021 y por qué no bastaba

Prototipo:

- Arduino + ESP8266 (módulo WiFi) + lector RFID.
- Al leer un tag, se enviaba el identificador vía WiFi a **ThingSpeak**.
- Una **aplicación Java de escritorio** hacía *polling* contra ThingSpeak.
- La tarjeta estaba **ligada a una colada** y poco más: se podía editar el registro.

Limitaciones honestas del prototipo:

| Limitación | Impacto |
|---|---|
| No modelaba el patio | No existía el concepto de zona/hueco; no se podía decir *dónde* está algo |
| No modelaba máquinas | No se distinguía "la bobina pasa por delante" de "la bobina la lleva la grúa" |
| Polling contra un servicio de terceros | Latencia, dependencia externa, sin control de retención |
| Sin modelo de eventos | Solo estado actual, mutable, editable a mano → se pierde la trazabilidad |
| Sin tratamiento de ruido | Una lectura = una verdad. En RFID real eso es falso |
| Escritorio, monousuario | Ni operarios, ni supervisión, ni concurrencia |
| Sin persistencia propia | Sin histórico, sin auditoría, sin replay |

El prototipo demostraba que *se puede leer un tag y mandarlo por la red*. No abordaba
el problema de negocio, que es **inferir el estado del patio a partir de señales
imperfectas**.

## 5. Qué cambia en la versión de 2026

| 2021 | 2026 |
|---|---|
| Hardware real limitado, un lector | **Planta simulada completa**: patio, máquinas, coladas, camiones |
| ThingSpeak + polling | MQTT (campo) + Kafka (log de eventos) |
| Java Swing de escritorio | Backend Spring Boot + frontend Next.js en tiempo real |
| Estado mutable | Event store append-only + proyecciones reconstruibles |
| Una lectura = la verdad | Motor de resolución de ubicación con ventanas, histéresis y confianza |
| Sin sobrantes | Modelo de sobrantes con linaje (bobina padre → resto) |
| Sin expedición | Pedido → carga → camión → trazabilidad completa |

## 6. Preguntas abiertas sobre el dominio

Para afinar la simulación conviene fijar (o inventar razonadamente):

- Tonelaje típico de bobina y capacidad de la máquina.
- ¿Se apilan bobinas? ¿Cuántas alturas? ¿Solo en algunas zonas?
- ¿Cuántas bobinas salen por colada y cada cuánto hay colada?
- ¿El patio tiene cubierto y exterior con criterios distintos (oxidación)?
- ¿Hay reserva de material contra pedido antes de la carga?

Estas se responden en [`01-dominio.md`](01-dominio.md) con valores plausibles y
marcadas como asunciones de la simulación, no como hechos.
