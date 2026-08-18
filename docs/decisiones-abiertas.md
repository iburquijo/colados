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
- **Sin Kafka** ([ADR-0011](adr/0011-sin-kafka-de-momento.md)): tras cambiar la
  topología de lectores, ningún argumento técnico lo sostenía. PostgreSQL hace de log de
  eventos. Queda como migración opcional en la fase 6, con disparadores concretos
  escritos para reabrir la decisión.
- El modelo RF y el motor de resolución se quedan en heurística bien medida; nada de
  filtros bayesianos hasta que todo lo demás funcione.
- **Patio simple por defecto** ([ADR-0013](adr/0013-patio-simple-y-capacidad-de-hueco.md)):
  30 huecos, una carretilla, capacidad 1 por hueco. El perfil `full` queda para las demos
  y la evaluación. Cierra las antiguas decisiones 1 (alcance del patio) y 3 (apilamiento).
- **Build con Gradle** (Kotlin DSL, multi-módulo, wrapper)
  ([ADR-0005](adr/0005-stack-backend-y-frontend.md)).

---

## 1. Precisión del modelo RF

Va desde "distancia < X → lee" hasta un modelo de propagación con multitrayecto.

**Recomendación:** empezar por *path loss* logarítmico con ruido gaussiano y curva
sigmoide de probabilidad de lectura. Es suficiente para generar ambigüedad realista.
El multitrayecto y las lecturas cruzadas se simulan con perillas
(`crossMachineReadRate`, `locationTagUnreadableRate`) en vez de modelarlos físicamente:
el efecto es el mismo y el coste, mucho menor.

---

## 2. Autenticación: ¿Keycloak o algo más simple?

**Recomendación:** sin autenticación hasta la fase 5. Cuando toque, Keycloak con OIDC
en lugar de JWT casero: es lo que se usa de verdad y el aprendizaje es transferible.
Roles: `OPERARIO` (consulta, resuelve incidencias), `SUPERVISOR` (expediciones,
reservas), `ADMIN` (datos maestros, simulador).

---

## 3. Datos maestros: ¿cómo se cargan?

Zonas, calles, huecos, **tags de ubicación** (el mapa EPC→hueco), lectores y máquinas.

**Recomendación:** definición declarativa en YAML (`infra/plant-layout.yaml`),
compartida por el simulador y el backend. El simulador la usa para la geometría
física; el backend, para el modelo lógico. Un único fichero evita que las dos visiones
del patio se desincronicen, que es un fallo silencioso y muy molesto de diagnosticar.

---

## 4. Nombre del proyecto

El repositorio se llama `colados`. En el dominio, el término correcto es **colada**
(femenino: una colada de aluminio). `colados` funciona como nombre propio y no genera
confusión real, pero si prefieres coherencia con el lenguaje ubicuo, `coladas` sería
más ajustado.

**Recomendación:** dejarlo. Renombrar un repo es fácil pero irrelevante para el
proyecto, y el nombre ya está.

---

## 5. Volumen objetivo

¿Cuántas lecturas por segundo debe aguantar? Determina si hay que preocuparse de
particionado y rendimiento.

**Estimación con los parámetros por defecto:** ~80 lecturas/s por máquina activa,
**~300/s en punta** con las cuatro trabajando y **cero cuando están paradas**. Agrupadas
en lotes de 200 ms son ~20 mensajes MQTT/s
([ADR-0008](adr/0008-lotes-y-observaciones.md),
[ADR-0010](adr/0010-lector-en-la-maquina.md)).

Con esas cifras no hay problema de volumen: ni de almacenamiento (~1 GB/día) ni de
proceso. Es lo que dejó a Kafka sin justificación
([ADR-0011](adr/0011-sin-kafka-de-momento.md)).

Lo que sí sigue apretando es el **modo acelerado**: a ×1000 no es viable generar todas
las lecturas y necesitará submuestreo.

**Decisión pendiente:** fijar un objetivo (¿2.000 lecturas/s procesadas, con margen ×6
sobre la punta?) y medirlo en la fase 5.
