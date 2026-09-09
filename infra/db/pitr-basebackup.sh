#!/usr/bin/env bash
# PITR(Point-In-Time Recovery)용 베이스 백업. WAL 아카이빙(archive_mode=on)이 켜진
# Postgres에 대해서만 의미가 있다 — README.md의 "PITR (WAL 아카이빙)" 섹션 참고.
# pg_dump 논리 백업(backup.sh)과 달리 물리 백업이라 pg_basebackup을 쓴다.
set -euo pipefail

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-monticker}"
OUT_DIR="${1:?사용법: $0 <output-dir>}"

[ -e "$OUT_DIR" ] && { echo "[pitr-basebackup] 이미 존재함: $OUT_DIR" >&2; exit 1; }

echo "[pitr-basebackup] ${DB_USER}@${DB_HOST}:${DB_PORT} → ${OUT_DIR}"
PGPASSWORD="${PGPASSWORD:-monticker}" pg_basebackup \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" \
  -D "$OUT_DIR" -Fp -Xs -P

echo "[pitr-basebackup] 완료: $(du -sh "$OUT_DIR" | cut -f1)"
