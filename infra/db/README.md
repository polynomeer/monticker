# DB 백업 / 복구

## 현재 구현: 논리 백업 (pg_dump/pg_restore)

- `backup.sh` — `pg_dump -Fc`(custom format, 압축+병렬 복구 가능)로 전체 DB를 백업하고, `RETENTION_DAYS`(기본 14일)보다 오래된 백업을 정리한다.
- `restore.sh` — 백업 파일을 지정한 DB로 복구한다(`--clean --if-exists`로 기존 객체를 지우고 덮어씀).

### 실제로 검증했다 (2026-09-05, 로컬 dev DB 대상)

1. 백업 전 `users` 10건, `stocks` 202건 확인
2. `pg_dump`로 백업(5.7MB)
3. 카나리아 행 삽입(`users` 11건으로 증가) — "백업 이후에 발생한 데이터"를 흉내냄
4. 백업으로 복구
5. **검증**: `users` 10건으로 정확히 복귀, 카나리아 행 0건(사라짐), `stocks` 202건 그대로 — 복구가 정확히 백업 시점 상태로 되돌렸음을 확인

이 저장소가 TimescaleDB를 쓰기 때문에 `pg_dump`가 `continuous_agg` 카탈로그 테이블에 대해
"circular foreign-key constraints... --disable-triggers 필요할 수 있음" 경고를 출력한다 — 이
경고는 `--data-only` 덤프에만 해당하는 것으로 보인다. 이 스크립트는 스키마+데이터 전체를
덤프하므로(비-data-only) 위 리허설에서 실제로는 아무 문제 없이 복구됐다. 그래도 복구 시
에러가 나면 이 경고를 먼저 의심할 것.

### 사용법

```bash
# 백업 (기본: localhost:5432, DB monticker)
DB_HOST=localhost DB_PORT=5432 ./backup.sh

# 복구
./restore.sh backups/monticker-20260905T142700Z.dump
```

프로덕션에서는 `backup.sh`를 매일 크론(K8s CronJob)으로 실행하고, 백업 파일을 로컬
디스크가 아니라 S3/GCS 등 별도 오브젝트 스토리지에 업로드하도록 확장해야 한다(현재는
로컬 `backups/` 디렉터리에만 저장 — 단일 서버 장애 시 백업까지 함께 유실되는 상태이므로
이 자체가 프로덕션 전환 전 반드시 고쳐야 할 부분이다).

## 아직 없는 것: PITR (Point-In-Time Recovery)

위 논리 백업은 **백업을 찍은 시점으로만** 복구할 수 있다(예: 매일 자정 백업이면 최악의 경우
거의 24시간 치 데이터가 유실될 수 있음). 특정 시각(예: "사고 발생 5분 전")으로 정밀하게
복구하려면 WAL(Write-Ahead Log) 아카이빙 기반 PITR이 필요하다:

```
postgresql.conf:
  archive_mode = on
  archive_command = 'cp %p /path/to/wal-archive/%f'   # 실제로는 S3 업로드 등으로 교체

주기적으로 pg_basebackup으로 베이스 백업을 뜨고,
복구 시 베이스 백업 + 그 이후 WAL을 순서대로 재생해 원하는 시점까지 복구한다.
```

이건 로컬 dev 환경의 docker-compose Postgres가 WAL 아카이빙을 켜둔 상태가 아니라서
지금 리허설할 수 없다 — 실제 프로덕션 Postgres 인스턴스를 프로비저닝한 뒤에 설정하고
리허설해야 한다. `docs/launch-plan.md` Phase 3에 이 상태를 그대로 기록해 뒀다.
