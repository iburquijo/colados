# ADR-0010 — El lector viaja con la máquina, no con el patio

- **Estado:** Aceptado
- **Fecha:** 2026-08-17
- **Sustituye:** la topología de lectores de zona descrita en `02-arquitectura.md` §6

## Contexto

Hay dos formas de instrumentar un patio con RFID, y la elección condiciona el sistema
entero:

- **A — Antenas fijas en el patio.** Una o varias antenas por calle leen los tags de
  las bobinas almacenadas. La infraestructura está en el patio.
- **B — Lector embarcado en la máquina.** Cada carretilla o pórtico lleva un lector que
  ve dos cosas: el tag de la bobina que transporta y los **tags de ubicación** fijados
  en el suelo o la estructura, uno por hueco. La infraestructura viaja.

Los primeros documentos de este proyecto asumieron A. Fue un error de lectura del
planteamiento original, que decía literalmente *"receptores en las máquinas que lo
movían, como tags en las zonas que se almacenaban"*: **tags** en las zonas, no antenas.
Es decir, B.

## Decisión

**Arquitectura B**, con lectores fijos únicamente en tres puntos de paso donde una
lectura vale mucho y son pocos: salida de línea, báscula y puerta de expedición.

Se añade además el **inventario periódico con lector de mano**, que no es un extra:
es lo que cierra el círculo (ver "Consecuencias").

| Tipo de lector | Cuántos | Qué lee |
|---|---|---|
| `MACHINE` | 4 (3 carretillas + 1 pórtico) | Tag de la bobina transportada + tags de ubicación al pasar |
| `GATE` | 3 (línea, báscula, expedición) | Tags de bobina que cruzan el punto |
| `HANDHELD` | 1–2 | Inventario puntual, alta confianza |

Los **tags de ubicación** son transpondedores pasivos baratos, uno por hueco, cuyo EPC
está mapeado a un `slotId` en los datos maestros.

## Razones

1. **Es lo que se hace en la realidad.** Cubrir 300 huecos de patio exterior con
   antenas UHF es carísimo y técnicamente hostil: metal por todas partes, intemperie,
   alcance limitado. En logística de patio el lector viaja con la máquina.
2. **El volumen pasa a ser proporcional a la actividad, no al inventario.** Con A, una
   bobina parada generaba ~10 lecturas/s indefinidamente: el coste crecía con el stock
   almacenado, que es exactamente al revés de lo razonable. Con B, **una bobina
   depositada no genera absolutamente nada**. Patio lleno y sin movimiento = cero
   eventos.
3. **De ~6.000 lecturas/s a ~300 en punta**, y cero con las máquinas paradas.
4. **Mucho menos ruido.** El lector embarcado tiene alcance corto y ve lo que tiene
   delante. Desaparece el solapamiento entre calles contiguas, que era la principal
   fuente de ambigüedad en A.
5. **Coste: 4 lectores en vez de 15**, más tags de ubicación a céntimos la unidad.
6. **Los eventos salen casi directos del comportamiento del lector**, que es lo que
   intuitivamente se espera del sistema:

   ```
   ve el tag de la bobina, fuerte y continuo        → CARGADA
   va leyendo tags de ubicación mientras circula    → EN TRÁNSITO (con trayectoria)
   deja de ver el tag de la bobina; el último tag
      de ubicación con señal firme es C5-08         → DEPOSITADA EN C5-08
   ```

## Lo que se pierde, y cómo se compensa

**Con A el patio se reconfirmaba solo.** Las antenas fijas volvían a ver cada bobina
cada segundo, así que una desaparición se detectaba enseguida. Con B, **una vez
depositada, nadie vuelve a mirarla nunca**.

Consecuencia directa y grave: si el inventario se desvía —un tag de ubicación ilegible
en el momento del depósito, una bobina movida con una máquina sin lector, una lectura
perdida justo en el instante crítico— **el error se queda ahí indefinidamente y el
sistema no tiene forma de detectarlo por sí mismo**. Peor: el sistema seguirá afirmando
la ubicación equivocada con total aplomo.

Dos mitigaciones, y ambas forman parte del diseño:

1. **Confianza que decae con el tiempo.** Una ubicación confirmada hace 20 minutos y
   otra confirmada hace tres semanas **no valen lo mismo**, aunque el dato sea idéntico.
   La UI muestra la antigüedad de la última confirmación, siempre.
2. **Inventario periódico con lector de mano.** Un operario recorre el patio y confirma
   lo que hay. Cada discrepancia entre lo leído y lo que el sistema creía es un evento
   `InventoryDiscrepancy`: mide la deriva real y la corrige. Es lo que hacen las plantas
   de verdad, y aquí es lo único que cierra el círculo.

También se aprovecha un momento gratuito de reconfirmación: **al recoger** una bobina,
la máquina lee el tag de ubicación del hueco del que la saca. Si no coincide con donde
el sistema creía que estaba, se descubre la deriva de forma retroactiva sin coste alguno.

## ¿Sigue habiendo un problema interesante que resolver?

Sí, y sigue siendo el núcleo del proyecto — solo cambia de forma:

- **¿Cuándo exactamente se soltó la bobina?** El tag no deja de leerse de golpe: se
  desvanece. Hay que definir un criterio de *ausencia* y equivocarse en él desplaza la
  ubicación al hueco de al lado.
- **¿Cuál de los tags de ubicación es el destino?** En el instante del depósito puede
  haber varios al alcance. Solo uno persiste después.
- **Depósito sin tag de ubicación legible** (sucio, tapado, roto) → la bobina queda en
  `LOCATION_UNKNOWN`. El sistema debe admitirlo, no inventar el hueco más probable.
- **Lectura cruzada entre máquinas** que trabajan cerca: la carretilla A lee la bobina
  que lleva la B.
- **Deriva del inventario**, que en A no existía y aquí es un problema de primera clase.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| A — antenas fijas por calle | Infraestructura desproporcionada, volumen proporcional al stock, ruido alto por solapamiento. Se documentó por error de lectura del planteamiento inicial. |
| A + B combinadas | Máximo realismo y máxima complejidad. No aporta al objetivo de aprendizaje lo que cuesta. |
| RTLS con tags activos (UWB) sobre las bobinas | Posición continua y precisa, pero tags con batería a decenas de euros por bobina: inviable para consumibles que salen de planta. |
| GPS en la máquina en lugar de tags de ubicación | Precisión de metros, insuficiente para distinguir huecos contiguos, y malo bajo cubierto. Sería un complemento, no un sustituto. |

## Consecuencias

**Positivas:** infraestructura realista y barata; volumen proporcional a la actividad;
mucho menos ruido; los eventos de dominio salen de forma natural del comportamiento del
lector; el inventario con lector de mano entra como caso de uso legítimo en lugar de
como adorno.

**Negativas:** **no hay reconfirmación pasiva** — es la contrapartida seria y hay que
convivir con ella; la ubicación depende de un único instante crítico (el depósito), así
que un fallo ahí no se corrige solo; aparece un estado nuevo, `LOCATION_UNKNOWN`, que
hay que gestionar en la UI; el modelo de confianza pasa a depender fuertemente del
tiempo transcurrido desde la última confirmación.

**Impacto documental:** obliga a rehacer la topología de lectores, el modelo RF del
simulador, el motor de resolución de ubicación y parte del modelo de dominio. Las
cifras de volumen de [ADR-0008](0008-lotes-y-observaciones.md) y
[ADR-0009](0009-estrategia-de-almacenamiento.md) bajan un orden de magnitud, pero
**ambas decisiones siguen siendo válidas**: agrupar en lotes es lo que hace el hardware
real, y colapsar en observaciones sigue siendo la abstracción correcta.
