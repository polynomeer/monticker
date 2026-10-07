.PHONY: dev dev-kafka dev-msa dev-down dev-status doctor check check-full backfill \
        up up-full up-pinpoint up-msa down logs ps api-test api-run web-install web-dev web-build \
        k8s-build k8s-dev k8s-prod k8s-down k8s-status \
        monitoring-up monitoring-down monitoring-status \
        db-backup db-restore-rehearsal db-backup-image pinpoint-up pinpoint-down

# ── 로컬 개발 (scripts/README.md) ─────────────────────────────
# 인프라 컨테이너 + 호스트의 api·worker·web을 한 번에. Ctrl-C로 전부 정리.

dev:
	scripts/dev/up.sh

dev-kafka:
	scripts/dev/up.sh --kafka

dev-msa:
	scripts/dev/up.sh --msa

dev-down:
	scripts/dev/down.sh

dev-status:
	scripts/dev/status.sh

doctor:
	scripts/dev/doctor.sh

# CI(backend-ci·web-ci)와 같은 검사. check-full은 integrationTest와 web build까지.
check:
	scripts/check.sh

check-full:
	scripts/check.sh --full

backfill:
	python3 scripts/data/backfill-candles.py

# ── docker compose 직접 제어 ──────────────────────────────────

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

# MSA 모드 전체 스택:
#   Kafka + worker-market/event/alert 역할 분리 (quant-engine·trading-service는 ADR-048/049로 폐기)
up-msa:
	docker compose --profile msa up -d

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
# Prometheus http://localhost:9091  Grafana http://localhost:3001 (admin / monticker)

monitoring-up:
	docker compose up -d prometheus grafana

monitoring-down:
	docker compose stop prometheus grafana

monitoring-status:
	@echo "--- Prometheus ---"
	@curl -s http://localhost:9091/-/healthy || echo "DOWN"
	@echo "\n--- Grafana ---"
	@curl -s http://localhost:3001/api/health | python3 -m json.tool 2>/dev/null || echo "DOWN"

# ── DB Backup (resilience-plan P0-5) ───────────────────────────
# 로컬 compose Postgres 대상. 운영은 infra/k8s/base/db-backup.yaml CronJob이 같은 스크립트를 돈다.
# 도구(pg_dump 등)는 호스트에 설치하지 않고 DB와 같은 메이저의 컨테이너 안에서 실행한다.

DB_TOOL_IMAGE ?= timescale/timescaledb:latest-pg16

db-backup:
	docker run --rm --network monticker_default -v "$(PWD)/infra/db:/db" -v "$(PWD)/infra/db/backups:/backups" \
	  -e DB_HOST=postgres -e BACKUP_DIR=/backups $(DB_TOOL_IMAGE) bash /db/backup.sh

# 백업 → 스크래치 DB 복구 → 행 수 대조 → 스크래치 삭제. 원본은 읽기만 한다.
db-restore-rehearsal:
	docker run --rm --network monticker_default -v "$(PWD)/infra/db:/db" \
	  -e DB_HOST=postgres -e BACKUP_DIR=/tmp/rehearsal $(DB_TOOL_IMAGE) bash /db/rehearse-restore.sh

db-backup-image:
	docker build -f infra/docker/db-backup/Dockerfile -t monticker/db-backup:latest infra/db

# ── Pinpoint APM ───────────────────────────────────────────────
# UI: http://localhost:18080  초기 기동 2~3분 소요 (HBase 스키마 초기화)
# 에이전트 활성화: PINPOINT_ENABLE=true docker compose --profile full --profile pinpoint up

pinpoint-up:
	docker compose --profile pinpoint up -d

pinpoint-down:
	docker compose --profile pinpoint down
