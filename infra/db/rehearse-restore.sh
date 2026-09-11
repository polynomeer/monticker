#!/usr/bin/env bash
# 복원 리허설 (resilience-plan §D7 / P0-5) — "백업이 있다"와 "복구가 된다"는 다른 말이다.
#
# 순서:
#   1. 운영 DB를 pg_dump로 백업한다
#   2. 별도 스크래치 DB(monticker_restore_check)를 만들어 그 백업을 복구한다
#   3. 핵심 테이블의 행 수를 원본과 대조한다
#   4. 스크래치 DB를 지운다
#
# 원본 DB는 읽기만 한다. 실패하면 0이 아닌 코드로 끝나므로 크론/CI에 그대로 걸 수 있다.
# 마지막 성공 시각을 어딘가에 남겨 두면(예: 메트릭) "3개월간 리허설 안 함"을 감지할 수 있다.
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-monticker}"
DB_NAME="${DB_NAME:-monticker}"
SCRATCH_DB="${SCRATCH_DB:-monticker_restore_check}"
export PGPASSWORD="${PGPASSWORD:-monticker}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$HERE/backups/rehearsal}"

# 행 수를 대조할 테이블 — 실제 돈/사용자 데이터가 있는 곳 위주
TABLES="${TABLES:-users stocks paper_accounts paper_trades ledger_events orders fills alert_rules candles_1m}"

psql_() { psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$1" -v ON_ERROR_STOP=1 -tA "${@:2}"; }

count_rows() {  # $1 = db
  local db="$1" out=""
  for t in $TABLES; do
    if psql_ "$db" -c "SELECT 1 FROM information_schema.tables WHERE table_name='$t'" | grep -q 1; then
      out+="$t=$(psql_ "$db" -c "SELECT count(*) FROM $t") "
    else
      out+="$t=ABSENT "
    fi
  done
  echo "$out"
}

cleanup() {
  psql_ postgres -c "DROP DATABASE IF EXISTS $SCRATCH_DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "[rehearsal] 1/4 원본 행 수 스냅샷"
BEFORE="$(count_rows "$DB_NAME")"
echo "           $BEFORE"

echo "[rehearsal] 2/4 백업"
BACKUP_DIR="$BACKUP_DIR" RETENTION_DAYS=1 "$HERE/backup.sh" | sed 's/^/           /'
DUMP="$(ls -t "$BACKUP_DIR"/monticker-*.dump | head -1)"

echo "[rehearsal] 3/4 스크래치 DB($SCRATCH_DB)로 복구"
cleanup
psql_ postgres -c "CREATE DATABASE $SCRATCH_DB" >/dev/null
# TimescaleDB 확장이 있는 원본을 복구하려면 대상에도 확장이 먼저 있어야 한다.
psql_ "$SCRATCH_DB" -c "CREATE EXTENSION IF NOT EXISTS timescaledb" >/dev/null 2>&1 || true
# pg_restore는 존재하지 않는 확장 객체 등으로 경고를 내도 계속 진행한다 — 행 수 대조가 최종 판정이다.
"$HERE/restore.sh" "$DUMP" "$SCRATCH_DB" 2>&1 | grep -vE "^pg_restore: (warning|error): (could not execute query: ERROR:  extension|.*already exists)" | sed 's/^/           /' || true

echo "[rehearsal] 4/4 대조"
AFTER="$(count_rows "$SCRATCH_DB")"
echo "           $AFTER"

if [ "$BEFORE" = "$AFTER" ]; then
  echo "[rehearsal] PASS — 복구된 DB의 행 수가 원본과 일치한다 ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
else
  echo "[rehearsal] FAIL — 행 수 불일치" >&2
  echo "  원본: $BEFORE" >&2
  echo "  복구: $AFTER" >&2
  exit 1
fi
