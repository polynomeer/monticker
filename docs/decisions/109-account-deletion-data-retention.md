# ADR-109: 회원 탈퇴 시 데이터 처리 — 비밀은 즉시 삭제, 사용자 행은 익명화, 거래·결제 기록은 5년 보존

## Status
Accepted

## Context

탈퇴(`AuthService.deleteAccount`)는 `users.deleted_at`을 채우는 소프트 삭제다. 사용자 행이 남으니 `REFERENCES users(id) ON DELETE CASCADE`는
한 번도 돌지 않는다. 지금까지 탈퇴가 지운 것은 refresh token뿐이었다. 점검해 보니 이런 것이 남아 있었다.

- **비밀값.** 증권사 앱키·시크릿·토큰(AES-GCM 암호화), 토스 빌링키, 푸시 토큰. ADR-067의 "해지하면 키를 즉시 지운다"는 약속이
  탈퇴 경로에는 적용되지 않았다.
- **돈이 계속 나가는 경로.** 갱신 배치(`findExpiringBeforeAfter`)는 사용자가 탈퇴했는지 보지 않는다. 활성 구독과 빌링키가 남아 있으면
  **탈퇴한 사용자에게 계속 청구한다.**
- **사람을 알아볼 수 있는 값.** `users` 행에 이메일·닉네임·비밀번호 해시·소셜 provider id가 그대로 남았다.
- **떠난 사용자 앞으로 계속 도는 자동화.** Watch Rule(모의 자동주문), 모의 조건부 주문, 알림 규칙.

통합 포트폴리오만 `UserAccountDeletedEvent`(탈퇴 트랜잭션 안에서 동기 발행)를 받아 지우고 있었다(feat/portfolio-web).

모든 데이터를 지우면 안 되는 이유도 있다.
- 결제 기록에는 전자상거래법 제6조(대금결제·재화 공급 기록 5년)와 국세기본법(장부·증빙 5년)의 보존 의무가 걸릴 수 있다.
- 실계좌 주문·정산 기록은 분쟁(체결·수수료·손익) 증빙이다. 우리는 증권사가 아니라 법정 보존 주체인지 확실하지 않다.
- 동의 기록(ADR-068)은 "언제 무엇에 동의했나"의 증빙이라 계정과 함께 사라지면 안 된다.

고려한 대안:
1. **사용자 행을 하드 삭제하고 CASCADE에 맡긴다.** 결제·실거래·동의 테이블은 RESTRICT FK라 삭제가 실패한다. CASCADE로 바꾸면 보존 의무가 있는 기록까지 지워진다.
2. **소프트 삭제를 유지하고 정리 SQL을 `AuthService` 한곳에 모은다.** auth 모듈이 다른 모듈의 테이블을 알게 된다(Modulith 경계 위반). 새 테이블이 생겨도 auth를 고칠 생각을 하기 어렵다.
3. **소프트 삭제 + 사용자 행 익명화 + 모듈마다 탈퇴 이벤트 리스너** — 채택.

## Decision

### 구조

- `AuthService.deleteAccount`는 한 트랜잭션에서 아래 순서로 처리한다.
  1. 비밀번호를 확인한다.
  2. `UserAccountDeletedEvent`를 **먼저** 발행한다.
  3. auth가 가진 데이터를 지운다.
  4. `User.anonymize()`로 사용자 행을 익명화한다.
- 각 모듈은 자기 테이블만 지우는 `*UserDeletionListener`(`@EventListener`, 동기)를 둔다. `common`은 OPEN이라 모든 모듈이 이 이벤트를 받을 수 있다.
  `device`는 의존성이 `{}`였다. 이번에 `{"common"}`을 허용했다.
- 리스너가 던지면 탈퇴 전체가 롤백된다. 이벤트를 먼저 발행하는 것도 그래서다. **거부는 이것으로 구현한다.**
- 검색 색인(ES) 문서도 같은 트랜잭션에서 `SearchIndexEvent.delete`로 지운다(ADR-042). 롤백되면 이 삭제 이벤트도 함께 사라진다.

### 사용자 행(`users`)

| 컬럼 | 처리 |
|---|---|
| email | `deleted-{id}@deleted.invalid`로 바꾼다. UNIQUE 제약을 지키면서 같은 이메일로 바로 다시 가입할 수 있다 |
| nickname | `탈퇴한 사용자`. 커뮤니티 댓글 작성자 표시가 이것으로 바뀐다 |
| password_hash, provider_id | NULL. 같은 소셜 계정으로 로그인하면 새 계정이 된다 |
| email_verified | false |
| 행 자체 | 남긴다. 결제·실거래·동의 기록이 이 id를 참조한다 |

### 데이터 종류별 처리

**즉시 삭제: 비밀값과 돈이 나가는 경로**

| 테이블 | 모듈 | 처리 |
|---|---|---|
| refresh_tokens | auth | 삭제(기존) |
| brokerage_accounts 키 컬럼 | brokerage | 계좌마다 `disconnect()`. 갈아타기로 비활성화됐지만 키가 남은 계좌도 포함한다(ADR-067 Note). 계좌 행은 남긴다 |
| conditional_orders(ACTIVE) | brokerage | CANCELLED |
| user_billing_keys | subscription | 삭제 |
| user_subscriptions(ACTIVE) | subscription | CANCELLED. 남은 기간은 환불하지 않는다. 행은 이력으로 남긴다 |
| device_tokens | device | 삭제 |

**탈퇴 거부.** 결과가 열린 실주문이 하나라도 있으면 탈퇴를 거부한다. 계좌가 비활성이어도 마찬가지다. ADR-067의 정의를 쓴다:
`PENDING_SUBMIT`, `UNKNOWN`, 최근 24시간의 `SUBMITTED`/`PARTIALLY_FILLED`, 발동 중인 조건부 주문. 409를 돌려준다.
그 주문을 대조하려면 키가 필요하다. 키를 지운 뒤 체결되면 정산과 원장이 영영 맞지 않는다. 락은 해지와 같다(사용자 advisory lock, 계좌 행 `FOR UPDATE`).

**즉시 삭제: 개인 설정과 개인이 만든 데이터**

| 테이블 | 모듈 |
|---|---|
| notification_preferences, user_preferences | auth |
| watchlist_groups, watchlist_items(CASCADE), ES `watchlist_items` | watchlist |
| alert_rules, alert_histories(규칙 경유 + 직접 주인), ES `alert_histories` | alert |
| saved_screens | screener |
| risk_limits, risk_limit_pending_changes, risk_limit_warnings | risk |
| watch_rules, watch_rule_executions·watch_rule_daily_counts(CASCADE) | watchrule |
| portfolio_accounts·transactions·import_batches | portfolio(기존) |
| paper_conditional_orders(WAITING_PARENT·ACTIVE) | paper. 지우지 않고 CANCELLED로 바꾼다. 아래 후속 참고 |

**보존 5년, 사용자 행 익명화로 직접 식별 불가**

법률 자문 전까지는 보수적으로 보존한다. 자문 결과는 [legal-review-brief.md §2-14](../legal-review-brief.md)에서 받는다.

| 테이블 | 근거 |
|---|---|
| payment_records, user_subscriptions(이력) | 전자상거래법 제6조, 국세기본법 |
| creator_earnings, creator_payouts | 같은 근거. 정산 상대방의 기록이기도 하다 |
| brokerage_accounts(키를 지운 행), brokerage_orders, brokerage_settlements, conditional_orders, rebalance_targets·executions·legs | 실거래 분쟁 증빙. 법정 의무는 자문 대기(§2-14). rebalance_targets는 키가 없어 실행될 수 없다 |
| risk_check_logs | 실주문을 어떤 한도로 통과·거부했는지의 기록. 실주문과 같이 보존한다 |
| user_consents | 동의 증빙(ADR-068). 추가 전용 원장이다 |
| trading_halts(halted_by·lifted_by) | 운영자 감사 기록이다. 사용자 데이터가 아니다 |

**남기되 익명화(작성자 표시만 바뀜)**

| 테이블 | 처리 |
|---|---|
| stock_comments | 내용은 남긴다. 작성자는 익명화된 닉네임(`탈퇴한 사용자`)으로 보인다. 지우면 답글 흐름이 끊긴다 |
| stock_comment_reports | 남긴다. 운영 처리 기록이다 |

**결정했지만 미구현: 후속**

| 테이블 | 결정 | 미구현 이유 |
|---|---|---|
| paper_accounts, paper_trades, paper_settlements, orders, fills, order_sagas, portfolio_positions, ledger_events, ledger_snapshots, order_emotion_tags, investment_behavior_scores, weekly_report_sends | 삭제(가상 자금, 보존 의무 없음) | 모듈 셋(wallet → paper → matching)에 FK로 엮여 있다. 매칭 엔진은 메모리 호가창에 대기 주문을 들고 있어서, 행만 지우면 늦게 온 체결이 FK에 걸린다. 엔진에서 먼저 취소하는 순서 설계가 필요하다 |
| rule_sets(+Mongo), quant_backtest_results, quant_signals, quant_forward_tests, strategy_subscriptions, tax_harvesting_logs, portfolio_optimizations, order_proposals | 삭제 | 크리에이터가 탈퇴하면 마켓 등록 전략(strategy_market)을 내려야 하는데, 구독자·creator_earnings(보존 대상)가 그 행을 참조한다. 판매 중지 처리를 따로 정해야 한다 |
| strategy_market | 판매 중지, 행은 보존 | 위와 같다 |

## Reasons

- **모듈마다 리스너를 둔다.** 데이터를 가진 모듈이 자기 테이블을 지운다. auth는 다른 모듈의 테이블을 모른다. 새 사용자 테이블을 만드는 PR은 같은 모듈에 리스너를 더하면 된다.
- **동기·같은 트랜잭션이다.** "탈퇴는 됐는데 키가 남았다"는 상태가 생기지 않는다. 비동기(커밋 뒤)로 처리하면 그 사이에 갱신 배치가 청구하거나 대조 잡이 키를 쓸 수 있다. 실패를 재시도할 장치(event_publication)도 새로 필요해진다.
- **익명화하고 하드 삭제는 하지 않는다.** 보존 대상 기록이 사용자 id를 FK로 잡고 있다. 행을 지우지 않아도 이메일·이름·인증 수단이 없으면 직접 식별할 수 없다. 같은 이메일로 다시 가입하는 것도 막히지 않는다.
- **열린 실주문이 있으면 거부한다.** 해지(ADR-067)와 같은 이유다. 탈퇴하려는 사용자는 1~2분(대조 주기) 기다리면 된다.

## Consequences

- 탈퇴 트랜잭션이 길어진다. 모듈 열 개가 사용자 단위로 DELETE를 한다. 사용자 한 명 분량이라 작지만, 알림 이력이 많은 사용자는 ES 삭제 이벤트가 그만큼 쌓인다.
- 리스너 실행 순서는 정하지 않았다. 지금은 모듈 사이에 FK가 엮인 테이블을 지우지 않아 순서가 상관없다. 모의투자 데이터 삭제(후속)는 순서가 필요하다(`@Order` 또는 한 모듈로 모으기).
- **갱신 배치와 탈퇴의 경쟁이 남아 있다.** 배치가 빌링키를 읽은 뒤 탈퇴가 커밋되면 그 한 번은 청구될 수 있다. 갱신 쪽에서 구독 행을 `FOR UPDATE`로 다시 읽고 ACTIVE인지 확인해야 닫힌다(후속 → 닫힘, 아래 Notes).
- 토스 빌링키는 우리 DB에서만 지운다. PG 쪽 빌링키 폐기 API는 호출하지 않는다(ADR-067의 증권사 토큰 폐기와 같은 입장).
- 5년 보존 기록을 실제로 파기하는 잡이 없다. 보존 기간이 확정되면 만든다(후속).
- 탈퇴 전에 쓴 이메일(인증·비밀번호 재설정 링크)은 Redis TTL(최대 24시간)이 지나면 무효가 된다. 사용자 행이 탈퇴 상태라 그 전에도 쓸 수 없다.
- 개인정보처리방침의 "탈퇴 시 처리" 문구가 이 표와 맞아야 한다(웹 `/privacy`). 문구를 고치는 것은 별도 PR이다.

## Revisit When

- 법률 자문(§2-14)에서 보존 대상이나 보존 기간이 달라질 때.
- 모의투자·퀀트 데이터 삭제를 구현할 때. 위 "후속" 표를 이 ADR의 Note로 갱신한다.
- 재가입 악용(무료 체험 반복 등)이 실제로 생길 때. 이메일 해시를 일정 기간 보관하는 방안을 검토한다(처리방침 개정이 먼저다).
- 사용자 데이터를 가진 테이블이 새로 생길 때. 이 표에 행을 더한다.

## Notes

- 2026-10 — 갱신 배치와 탈퇴의 경쟁을 닫았다. `renewSubscription`이 시작하자마자 구독 행을 `PESSIMISTIC_WRITE`로 다시 읽고
  (`UserSubscriptionRepository.findByIdForUpdate`, 청크 트랜잭션에 이미 올라 있는 엔티티라 `EntityManager.refresh`로 상태도 다시 읽는다)
  ACTIVE가 아니면 빌링키를 읽기 전에 `Skipped`로 끝낸다. `closeForAccountDeletion`과 `cancel`도 같은 행 락(`findByUserIdForUpdate`)을
  먼저 쥔다 — 탈퇴는 빌링키를 지우기 **전에** 쥔다. 갱신이 먼저 락을 쥐었으면 그 청구가 끝난 뒤(정당한 마지막 주기) 탈퇴가 진행되고,
  탈퇴가 먼저 커밋되면 갱신은 해지된 구독을 보고 청구하지 않는다. 락은 청크 트랜잭션이 커밋될 때까지(최대 20건의 PG 호출) 유지되므로
  그동안 같은 사용자의 해지·탈퇴 요청은 기다린다. ADR-053 Notes 참고.
