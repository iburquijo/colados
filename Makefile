# Atajos para el entorno local de Colados. Aqui no hay magia: todo esto envuelve
# 'docker compose' y './gradlew'. Si algo falla, el comando que imprime Make es el
# mismo que puedes lanzar a mano para depurarlo.
#
#   make          -> ayuda
#   make up       -> levanta la infra
#   make smoke    -> comprueba que el broker reparte de verdad

COMPOSE := docker compose -f infra/docker-compose.yml
GRADLE  := ./gradlew

# Servicio para 'make logs'. Sobreescribible:  make logs SVC=postgres
SVC ?= mosquitto

# Mensaje de 'make smoke': un lote de lecturas como el del simulador
# (docs/03-contratos-eventos.md).
SMOKE_TOPIC   := colados/reads/R1/batch
SMOKE_PAYLOAD := {"lectorId":"R1","reads":[{"epc":"E28011","rssi":-52}]}

.DEFAULT_GOAL := help
.PHONY: help up down reset ps logs smoke sub pub psql build test backend simulator require-docker require-up

help: ## Muestra esta ayuda
	@echo "Colados - entorno local"
	@echo
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-11s\033[0m %s\n", $$1, $$2}'
	@echo

## --- Infraestructura ---------------------------------------------------------

up: require-docker ## Levanta mosquitto y postgres y espera a que esten healthy
	$(COMPOSE) up -d --wait
	@$(COMPOSE) ps

down: ## Para los servicios, conservando los datos de postgres
	$(COMPOSE) down

reset: ## Para los servicios y BORRA el volumen de postgres (empezar de cero)
	$(COMPOSE) down -v

ps: ## Estado y salud de los servicios
	@$(COMPOSE) ps

logs: ## Sigue el log de un servicio (make logs SVC=postgres)
	$(COMPOSE) logs -f $(SVC)

## --- Comprobaciones ----------------------------------------------------------

smoke: require-up ## Publica y consume un mensaje: verifica el broker de punta a punta
	@docker exec -d colados-mosquitto sh -c 'mosquitto_sub -t "$(SMOKE_TOPIC)" -q 1 -C 1 -W 5 > /tmp/smoke.out 2>&1'
	@sleep 1
	@docker exec colados-mosquitto mosquitto_pub -q 1 -t '$(SMOKE_TOPIC)' -m '$(SMOKE_PAYLOAD)'
	@sleep 2
	@if docker exec colados-mosquitto test -s /tmp/smoke.out; then \
		echo "OK: publicado en $(SMOKE_TOPIC) y recibido por el suscriptor:"; \
		docker exec colados-mosquitto cat /tmp/smoke.out; \
	else \
		echo "FALLO: el mensaje no llego al suscriptor. Mira 'make logs'."; \
		exit 1; \
	fi

sub: require-up ## Suscriptor a todos los topics de colados (Ctrl-C para salir)
	docker exec -it colados-mosquitto mosquitto_sub -v -q 1 -t 'colados/#'

pub: require-up ## Publica el mensaje de prueba (util con 'make sub' en otra terminal)
	docker exec colados-mosquitto mosquitto_pub -q 1 -t '$(SMOKE_TOPIC)' -m '$(SMOKE_PAYLOAD)'

psql: require-up ## Abre una consola psql contra la base colados
	docker exec -it colados-postgres psql -U colados -d colados

## --- Codigo ------------------------------------------------------------------

build: ## Compila y empaqueta contracts, backend y simulator
	$(GRADLE) build

test: ## Pasa los tests
	$(GRADLE) test

backend: up ## Arranca el backend (levanta antes la infra)
	$(GRADLE) :backend:bootRun

simulator: up ## Arranca el simulador (levanta antes la infra)
	$(GRADLE) :simulator:bootRun

## --- Guardas -----------------------------------------------------------------

# El fallo mas comun no es el compose, es que no hay demonio de Docker detras.
# Mejor decirlo claro que dejar que salga el "Cannot connect to the Docker daemon".
require-docker:
	@docker info > /dev/null 2>&1 || { \
		echo "El demonio de Docker no responde."; \
		echo "Arranca Docker Desktop (o 'sudo systemctl start docker') y repite."; \
		exit 1; \
	}

require-up: require-docker
	@docker compose -f infra/docker-compose.yml ps --status running --services 2>/dev/null \
		| grep -q mosquitto || { \
		echo "mosquitto no esta levantado. Lanza 'make up' primero."; \
		exit 1; \
	}
