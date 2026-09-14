# 원장 불일치 — `LedgerMismatch` (page) · `LedgerMismatchReported` (ticket) · `LedgerReconciliationDidNotRun`

## 이 런북의 첫 문장
**자동으로 고치지 않는다. 스크립트로 고치지 않는다. 급하게 고치지 않는다.**
불일치는 두 가지 원인이 가능하다 — 원장 누락(기록 실패) 또는 잔고 오염(잘못된 UPDATE). 방향을 틀리면 사용자 돈이 사라진다([ADR-043](../decisions/043-ledger-pagination-and-reconciliation.md)).

## 증상
- `LedgerMismatch`: 지난 1시간 대사에서 `mode="alert"` 불일치 ≥ 1. 17:30 KST 배치 직후에 온다.
- `LedgerMismatchReported`: `LEDGER_RECON_MODE=report` 기간의 발견. 페이지는 아니지만 같은 절차.
- `LedgerReconciliationDidNotRun`: 평일 26시간 동안 `ledger_reconciliation_checked_total` 증가 0 — **불일치가 없는 게 아니라 안 본 것**.

## 불변식 (무엇이 어긋났는가)
```
paper_accounts.cash + reserved(미체결 BUY limit_price × 잔량)  =  10,000,000 + Σ ledger_events.amount[현금 영향 타입]
```
현금 영향 타입: DEPOSIT WITHDRAWAL FILL PARTIAL_FILL FEE SETTLEMENT PAPER_SETTLEMENT_COMPLETE. 구독·크리에이터·증권사 정산과 CASH_RESERVED/UNRESERVED는 제외(`LedgerReconciliationService.CASH_EVENT_TYPES`).

## 1차 확인 (3단계)
1. **누가, 얼마나**:
   ```sql
   SELECT user_id, as_of_date, account_cash, reserved_cash, ledger_sum,
          (account_cash + reserved_cash) - (10000000 + ledger_sum) AS drift
   FROM ledger_snapshots WHERE mismatch ORDER BY as_of_date DESC, abs((account_cash + reserved_cash) - (10000000 + ledger_sum)) DESC;
   ```
   drift가 **양수** = 잔고가 원장보다 크다(원장에 없는 입금, 또는 원장에 있어야 할 출금 누락). **음수** = 반대.
2. **언제부터**: 같은 유저의 이전 스냅샷을 본다. `mismatch=false`였던 마지막 날짜와 첫 `true` 사이가 창이다. 스냅샷은 매일 쌓이므로 하루로 좁혀진다.
3. **그날 무슨 일이**: 그 창의 `ledger_events`, `orders`/`fills`, `paper_trades`(fill_id 링크), `event_publication`(미완료 리스너 — 원장 리스너가 실패했으면 여기 남는다), `order_sagas`(COMPENSATING/FAILED).

## 흔한 원인과 판별
| 관찰 | 원인 후보 | 판별 |
|------|----------|------|
| `event_publication`에 `PaperTradeEventListener`/`OrderFilledEventListener` 미완료 행 | 원장 기록 실패(예: 이전의 jsonb 바인딩 결함) | 미완료 행의 이벤트와 잔고 변화가 일치하면 **원장 누락** — Outbox 재전송이 5분 뒤 스스로 메운다. 재전송 뒤 재대사 |
| `order_sagas` FAILED | 보상 트랜잭션 실패 — 예약금이 안 돌아왔거나 두 번 돌아옴 | 사가 로그(`[Saga:{id}] 보상 트랜잭션 실패`) |
| drift == 미체결 주문 예약금 | 초기화 뒤 남은 예약(ADR-047 이전 데이터) 또는 `reserved` 계산 대상 상태 불일치 | `orders WHERE status IN ('PENDING','PARTIALLY_FILLED')` 대조 |
| drift가 정확히 수수료+세금 | T+2 정산이 cash에서 빼고 원장(PAPER_SETTLEMENT_COMPLETE)을 안 썼거나 반대 | `paper_settlements` SETTLED 시각과 원장 |
| 유저 여럿이 같은 날 같은 방향 | 코드 변경(배포)이 원인 | 배포 시각과 대조 → [deploy-rollback.md](deploy-rollback.md) |

## 완화
- 없다. 잔고를 건드리지 않는다. 해당 유저의 거래를 막을지는 drift 크기와 방향으로 판단(양수 큰 drift = 없는 돈으로 거래 중 → 거래 중단 고려).
- 재대사로 확인: `POST /api/admin/batch/ledger-reconciliation?date=YYYY-MM-DD` (ADMIN). Outbox 재전송 이후 사라지는 불일치는 일시적이었다.

## 교정 (원인이 확정된 뒤에만)
- **원장 누락**: 누락된 이벤트를 원장에 **추가**한다(append-only — 기존 행 수정·삭제 금지). `description`에 사고 ID.
- **잔고 오염**: 원장이 진실이면 잔고를 원장 합 + 초기 지급으로 맞춘다 — `paper_accounts` UPDATE 한 번, 그 UPDATE도 원장에 DEPOSIT/WITHDRAWAL로 남긴다(사고 ID).
- 교정 후 재대사 → `mismatch=false` 확인.

## 에스컬레이션
- 실브로커 계좌(BYOK)가 관련되면 즉시 — 이 대사는 모의투자 현금만 본다. 실거래 정합성은 별도 ADR 대상.
- 원인을 24시간 안에 못 좁히면 해당 유저 거래 중단 + 사용자 통지.
