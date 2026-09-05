#!/usr/bin/env bash
# 논리 백업 복구. 기본 동작은 기존 DB의 객체를 지우고(--clean --if-exists) 백업 내용으로 덮어쓴다.
# 사용법: ./restore.sh <backup-file> [target-db-name]
set -euo pipefail

BACKUP_FILE="${1:?사용법: $0 <backup-file> [target-db-name]}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-monticker}"
DB_NAME="${2:-${DB_NAME:-monticker}}"

[ -f "$BACKUP_FILE" ] || { echo "[restore] 파일 없음: $BACKUP_FILE" >&2; exit 1; }

echo "[restore] ${BACKUP_FILE} → ${DB_NAME}@${DB_HOST}:${DB_PORT}"
PGPASSWORD="${PGPASSWORD:-monticker}" pg_restore \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  --clean --if-exists --no-owner --no-privileges \
  "$BACKUP_FILE"

echo "[restore] 완료"
