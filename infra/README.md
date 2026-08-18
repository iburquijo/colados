# Infra local

Todo el entorno de desarrollo se levanta desde este directorio:

```bash
cd infra
docker compose up -d
docker compose ps      # ambos servicios deben quedar en "healthy"
```

| Servicio | Puerto | Qué es |
|---|---|---|
| `mosquitto` | 1883 | Broker MQTT, anónimo y sin persistencia ([ADR-0014](../docs/adr/0014-sin-autenticacion.md), [ADR-0009](../docs/adr/0009-estrategia-de-almacenamiento.md)) |
| `postgres` | 5432 | PostgreSQL 16, base `colados`, usuario/contraseña `colados` |

Para tirarlo todo, incluido el volumen de datos de PostgreSQL:

```bash
docker compose down -v
```

## Comprobar que el broker reparte de verdad

Que el contenedor esté `healthy` solo dice que el broker responde. Para verificar
el camino completo publicador → broker → suscriptor, en dos terminales:

```bash
# terminal 1 — suscriptor
docker exec colados-mosquitto mosquitto_sub -t 'colados/reads/+/batch' -q 1

# terminal 2 — publicador
docker exec colados-mosquitto mosquitto_pub -q 1 \
  -t 'colados/reads/R1/batch' \
  -m '{"lectorId":"R1","reads":[{"epc":"E28011","rssi":-52}]}'
```

El JSON debe aparecer en la terminal 1. Los topics reales están en
[`docs/03-contratos-eventos.md`](../docs/03-contratos-eventos.md).

## Si no levanta

- **`Cannot connect to the Docker daemon at unix:///var/run/docker.sock`.** No es el
  compose: es que el demonio de Docker no está corriendo. Arráncalo (Docker Desktop, o
  `sudo systemctl start docker`) y repite. `docker info` lo confirma: si falla en la
  sección `Server:`, no hay demonio.
- **Los logs de `mosquitto` salen vacíos.** Es lo esperado: `mosquitto.conf` fija
  `log_type warning` y `log_type error`, así que un arranque correcto no imprime nada.
  Para verlo hablar, sube el nivel con `log_type all` temporalmente.
- **`bind: address already in use`.** Ya tienes algo en 1883 o 5432 —otro Mosquitto o
  un PostgreSQL local—. Párralo, o cambia el lado izquierdo del mapeo de puertos.
