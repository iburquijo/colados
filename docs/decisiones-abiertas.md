# Decisiones abiertas

Lo que aún no está cerrado, con una recomendación para cada punto. Al decidir, se
mueve a un ADR y se borra de aquí.

## Marco ya fijado

**Objetivo de aprendizaje del proyecto: arquitectura orientada a eventos.**

No es una declaración de intenciones vacía; es el criterio para resolver los empates
que vienen. Ante dos opciones equivalentes, gana la que enseñe más sobre eventos,
*streaming*, trazabilidad y tiempo real. Y a la inversa: cualquier cosa que no sirva a
ese objetivo se resuelve por la vía más simple que funcione, sin remordimientos.

Consecuencias directas ya aplicadas:

- Simulador en **Java + Spring Boot** ([ADR-0007](adr/0007-tecnologia-del-simulador.md)):
  es un medio para generar entrada realista, no el objeto de estudio.
- **Kafka en la fase 3** ([ADR-0002](adr/0002-kafka-como-backbone.md)), no antes: se
  introduce cuando haya dominio que reprocesar y el replay se pueda demostrar de verdad.
  La fase 3 es el corazón del proyecto, no un extra.
- El modelo RF y el motor de resolución se quedan en heurística bien medida; nada de
  filtros bayesianos hasta que todo lo demás funcione.

---

## 1. Alcance del patio simulado

Un patio de 300 huecos con 4 máquinas genera un volumen realista pero pesado para
depurar.

**Recomendación:** dos configuraciones. `dev` (1 zona, 20 huecos, 1 máquina) para
desarrollar, y `full` (patio completo) para demos y evaluación. Misma topología,
distinto tamaño, mismo código.

---

## 2. Precisión del modelo RF

Va desde "distancia < X → lee" hasta un modelo de propagación con multitrayecto.

**Recomendación:** empezar por *path loss* logarítmico con ruido gaussiano y curva
sigmoide de probabilidad de lectura. Es suficiente para generar ambigüedad realista.
El multitrayecto se simula con la perilla `phantomRate` en vez de modelarlo
físicamente — el efecto es el mismo y el coste, mucho menor.

---

## 3. ¿Se modela el apilamiento en altura?

Una bobina bajo otra se lee peor (apantallamiento metálico) y no se puede retirar sin
mover la de arriba (restricción LIFO).

**Recomendación:** modelarlo, pero en la fase 4. Es realista y da una regla de negocio
interesante ("no puedes servir esa bobina sin mover dos"), pero complica el planificador
del simulador. No en la fase 2.

---

## 4. Autenticación: ¿Keycloak o algo más simple?

**Recomendación:** sin autenticación hasta la fase 5. Cuando toque, Keycloak con OIDC
en lugar de JWT casero: es lo que se usa de verdad y el aprendizaje es transferible.
Roles: `OPERARIO` (consulta, resuelve incidencias), `SUPERVISOR` (expediciones,
reservas), `ADMIN` (datos maestros, simulador).

---

## 5. Datos maestros: ¿cómo se cargan?

Zonas, calles, huecos, lectores, antenas, máquinas.

**Recomendación:** definición declarativa en YAML (`infra/plant-layout.yaml`),
compartida por el simulador y el backend. El simulador la usa para la geometría
física; el backend, para el modelo lógico. Un único fichero evita que las dos visiones
del patio se desincronicen, que es un fallo silencioso y muy molesto de diagnosticar.

---

## 6. Nombre del proyecto

El repositorio se llama `colados`. En el dominio, el término correcto es **colada**
(femenino: una colada de aluminio). `colados` funciona como nombre propio y no genera
confusión real, pero si prefieres coherencia con el lenguaje ubicuo, `coladas` sería
más ajustado.

**Recomendación:** dejarlo. Renombrar un repo es fácil pero irrelevante para el
proyecto, y el nombre ya está.

---

## 7. Volumen objetivo

¿Cuántas lecturas por segundo debe aguantar? Determina si hay que preocuparse de
particionado y rendimiento.

**Estimación con los parámetros por defecto:** ~300 bobinas activas × ~2 antenas que
las ven × ~10 lecturas/s ≈ **6.000 lecturas/s**, que agrupadas en lotes de 200 ms son
solo ~150 mensajes MQTT/s ([ADR-0008](adr/0008-lotes-y-observaciones.md)).

Ese cambio desactivó el problema de almacenamiento, pero no el de proceso: el motor de
resolución sigue viendo 6.000 lecturas/s. Y el modo acelerado ×1000 no es viable con
lecturas completas: necesitará submuestreo.

**Decisión pendiente:** fijar un objetivo (¿5.000 lecturas/s procesadas?) y medirlo en
la fase 5.
