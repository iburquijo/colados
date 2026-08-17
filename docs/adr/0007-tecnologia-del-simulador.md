# ADR-0007 — Tecnología del simulador

- **Estado:** **Propuesto** — pendiente de confirmar
- **Fecha:** 2026-08-17

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

## Recomendación

**Opción A (Java/Spring Boot)**, por una razón práctica: un proyecto personal muere
por fricción acumulada, y un segundo toolchain es fricción en cada build, cada
Compose y cada cambio de esquema. La ventaja de SimPy es real pero acotada — el
planificador de eventos discretos que hace falta aquí es sencillo y se escribe una vez.

**Cuándo cambiaría la recomendación:** si el objetivo de aprendizaje incluyera
explícitamente simulación y análisis de datos, la opción B pasaría a ser la mejor,
porque el ecosistema científico de Python no tiene rival y el análisis de las trazas
sería mucho más rico.

## Consecuencias

Sea cual sea la elección, se mantienen: el simulador es un proceso aparte, solo
publica `TagRead` por MQTT, es determinista por semilla y no depende del backend.
Cumplidas esas condiciones, cambiar de lenguaje más adelante es reescribir un
componente aislado, no migrar el sistema.
