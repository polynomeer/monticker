# DB 백업 CronJob 이미지 (resilience-plan §D7 / P0-5)
# pg_dump/pg_restore/psql(서버와 같은 메이저 16) + aws-cli(오프박스 업로드). infra/db/*.sh를 그대로 싣는다.
#
# 빌드 컨텍스트는 infra/db 다 (루트 .dockerignore가 infra/를 제외한다):
#   docker build -f infra/docker/db-backup/Dockerfile -t monticker/db-backup:<tag> infra/db
FROM timescale/timescaledb:latest-pg16
RUN apk add --no-cache aws-cli bash
COPY backup.sh restore.sh rehearse-restore.sh /opt/db/
RUN chmod +x /opt/db/*.sh
ENTRYPOINT ["/opt/db/backup.sh"]
