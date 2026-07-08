.PHONY: up up-full up-pinpoint up-msa down logs ps k8s-dev k8s-prod k8s-down k8s-build \
        monitoring-up monitoring-down monitoring-status \
        pinpoint-up pinpoint-down \
        quant-build quant-run

up:
	docker compose up -d postgres redis

# 전체 스택 (api + worker + Kafka + 모니터링)
# Stage 4: worker 틱이 Kafka를 경유한다 (MockPriceGenerator → market.ticks → TickKafkaConsumer)
up-full:
	docker compose --profile full up -d

# Pinpoint APM 포함 전체 스택 (HBase 초기화 2~3분 소요)
up-pinpoint:
	PINPOINT_ENABLE=true docker compose --profile full --profile pinpoint up -d

down:
	docker compose down

logs:
	docker compose logs -f

ps:
	docker compose ps

# MSA 모드 전체 스택 (Kafka + quant-engine + worker-market/event/alert)
# api의 QUANT_ENGINE_URL=http://quant-engine:8082 로 quant 요청이 위임된다.
up-msa:
	QUANT_ENGINE_URL=http://quant-engine:8082 docker compose --profile msa up -d

quant-build:
	cd backend/quant-engine && ./gradlew bootJar

quant-run:
	cd backend/quant-engine && ./gradlew bootRun

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

# ── Kubernetes ────────────────────────────────────────────────

k8s-build:
	docker build -t monticker/api:dev       ./backend/api
	docker build -t monticker/worker:dev    ./backend/worker
	docker build -t monticker/web:dev       ./apps/web
	docker build -t monticker/market-gateway:dev ./services/market-gateway

k8s-dev:
	kubectl apply -k infra/k8s/overlays/dev

k8s-prod:
	kubectl apply -k infra/k8s/overlays/prod

k8s-down:
	kubectl delete namespace monticker

k8s-status:
	kubectl get pods,svc,ingress -n monticker

# ── Observability ──────────────────────────────────────────────
# Prometheus http://localhost:9090  Grafana http://localhost:3001 (admin / monticker)

monitoring-up:
	docker compose up -d prometheus grafana

monitoring-down:
	docker compose stop prometheus grafana

monitoring-status:
	@echo "--- Prometheus ---"
	@curl -s http://localhost:9091/-/healthy || echo "DOWN"
	@echo "\n--- Grafana ---"
	@curl -s http://localhost:3001/api/health | python3 -m json.tool 2>/dev/null || echo "DOWN"

# ── Pinpoint APM ───────────────────────────────────────────────
# UI: http://localhost:18080  초기 기동 2~3분 소요 (HBase 스키마 초기화)
# 에이전트 활성화: PINPOINT_ENABLE=true docker compose --profile full --profile pinpoint up

pinpoint-up:
	docker compose --profile pinpoint up -d

pinpoint-down:
	docker compose --profile pinpoint down
