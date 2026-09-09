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

## PITR (Point-In-Time Recovery)

위 논리 백업은 **백업을 찍은 시점으로만** 복구할 수 있다(예: 매일 자정 백업이면 최악의 경우
거의 24시간 치 데이터가 유실될 수 있음). 특정 시각(예: "사고 발생 5분 전")으로 정밀하게
복구하려면 WAL(Write-Ahead Log) 아카이빙 기반 PITR이 필요하다.

### 대상 Postgres에 필요한 설정

```
archive_mode = on
archive_command = 'cp %p /path/to/wal-archive/%f'   # 실제 운영에서는 S3 업로드 등으로 교체
wal_level = replica                                  # pg_basebackup(물리 복제 연결)에 필요
```
+ `pg_hba.conf`에 `host replication <user> <cidr> <auth-method>` 항목 추가(그렇지 않으면
`pg_basebackup`이 "no pg_hba.conf entry for replication connection"으로 거부됨).

### 스크립트

- `pitr-basebackup.sh <output-dir>` — `pg_basebackup`으로 물리 베이스 백업을 뜬다(주기적으로,
  예를 들어 하루 1회 실행하는 것을 전제로 한다).
- `pitr-restore.sh <basebackup-dir> <wal-archive-dir> <target-time> <new-data-dir>` — 베이스
  백업을 새 데이터 디렉터리로 복사하고 `recovery.signal` + `restore_command`/`recovery_target_time`을
  심어둔다. Postgres를 직접 기동하지는 않는다(배포 환경마다 기동 방식이 다르므로) — 이 스크립트가
  준비한 디렉터리를 `postgres` 유저가 소유하게 한 뒤 평소 방식대로 그 디렉터리를 데이터 디렉터리로
  지정해 Postgres를 기동하면, 목표 시각까지 WAL을 자동 재생한 뒤 정상 서비스로 전환된다
  (`recovery_target_action = 'promote'`).

### 실제로 검증했다 (2026-09-09, 로컬 dev와 완전히 격리된 임시 Docker 컨테이너 대상)

로컬 dev의 docker-compose Postgres(`monticker-postgres`)는 WAL 아카이빙이 꺼져 있고, 다른
세션이 동시에 쓰고 있을 수 있어 건드리지 않았다 — 대신 완전히 별도의 임시 컨테이너
(`monticker-pitr-source`)를 띄워 아카이빙을 켜고 리허설했다.

1. 아카이빙 켠 소스에 `baseline-1`, `baseline-2` 삽입
2. `pitr-basebackup.sh`로 베이스 백업
3. `after-basebackup-canary` 삽입(카나리아) → `SELECT now()`로 목표 시각 기록 → 2초 후
   `AFTER-TARGET-should-not-survive-1/2` 삽입("목표 시각 이후에 발생한 데이터"를 흉내냄)
4. `pitr-restore.sh`로 목표 시각(카나리아 이후, should-not-survive 이전)까지 복구 준비
5. 새 데이터 디렉터리로 Postgres 기동
6. **검증**: 로그에 `recovery stopping before commit of transaction ..., time <should-not-survive 시각>`
   / `last completed transaction was at log time <카나리아 시각>` 정확히 기록됨. 실제 쿼리 결과도
   `baseline-1`, `baseline-2`, `after-basebackup-canary` 3건만 남고 `should-not-survive` 2건은
   정확히 사라짐 — 목표 시각 복구가 정밀하게 동작함을 확인.

`docs/launch-plan.md` Phase 3도 "스크립트 미작성"에서 "스크립트 작성+리허설 완료, 실 프로덕션
Postgres 설정만 남음"으로 갱신했다.
