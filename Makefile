.PHONY: up up-graph down logs test lint frontend-build

up:
	docker compose --profile core up --build

up-graph:
	docker compose --profile core --profile graph up --build

down:
	docker compose --profile core --profile graph down

logs:
	docker compose --profile core --profile graph logs -f api worker

test:
	cd backend && mvn -q test

lint:
	cd backend && mvn -q -DskipTests compile

frontend-build:
	cd frontend && npm run build
