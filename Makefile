.PHONY: up down logs ps

up:
	docker compose up -d postgres redis

down:
	docker compose down

logs:
	docker compose logs -f

ps:
	docker compose ps

api-test:
	cd backend/api && ./gradlew test

api-run:
	cd backend/api && ./gradlew bootRun

web-install:
	pnpm install

web-dev:
	pnpm --filter @monticker/web dev

web-build:
	pnpm --filter @monticker/web build
