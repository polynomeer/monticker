#!/usr/bin/env bash
# 논리 백업(pg_dump custom format) — 매일 크론으로 실행하는 것을 전제로 한다.
# 실제 운영에서는 이 백업만으로는 부족하다 — README.md의 PITR(WAL 아카이빙) 섹션 참고.
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-monticker}"
DB_NAME="${DB_NAME:-monticker}"
BACKUP_DIR="${BACKUP_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

mkdir -p "$BACKUP_DIR"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_FILE="$BACKUP_DIR/monticker-${TIMESTAMP}.dump"

echo "[backup] ${DB_NAME}@${DB_HOST}:${DB_PORT} → ${OUT_FILE}"
PGPASSWORD="${PGPASSWORD:-monticker}" pg_dump \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -Fc --no-owner --no-privileges \
  -f "$OUT_FILE"

echo "[backup] 완료: $(du -h "$OUT_FILE" | cut -f1)"

# 보관 정책: RETENTION_DAYS보다 오래된 백업 삭제
find "$BACKUP_DIR" -name "monticker-*.dump" -mtime "+${RETENTION_DAYS}" -print -delete
