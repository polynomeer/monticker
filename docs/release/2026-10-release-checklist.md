# 2026-10 릴리스 배포 체크리스트

> 읽을 때: 터미널 UI 전환(PR #97~#100)부터 #188까지를 처음으로 운영에 올릴 때. 위에서 아래로 순서대로 진행하고, 항목마다 담당자·완료 시각을 적는다.
> 대상 커밋: `main` `10594c2d` (2026-10-09) + 알림 운영 스위치(#190). 이후 커밋이 더해지면 §2 마이그레이션 표부터 다시 확인한다.

## 0. 이번 릴리스에서 무엇이 바뀌나 (요약)

- 웹 전 화면이 터미널 UI로 바뀐다(ADR-066). 라이트 테마가 사라지고 다크 고정.
- 동의 기록·재동의 화면, 증권사 연동 해지, '결과 확인 중' 주문 알림(P0).
- 리스크 한도 쿨링오프·근접 경고, 모의 지정가·조건부 주문, Watch Rule 그룹·지정가·계좌 %, 퀀트 시그널 알림, 지갑 행동 지표, KRX 영업일 정산, 차트 그리기 등 P1·P2 기능.
- **알림이 크게 늘어난다** — §6 참고. 사용자 공지 초안은 [2026-10-user-notice-draft.md](2026-10-user-notice-draft.md).

## 1. 배포 전 — 사람이 정해야 할 것 (게이트)

| # | 항목 | 왜 | 근거 |
|---|---|---|---|
| 1-1 | **KRX 휴장일 날짜 대조** — 2026년 7/17(제헌절 재지정), 2027년 6/7(현충일 대체 미포함)·7/19(제헌절 대체 포함). 틀리면 V83 후속 마이그레이션으로 고친다 | 정산일(T+2)·장 상태·배치 실행일이 이 표를 따른다 | ADR-086, `V83__market_holidays.sql` |
| 1-2 | **사용자 공지 확정·게시 시점** — 알림 증가, 동작 변경 | 배포 직후 알림이 갑자기 늘어난다 | §6, 공지 초안 |
| 1-3 | `APP_HTTP_TRUSTED_PROXIES`를 **실제 ingress-nginx 파드 CIDR**로 좁힌 값 | 지금 값(RFC1918 전체)이면 클러스터 안 아무 파드가 X-Forwarded-For를 위조할 수 있다. **비우면 모든 요청이 ingress IP 하나로 보여 전면 429** | ADR-084, `infra/k8s/base/configmap.yaml` |
| 1-4 | `ANALYTICS_RISK_FREE_RATE` 값(기본 0, −0.05~0.2 밖이면 기동 실패) | 분석 화면 샤프 비율 | ADR-097 |
| 1-5 | 실주문 리스크 게이트 추정가에 시세 신선도 검사를 넣을지 | 지금은 오래된 시세로도 추정한다(실주문 동작 변경이라 미적용) | #128 PR 본문 |
| 1-6 | **`UNSUBSCRIBE_TOKEN_SECRET`을 운영 시크릿 저장소에 등록**(JWT_SECRET과 다른 값) | 없으면 prod api가 기동하지 않는다 | ADR-102, §4 |

## 2. 데이터베이스 마이그레이션 (V57 → V100)

Flyway가 api 기동 시 자동 적용한다. **V89는 비어 있다**(정상). 아래 셋은 주의한다.

| 버전 | 주의 | 대응 |
|---|---|---|
| **V82** | `orders`·`paper_trades` **전체 UPDATE**(진입 출처 백필) | 트래픽이 적은 시간에. 큰 테이블이면 소요 시간을 스테이징에서 먼저 잰다 |
| **V84**, **V96**, **V98** | `CREATE INDEX CONCURRENTLY` — 트랜잭션 밖, 쓰기를 막지 않는다 | **Flyway 세션 lock 설정**(`spring.flyway.postgresql.transactional-lock: false`)이 운영 설정에도 있는지 확인. 없으면 마이그레이션이 영원히 멈춘다. 중간에 실패하면 INVALID 인덱스가 남으므로 `DROP INDEX CONCURRENTLY IF EXISTS <이름>;` 후 `flyway repair` → 재기동 |
| **V99**, **V100** | `DROP INDEX CONCURRENTLY` — paper_trades 중복 인덱스 2개 삭제, 트랜잭션 밖 | 위와 같은 세션 lock 필요. 중간에 실패하면 같은 DROP 문을 다시 실행한 뒤 `flyway repair` |
| **V91** | worker outbox를 `worker_outbox` 스키마로 분리하고 미완료 worker 행을 옮긴다 | §3 배포 순서(api → worker)를 반드시 지킨다 |

- [ ] 운영 DB 백업(스냅샷) — 롤백 시 필요. 마이그레이션은 되돌리는 스크립트가 없다.
- [ ] 스테이징에서 V57~V100 전체 적용 시간 측정(특히 V82).
- [ ] 적용 후 `SELECT version, success FROM flyway_schema_history WHERE version::int >= 57 ORDER BY installed_rank;` 전부 `t`.
- [ ] `SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;` 결과 없음(INVALID 인덱스 없음).

## 3. 배포 순서

1. **api** (마이그레이션 적용) → 정상 기동·헬스 확인
2. **worker** (모든 역할: market / event / alert)
3. **web**

- worker를 먼저 올리면 V91이 없어 기동 가드(`WorkerOutboxSchemaGuard`)가 새 pod를 멈춘다 — 그동안 구버전이 계속 일한다(장애 아님, 순서만 바로잡으면 된다).
- 롤링 배포 중 구버전 worker의 outbox 재전송 한 주기가 새 이벤트 클래스를 몰라 실패할 수 있다 — 배포가 끝나면 사라진다(ADR-094, ADR-100).
- `notify.user` 메시지 형식은 이번 릴리스에서 하위 호환(새 필드는 무시 가능).

## 4. 설정·시크릿

| 키 | 값 | 비고 |
|---|---|---|
| `APP_HTTP_TRUSTED_PROXIES` | 1-3에서 정한 CIDR | **비우지 말 것**. 오버레이가 ConfigMap을 덮어쓸 때 빠지지 않는지 확인 |
| `ANALYTICS_RISK_FREE_RATE` | 1-4 | |
| **`UNSUBSCRIBE_TOKEN_SECRET`** (시크릿) | `openssl rand -base64 48`, **JWT_SECRET과 다른 값** | **필수 — 없으면 prod api가 기동하지 않는다**(ADR-102). 교체하면 이미 보낸 메일의 수신 거부 링크가 모두 무효 |
| `APP_BASE_URL` | `https://monticker.io` | 주간 리포트·인증 메일의 링크, 수신 거부 링크(`/unsubscribe`, `/api/unsubscribe`)도 이 주소를 쓴다 |
| `MAIL_FROM` | 발신 주소 | SPF/DKIM이 이 도메인으로 설정됐는지 |
| `WEEKLY_REPORT_ENABLED` | `true` (첫 월요일을 피하려면 `false`로 배포 후 켠다) | api. 주간 리포트 이메일 |
| `WATCHLIST_EVENT_PUSH_ENABLED` | `true` (단계적으로 내보내려면 `false`로 배포 후 켠다) | worker. 관심종목 급등·급락·거래량 급증 푸시만 멈춘다(이벤트 기록·색인은 계속) |
| `QUANT_SIGNAL_PUSH_ENABLED` | `true` | api. 퀀트 시그널 푸시·메일만 멈춘다(알림함 이력은 계속) |
| `NEWS_ALERT_ENABLED` | `true` | worker. 뉴스·공시 알림을 멈춘다(이력 행도 만들지 않고 outbox에 남은 발송도 버린다) |
| `WEEKLY_REPORT_CRON` 등 | 기본값(`0 0/30 8-20 * * MON`, 2000명/회, 20분) | ADR-101 |
| `market-calendar.refresh-ms` | 기본 1시간 | ADR-086 |
| GitHub 저장소 시크릿 `ANTHROPIC_API_KEY` | 선택 | PR 자동 리뷰. 없으면 리뷰를 건너뛸 뿐 |

## 5. 모니터링·경보

- [ ] `infra/monitoring/alert-rules.yml`(36 rules)과 Grafana 대시보드가 운영 Prometheus·Grafana에 반영됐는지.
- [ ] 새 경보가 Alertmanager 라우팅에 연결됐는지: `MarketCalendarNextYearMissing`, `MarketCalendarCurrentYearMissing`, `MarketCalendarUncoveredLookup`, `WorkerOutboxBacklog`, `OutboxBacklog`(api 한정으로 바뀜).
- [ ] 배포 직후 대시보드에서: Outbox 미완료·최고령(api·worker), 알림 발송량, 429 비율, `weekly_behavior_report_total`(월요일).
- 런북: [runbooks/README.md](../runbooks/README.md) — outbox 적체, KRX 캘린더 항목이 새로 생겼다.

## 6. ⚠️ 알림 증가 — 배포 직후 지켜볼 것

다음이 **동시에** 시작된다. 모두 사용자 알림 설정과 방해 금지 시간을 따른다.

| 알림 | 왜 늘어나나 | 기본값 | 끄는 방법(운영) |
|---|---|---|---|
| 관심종목 급등·급락·거래량 급증 푸시 | **지금까지 SQL 오류로 한 번도 발송되지 않았다**(#188에서 수정) — 배포하면 처음으로 나간다 | 가격·거래량 알림 켬 | `WATCHLIST_EVENT_PUSH_ENABLED=false` (worker 재기동) |
| 관심종목 뉴스·공시 알림 | 새 기능(#183) | 켬(V78 기본값) | `NEWS_ALERT_ENABLED=false` (worker 재기동) |
| 퀀트 시그널 푸시 | 구독자도 받게 됨(#167, 이전엔 전략 주인만) | 켬 | `QUANT_SIGNAL_PUSH_ENABLED=false` (api 재기동, 알림함 이력은 계속 남음) |
| 주간 행동 리포트 이메일 | 새 기능(#184), 매주 월요일 | 켬 | `WEEKLY_REPORT_ENABLED=false` |

- 상한: 뉴스·공시는 사용자당 1시간 5건(넘으면 알림 없이 이력만), 공시는 중요도 70 이상, 뉴스는 발행 6시간 이내.
- 운영 스위치는 환경변수라 바꾸면 **해당 서비스(api 또는 worker)를 재기동**해야 적용된다. 꺼 둔 동안의 이벤트·기사는 다시 켜도 소급 발송하지 않는다. 뉴스 스위치 동작은 `news_alert_skipped_total{reason="disabled"}`로 확인한다.
- [ ] 배포 후 1시간: 푸시 발송 수, 푸시 제공자 오류율, 사용자 알림 끔 비율(급증하면 공지 보강).

## 7. 배포 후 확인 (스모크)

- [ ] 로그인 → 동의 화면이 필요한 사용자에게만 뜨는지(이 릴리스 전 가입자는 한 번 거친다 — 의도).
- [ ] 홈·스크리너·종목·지갑·리스크·퀀트랩·실전투자 화면이 열리고 콘솔/서버 5xx가 없는지.
- [ ] `/api/market/status`가 오늘 휴장 여부를 맞게 주는지.
- [ ] 모의 지정가 접수 → (장중) 체결 → 영수증 4단계(접수·예약금 잠금·체결·정산). **장중에만 확인 가능**(시세가 5분 넘게 멈추면 설계대로 체결하지 않는다).
- [ ] 관심종목 종목 하나에 이벤트가 생겼을 때 테스트 계정으로 푸시가 오는지(지금까지 한 번도 확인된 적 없는 경로).
- [ ] 첫 월요일 08:00 이후 `weekly_behavior_report_total{outcome="sent"}` 증가, 테스트 계정 메일 수신·링크.

## 8. 롤백

- 앱: 이전 이미지로 되돌린다([runbooks/deploy-rollback.md](../runbooks/deploy-rollback.md)).
- **스키마는 되돌리지 않는다** — V57 이후 마이그레이션은 컬럼·테이블 추가 위주라 이전 버전 앱이 새 스키마에서도 돈다. 예외:
  - **worker만 되돌리지 말 것.** 이전 worker는 outbox를 다시 `public.event_publication`에 쓰는데, 새 worker의 이관(`LegacyOutboxDrain`)이 없으니 1분 넘게 미완료로 남은 worker 행 때문에 **새 api의 재전송이 예전처럼 매번 실패**한다(ADR-094 이전 결함). 되돌릴 때는 **api와 worker를 함께** 되돌린다.
  - V82 백필은 되돌릴 필요 없다(이전 앱은 새 컬럼을 읽지 않는다).

## 9. 배포 후 남은 일

- [ ] 스크린샷 다시 찍기 — [manual/README.md](../manual/README.md) 목록.
- [ ] 후속 마이그레이션: 중복 인덱스 `idx_paper_trades_user`, `idx_paper_trades_stock` 삭제 — V99·V100(각각 단일 문장 `DROP INDEX CONCURRENTLY`, 근거는 [data-model.md](../data-model.md)). 배포 후 두 인덱스가 사라졌는지 확인.
- [ ] Next 16.4·ESLint 9 업그레이드([#160](https://github.com/polynomeer/monticker/pull/160), 트랙 W1) — **이번 릴리스에서 뺐다**(웹 전체 영향). 배포가 안정된 뒤 rebase → web tsc·vitest(KST·UTC)·lint·build·e2e → 로컬 스택에서 전 화면 점검 후 머지.
- [ ] 주간 리포트 첫 월요일: 실제 발송(`weekly_behavior_report_total`)과 원클릭 수신 거부(`email_unsubscribe_total`, ADR-102) 동작 확인.
- [ ] `LegacyOutboxDrain` 제거 — 모든 환경에서 구버전 worker가 내려간 뒤 한 릴리스(ADR-094).
- [ ] 운영 `candles_1d`의 하루 2행 중복 여부 확인.
- [ ] 연말(11월) 전에 다음 해 KRX 휴장일 마이그레이션 — `MarketCalendarNextYearMissing` 경보가 알려 준다.
