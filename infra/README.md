# Infra local

Lo normal es levantarlo desde la raiz con el [`Makefile`](../Makefile):

```bash
make up      # levanta y espera a que ambos servicios esten healthy
make smoke   # comprueba que el broker reparte de verdad
make down    # para, conservando los datos
```

Por debajo no hay nada mas que `docker compose`, y se puede usar directo:

```bash
cd infra
docker compose up -d --wait
docker compose ps      # ambos servicios deben quedar en "healthy"
```

| Servicio | Puerto | Qué es |
|---|---|---|
| `mosquitto` | 1883 | Broker MQTT, anónimo y sin persistencia ([ADR-0014](../docs/adr/0014-sin-autenticacion.md), [ADR-0009](../docs/adr/0009-estrategia-de-almacenamiento.md)) |
| `postgres` | 5432 | PostgreSQL 16, base `colados`, usuario/contraseña `colados` |

Para tirarlo todo, incluido el volumen de datos de PostgreSQL: `make reset`
(equivale a `docker compose down -v`).

## Comprobar que el broker reparte de verdad

Que el contenedor esté `healthy` solo dice que el broker responde. `make smoke`
verifica el camino completo publicador → broker → suscriptor y falla con código
distinto de cero si el mensaje no llega.

Para trastear a mano, en dos terminales: `make sub` y luego `make pub`. Los
topics reales están en
[`docs/03-contratos-eventos.md`](../docs/03-contratos-eventos.md).

## Si no levanta

- **`El demonio de Docker no responde`**, o el crudo
  `Cannot connect to the Docker daemon at unix:///var/run/docker.sock`. No es el
  compose: es que el demonio de Docker no está corriendo. Arráncalo (Docker Desktop, o
  `sudo systemctl start docker`) y repite. `docker info` lo confirma: si falla en la
  sección `Server:`, no hay demonio.
- **Los logs de `mosquitto` salen vacíos.** Es lo esperado: `mosquitto.conf` fija
  `log_type warning` y `log_type error`, así que un arranque correcto no imprime nada.
  Para verlo hablar, sube el nivel con `log_type all` temporalmente.
- **`bind: address already in use`.** Ya tienes algo en 1883 o 5432 —otro Mosquitto o
  un PostgreSQL local—. Párralo, o cambia el lado izquierdo del mapeo de puertos.
