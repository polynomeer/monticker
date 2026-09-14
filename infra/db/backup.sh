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

# 덤프가 실제로 복구 가능한 아카이브인지 확인한다 — pg_restore --list는 서버 없이 TOC만 읽는다.
# 디스크 풀 등으로 잘린 파일이 "성공"으로 남는 것을 막는다 (resilience-plan §D7).
pg_restore --list "$OUT_FILE" > /dev/null
echo "[backup] 아카이브 무결성 확인"

# 오프박스 업로드 (resilience-plan P0-5): BACKUP_S3_URL이 설정돼 있으면 S3 호환 스토리지로 올린다.
# 로컬 디스크에만 있는 백업은 서버 장애 시 함께 사라진다 — 운영에서는 이 변수가 필수다.
# 예: BACKUP_S3_URL=s3://monticker-backups/db  (AWS S3, MinIO, Cloudflare R2 등 aws-cli 호환 대상)
if [ -n "${BACKUP_S3_URL:-}" ]; then
  command -v aws >/dev/null || { echo "[backup] aws CLI 없음 — 업로드 불가" >&2; exit 2; }
  aws s3 cp "$OUT_FILE" "${BACKUP_S3_URL%/}/$(basename "$OUT_FILE")" ${AWS_ENDPOINT_URL:+--endpoint-url "$AWS_ENDPOINT_URL"}
  echo "[backup] 업로드 완료: ${BACKUP_S3_URL%/}/$(basename "$OUT_FILE")"
fi

# 보관 정책: RETENTION_DAYS보다 오래된 로컬 백업 삭제 (오브젝트 스토리지 쪽 보관은 버킷 라이프사이클로)
find "$BACKUP_DIR" -name "monticker-*.dump" -mtime "+${RETENTION_DAYS}" -print -delete
