#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"

# ── cleanup on Ctrl-C ─────────────────────────────────────────
cleanup() {
  echo ""
  echo "Stopping..."
  kill "$API_PID" "$WORKER_PID" "$WEB_PID" 2>/dev/null
  docker compose stop
  wait 2>/dev/null
  echo "Done."
  exit 0
}
trap cleanup INT TERM

# ── ensure Docker Desktop is running ─────────────────────────
if ! docker info > /dev/null 2>&1; then
  echo "Docker is not running. Starting Docker Desktop..."
  open -a Docker
  echo "  Waiting for Docker to start (up to 60s)..."
  for i in $(seq 1 60); do
    sleep 1
    if docker info > /dev/null 2>&1; then
      echo "  Docker ready after ${i}s"
      break
    fi
    if [ "$i" -eq 60 ]; then
      echo "  Docker did not start in time. Please start Docker Desktop manually."
      exit 1
    fi
  done
fi

# ── kill any leftover processes on our ports ──────────────────
echo "Clearing ports 3000 and 8080..."
lsof -ti :3000 | xargs kill -9 2>/dev/null || true
lsof -ti :8080 | xargs kill -9 2>/dev/null || true
sleep 1

# ── 1. infra ──────────────────────────────────────────────────
echo "Starting infra (postgres + redis)..."
docker compose up -d postgres redis

echo "Waiting for postgres to be healthy..."
until docker compose exec postgres pg_isready -U monticker -q 2>/dev/null; do
  sleep 1
done
echo "  postgres OK"

# ── 2. api ────────────────────────────────────────────────────
echo "Starting API (port 8080)..."
cd "$ROOT/backend/api"
./gradlew bootRun --console=plain -q > "$ROOT/logs/api.log" 2>&1 &
API_PID=$!

echo "  Waiting for API..."
until /usr/bin/curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1 || \
      grep -q "Started ApiApplication" "$ROOT/logs/api.log" 2>/dev/null; do
  sleep 2
done
echo "  API OK  (log: logs/api.log)"

# ── 3. worker ─────────────────────────────────────────────────
echo "Starting Worker..."
cd "$ROOT/backend/worker"
./gradlew bootRun --console=plain -q > "$ROOT/logs/worker.log" 2>&1 &
WORKER_PID=$!
sleep 4
echo "  Worker OK  (log: logs/worker.log)"

# ── 4. web ────────────────────────────────────────────────────
echo "Starting Web (port 3000)..."
cd "$ROOT/apps/web"
pnpm install --ignore-scripts --frozen-lockfile 2>/dev/null || true
pnpm dev > "$ROOT/logs/web.log" 2>&1 &
WEB_PID=$!

echo "  Waiting for Web..."
for _ in $(seq 1 30); do
  sleep 2
  if /usr/bin/curl -sf http://localhost:3000 > /dev/null 2>&1; then
    break
  fi
  # bail early if the process died
  if ! kill -0 "$WEB_PID" 2>/dev/null; then
    echo "  Web process died. Check logs/web.log"
    cat "$ROOT/logs/web.log" | tail -20
    exit 1
  fi
done
echo "  Web OK  (log: logs/web.log)"

# ── ready ─────────────────────────────────────────────────────
echo ""
echo "=============================="
echo "  monticker is running"
echo "  Web  → http://localhost:3000"
echo "  API  → http://localhost:8080"
echo "=============================="
echo "Press Ctrl-C to stop all."

wait
