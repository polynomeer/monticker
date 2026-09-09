#!/usr/bin/env bash
# PITR 복구 준비. 베이스 백업(pitr-basebackup.sh 결과물)을 새 데이터 디렉터리로 복사하고
# recovery.signal + restore_command/recovery_target_time을 심어둔다.
#
# 이 스크립트는 Postgres를 직접 기동하지 않는다 — 배포 환경(Docker/K8s/systemd 등)마다
# Postgres를 띄우는 방식이 다르므로, 이 스크립트로 데이터 디렉터리를 준비한 뒤
# 평소 쓰는 방식으로 그 디렉터리를 가리켜 Postgres를 기동하면 된다. 기동 시 자동으로
# WAL 아카이브에서 목표 시각까지 재생한 뒤(recovery_target_action=promote) 정상 서비스로 전환된다.
#
# 사용법: ./pitr-restore.sh <basebackup-dir> <wal-archive-dir> <target-time> <new-data-dir>
#   <target-time> 형식: 'YYYY-MM-DD HH:MI:SS+00' (SELECT now();로 얻은 값 그대로 사용 가능)
set -euo pipefail

BASEBACKUP_DIR="${1:?사용법: $0 <basebackup-dir> <wal-archive-dir> <target-time> <new-data-dir>}"
WAL_ARCHIVE_DIR="${2:?}"
TARGET_TIME="${3:?}"
NEW_DATA_DIR="${4:?}"

[ -d "$BASEBACKUP_DIR" ] || { echo "[pitr-restore] 베이스 백업 없음: $BASEBACKUP_DIR" >&2; exit 1; }
[ -e "$NEW_DATA_DIR" ] && { echo "[pitr-restore] 이미 존재함: $NEW_DATA_DIR" >&2; exit 1; }

echo "[pitr-restore] ${BASEBACKUP_DIR} → ${NEW_DATA_DIR} (목표 시각: ${TARGET_TIME})"
cp -a "$BASEBACKUP_DIR" "$NEW_DATA_DIR"
chmod 700 "$NEW_DATA_DIR"

touch "$NEW_DATA_DIR/recovery.signal"
cat >> "$NEW_DATA_DIR/postgresql.auto.conf" <<EOF
restore_command = 'cp ${WAL_ARCHIVE_DIR}/%f %p'
recovery_target_time = '${TARGET_TIME}'
recovery_target_action = 'promote'
EOF

echo "[pitr-restore] 준비 완료. Postgres가 postgres 시스템 유저로 이 디렉터리를 소유해야 한다"
echo "[pitr-restore] (예: chown -R postgres:postgres ${NEW_DATA_DIR}) — 그 다음 이 디렉터리를"
echo "[pitr-restore] 데이터 디렉터리로 지정해 평소 방식대로 Postgres를 기동하면 목표 시각까지"
echo "[pitr-restore] 자동으로 WAL을 재생한 뒤 정상 서비스로 전환된다."
