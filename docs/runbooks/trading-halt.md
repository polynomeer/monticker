# 실거래 킬 스위치 — `TradingHaltActive` (ticket) · 사고 대응의 첫 단계

[ADR-057](../decisions/057-real-order-kill-switch.md). **원인을 찾기 전에 피해가 늘어나는 것부터 막는다.**
실주문이 잘못 나가고 있다는 의심이 들면(잘못된 배포, 오염된 시세, 자격증명 유출, 규제 요청) 먼저 켜고 그다음 조사한다.

## 언제 켜나
| 상황 | 범위 |
|------|------|
| 배포 직후 주문이 이상하다, 리스크 게이트가 이상하다, 시세 출처가 이상하다(ADR-055) | **GLOBAL** |
| 한 증권사만 이상하다(응답 형식 변경, 그쪽 사고 공지) — 서킷브레이커는 실패할 때만 열린다 | **PROVIDER** (`KIS`/`TOSS`) |
| 한 계정만 이상하다(자격증명 유출 의심, 비정상 주문 패턴) | **USER** — 사용자에게는 사유가 보이지 않는다 |

## 켜기
```bash
# 관리자 토큰으로 (모든 호출은 @Audited + trading_halts 행으로 남는다)
curl -X POST "$API/api/admin/trading-halts" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"scope":"GLOBAL","reason":"배포 v1.2.3 주문 수량 이상 — 조사 중"}'
# 증권사: {"scope":"PROVIDER","target":"KIS","reason":"..."}   사용자: {"scope":"USER","target":"<userId>","reason":"..."}
```

**앱이 죽었거나 관리자 인증이 안 될 때 — SQL 한 줄.** 캐시가 없어 다음 주문부터 즉시 막힌다.
```sql
INSERT INTO trading_halts (scope, reason) VALUES ('GLOBAL', '비상 수동 정지 — <누가, 왜>');
```
`reason`은 사용자 화면에 그대로 보인다(전역·증권사 범위). 사용자에게 보여도 되는 문장으로 쓴다.

## 켜진 동안 일어나는 일
- 새 실주문(수동·조건부·리밸런싱) → **423** `TRADING_HALTED`. 주문 행도 증권사 호출도 없다. 5xx가 아니므로 `OrderPathDown`은 울리지 않는다.
- **이미 증권사로 나간 주문은 회수되지 않는다.** 미체결 주문은 취소로 거둔다 — 취소는 막히지 않는다.
- 결과 불명 대조(ADR-056)·잔고·주문 조회는 계속된다.
- 조건부 주문은 **발동하지 않고 ACTIVE로 남는다**(`conditional_order_halted_total` 증가). 사용자 화면에 배너.

## 1차 확인
1. 켠 직후 `brokerage_order_blocked_by_halt_total`이 오르는지 — 막히고 있다는 증거.
2. 켜기 직전 몇 분 동안 나간 주문: `SELECT * FROM brokerage_orders WHERE submitted_at > now() - interval '15 minutes' ORDER BY submitted_at DESC;`
3. 그 주문 중 취소가 필요한 미체결(`SUBMITTED`)과 결과 불명(`UNKNOWN`/`PENDING_SUBMIT`)을 분리한다.

## 해제 전에 — 반드시
해제 직후 **첫 실시세 틱에 조건을 만족한 조건부 주문이 한꺼번에 발동한다.** 긴 정지 뒤 시장이 움직였다면 많은 스탑로스가 갭 가격에 나간다.
```sql
-- 해제하면 바로 발동할 후보: 활성 조건부 주문 수와 종목 분포
SELECT symbol, side, trigger_type, COUNT(*) FROM conditional_orders WHERE status = 'ACTIVE' GROUP BY 1,2,3 ORDER BY 4 DESC;
```
규모가 크면 장중 변동성이 낮은 시점을 고르거나, 사용자 공지 후 해제한다.

## 해제
```bash
curl -X POST "$API/api/admin/trading-halts/<id>/lift" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"v1.2.4 롤백 확인, 주문 수량 정상"}'
```
SQL로 켰고 앱이 아직 없으면: `UPDATE trading_halts SET lifted_at = now(), lift_reason = '...' WHERE id = <id> AND lifted_at IS NULL;`

## 알림 `TradingHaltActive`
전역·증권사 스위치가 30분 넘게 켜져 있다. **잊힌 스위치는 조용한 장애다** — 사용자 주문이 계속 막힌다. 아직 필요한지 판단하고 스레드에 남긴다.

## 이력
`SELECT * FROM trading_halts ORDER BY halted_at DESC;` — 행은 지워지지 않는다. `halted_by IS NULL`은 SQL로 직접 켠 것.
