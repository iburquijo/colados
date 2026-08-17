# ADR-0007 — Tecnología del simulador

- **Estado:** Aceptado
- **Fecha:** 2026-08-17

## Decisión

**Java 21 + Spring Boot** (opción A). Confirmada tras fijar que el objetivo de
aprendizaje del proyecto es la **arquitectura orientada a eventos**, no la simulación
ni el análisis de datos. Con ese objetivo, la ventaja de SimPy no compensa el coste de
un segundo toolchain.

## Contexto

El simulador es un proceso independiente que modela la planta física y publica
lecturas RFID por MQTT ([ADR-0006](0006-simulador-emite-solo-lecturas-crudas.md)).
Necesita: simulación de eventos discretos con control de tiempo (×1 a ×1000),
geometría 2D, distribuciones de probabilidad, un cliente MQTT y determinismo por semilla.

Al ser un proceso separado que solo habla MQTT, **puede escribirse en cualquier
lenguaje sin afectar al resto del sistema**. Es la decisión más fácilmente reversible
del proyecto.

## Opciones

### A) Java 21 + Spring Boot *(recomendada)*

**A favor:** un solo toolchain (Gradle, un Compose, un CI); reutiliza el módulo
`contracts` sin duplicar esquemas; `record`, clases selladas y *pattern matching*
encajan bien con actores y máquinas de estado; los *virtual threads* permiten un hilo
por máquina simulada sin coste; Testcontainers compartido.

**En contra:** sin librería de simulación de eventos discretos comparable a SimPy (hay
que escribir el planificador a mano, ~200 líneas); más verboso para el código
numérico del motor RF.

### B) Python + SimPy

**A favor:** SimPy resuelve la simulación de eventos discretos de fábrica; NumPy/SciPy
para el modelo RF y las distribuciones; mucho menos código; visualización y análisis
de trazas con pandas/matplotlib de forma inmediata.

**En contra:** segundo toolchain (venv, dependencias, imagen propia, CI aparte);
duplicación de los esquemas de `contracts` con riesgo de deriva (mitigable generando
las clases desde el JSON Schema); rendimiento peor a ×1000 con muchos tags.

### C) Go

**A favor:** binario único, arranque instantáneo, concurrencia natural.
**En contra:** tercer lenguaje del proyecto; sin ventaja clara sobre A o B para esto.

## Razones de la elección

1. **Fricción.** Un proyecto personal muere por fricción acumulada, y un segundo
   toolchain es fricción en cada build, cada Compose, cada cambio de esquema y cada
   pipeline de CI.
2. **El planificador de eventos discretos es asumible.** Lo que hace falta aquí —una
   cola de prioridad por instante de simulación y un reloj virtual— son unas 200
   líneas que se escriben una vez.
3. **`contracts/` sin duplicar.** El simulador consume directamente los tipos
   generados, con lo que desaparece el riesgo de que su `TagRead` derive del del backend.
4. **El objetivo es la arquitectura de eventos.** El simulador es un medio para
   generar entrada realista, no el objeto de estudio. Invertir en su ecosistema
   científico no sirve al objetivo.

**Qué se pierde, reconocido:** el análisis de trazas con pandas/matplotlib habría sido
mucho más cómodo que hacerlo en Java. Mitigación: el arnés de evaluación exporta las
trazas a CSV/Parquet y el análisis puntual se hace en un cuaderno aparte, fuera del
ciclo de build.

**Cuándo se revisaría:** si el modelo RF creciera hasta necesitar trilateración,
filtros bayesianos o ajuste de parámetros a partir de datos (ver ampliaciones en
`05-resolucion-ubicacion.md`), la balanza se inclinaría hacia Python. Como el
simulador es un proceso aislado que solo habla MQTT, reescribirlo sería sustituir un
componente, no migrar el sistema.

## Consecuencias

Sea cual sea la elección, se mantienen: el simulador es un proceso aparte, solo
publica `TagRead` por MQTT, es determinista por semilla y no depende del backend.
Cumplidas esas condiciones, cambiar de lenguaje más adelante es reescribir un
componente aislado, no migrar el sistema.
