#!/usr/bin/env python3
"""L-05 정합성 검증 하네스 (ADR-045 §3) — 부하가 아니라 "부하 중에 틀리지 않았는가"를 본다.
부하 시나리오와 독립적으로 DB 를 재계산해 대조한다. psql(docker exec)만 쓴다 — python pg 드라이버 불필요.

검증 항목(ADR-045 §3, 실패 시 SLO 위반):
  잔고 : 계정마다 cash + reserved == 10,000,000 + Σ ledger.amount[CASH_EVENT_TYPES]  (드리프트 0)
  주문 : orders.filled_qty == SUM(fills.quantity) per order, 중복 fill 0
  Saga : order_sagas STARTED/COMPENSATING 잔류 0
  결제 : pg_order_id 중복 0, 성공 결제 == SUBSCRIPTION_PAYMENT 원장, 사용자당 활성구독 1건 (ADR-053)
사용: verify.py <email_like>   예: verify.py 'burst-%@bench.local'   (환경: PG_CONTAINER, PG_USER, PG_DB)
종료코드: 위반 있으면 1.
"""
import sys, subprocess, json

import os
CONTAINER = os.environ.get("PG_CONTAINER", "monticker-postgres")
USER = os.environ.get("PG_USER", "monticker"); DB = os.environ.get("PG_DB", "monticker")
INITIAL = "10000000"
CASH_TYPES = "'DEPOSIT','WITHDRAWAL','FILL','PARTIAL_FILL','FEE','SETTLEMENT','PAPER_SETTLEMENT_COMPLETE'"

def q(sql):
    out = subprocess.run(["docker","exec",CONTAINER,"psql","-U",USER,"-d",DB,"-tA","-F","\t","-c",sql],
                         capture_output=True, text=True)
    if out.returncode != 0: raise RuntimeError(out.stderr.strip())
    return [line.split("\t") for line in out.stdout.strip().splitlines() if line]

import time
def outbox_incomplete():
    r = q("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
    return int(r[0][0]) if r else 0
# 원장 기록은 Modulith 아웃박스(@ApplicationModuleListener)로 비동기 커밋된다 — 커넥션 풀 고갈 등으로 리스너 tx가
# 실패하면 event_publication 에 미완료로 남아 OutboxResubmissionConfig(5분 주기)가 재시도한다. 불변식은 "쉴 때"
# 성립하므로, 검사 전에 아웃박스가 빌 때까지 기다린다(재시도는 5분 주기라 최대 ~6분). WAIT_OUTBOX=0 으로 끌 수 있다.
if os.environ.get("WAIT_OUTBOX", "1") != "0":
    deadline = time.time() + int(os.environ.get("OUTBOX_TIMEOUT", "420"))
    n = outbox_incomplete()
    if n:
        print(f"# 아웃박스 미완료 {n}건 — 드레인 대기(최대 {int((deadline-time.time()))}s, 재시도 5분 주기)")
        while time.time() < deadline:
            time.sleep(15); m = outbox_incomplete()
            if m != n: print(f"#   미완료 {m}건"); n = m
            if m == 0: break
        print(f"# 아웃박스 드레인 {'완료' if outbox_incomplete()==0 else '미완 — 그대로 검사'}")

like = sys.argv[1] if len(sys.argv) > 1 else 'burst-%@bench.local'
users = [int(r[0]) for r in q(f"SELECT id FROM users WHERE email LIKE '{like}' ORDER BY id")]
print(f"# 대상 계정: {len(users)}개 (email LIKE '{like}')")
if not users:
    print("대상 없음"); sys.exit(0)
uids = ",".join(str(u) for u in users)

# 1) 잔고 불변식 — 계정별 드리프트
rows = q(f"""
WITH cash AS (SELECT user_id, cash FROM paper_accounts WHERE user_id IN ({uids})),
     res AS (SELECT user_id, COALESCE(SUM(limit_price*(quantity-filled_qty)),0) r FROM orders
             WHERE user_id IN ({uids}) AND side='BUY' AND status IN ('PENDING','PARTIALLY_FILLED') GROUP BY user_id),
     led AS (SELECT user_id, COALESCE(SUM(amount),0) s FROM ledger_events
             WHERE user_id IN ({uids}) AND event_type IN ({CASH_TYPES}) GROUP BY user_id)
SELECT u.id,
       COALESCE(cash.cash, {INITIAL}) AS cash,
       COALESCE(res.r,0) AS reserved,
       COALESCE(led.s,0) AS ledger_sum,
       (COALESCE(cash.cash,{INITIAL}) + COALESCE(res.r,0)) - ({INITIAL} + COALESCE(led.s,0)) AS drift
FROM users u LEFT JOIN cash ON cash.user_id=u.id LEFT JOIN res ON res.user_id=u.id LEFT JOIN led ON led.user_id=u.id
WHERE u.id IN ({uids})""")
bad_balance = [r for r in rows if abs(float(r[4])) > 0.0001]
print(f"\n[잔고] 계정 {len(rows)}개 검사, 드리프트≠0: {len(bad_balance)}개")
for r in bad_balance[:10]:
    print(f"  user={r[0]} cash={r[1]} reserved={r[2]} ledger_sum={r[3]} drift={r[4]}")

# 2) 주문 무결성 — filled_qty vs SUM(fills), 중복 fill
mismatch = q(f"""
SELECT o.id, o.filled_qty, COALESCE(SUM(f.quantity),0) fq
FROM orders o LEFT JOIN fills f ON f.order_id=o.id
WHERE o.user_id IN ({uids}) GROUP BY o.id, o.filled_qty
HAVING o.filled_qty <> COALESCE(SUM(f.quantity),0)""")
dup_fills = q(f"""SELECT fill_id, COUNT(*) FROM paper_trades WHERE fill_id IS NOT NULL
                 AND user_id IN ({uids}) GROUP BY fill_id HAVING COUNT(*) > 1""")
counts = q(f"SELECT status, COUNT(*) FROM orders WHERE user_id IN ({uids}) GROUP BY status ORDER BY status")
total_orders = sum(int(c[1]) for c in counts)
print(f"\n[주문] 총 {total_orders}건  상태: {dict((c[0],int(c[1])) for c in counts)}")
print(f"  filled_qty ≠ Σfills: {len(mismatch)}건, 중복 fill: {len(dup_fills)}건")
for r in mismatch[:10]: print(f"    order={r[0]} filled_qty={r[1]} sum_fills={r[2]}")

# 3) Saga 잔류
saga = q(f"""SELECT status, COUNT(*) FROM order_sagas WHERE user_id IN ({uids})
             AND status IN ('STARTED','COMPENSATING') GROUP BY status""")
saga_residue = sum(int(s[1]) for s in saga)
saga_all = q(f"SELECT status, COUNT(*) FROM order_sagas WHERE user_id IN ({uids}) GROUP BY status ORDER BY status")
print(f"\n[Saga] 전체: {dict((s[0],int(s[1])) for s in saga_all)}  미완료(STARTED/COMPENSATING) 잔류: {saga_residue}")

# 4) 결제 정합성 (ADR-053) — 주문 쪽과 같은 자세로 결제를 본다.
#    L-08 subscription-burst 를 돌린 뒤 여기서 대조한다. 주문만 검증하고 결제는 검증하지
#    않던 기간이 길었다 — 돈이 오가는 두 경로 중 하나만 보고 있었던 셈이다.

# 4-1) 같은 청구주기를 두 번 청구하지 않았는가. 유니크 인덱스(V50)가 막지만, 인덱스가
#      실수로 빠진 채 배포되는 회귀를 여기서 잡는다.
dup_orders = q(f"""SELECT pg_order_id, COUNT(*) FROM payment_records
                   WHERE user_id IN ({uids}) AND pg_order_id IS NOT NULL
                   GROUP BY pg_order_id HAVING COUNT(*) > 1""")

# 4-2) 성공한 결제 1건 == SUBSCRIPTION_PAYMENT 원장 1건. 결제는 됐는데 원장이 없으면
#      회계가 비고, 원장만 있으면 받지 않은 돈을 받았다고 기록한 것이다.
pay_rows = q(f"""
WITH paid AS (SELECT user_id, COUNT(*) n, COALESCE(SUM(amount),0) amt
              FROM payment_records WHERE user_id IN ({uids}) AND status='SUCCESS' GROUP BY user_id),
     led  AS (SELECT user_id, COUNT(*) n, COALESCE(SUM(-amount),0) amt
              FROM ledger_events WHERE user_id IN ({uids}) AND event_type='SUBSCRIPTION_PAYMENT' GROUP BY user_id)
SELECT u.id, COALESCE(paid.n,0), COALESCE(led.n,0), COALESCE(paid.amt,0), COALESCE(led.amt,0),
       COALESCE(paid.amt,0) - COALESCE(led.amt,0) AS drift
FROM users u LEFT JOIN paid ON paid.user_id=u.id LEFT JOIN led ON led.user_id=u.id
WHERE u.id IN ({uids}) AND (COALESCE(paid.n,0) > 0 OR COALESCE(led.n,0) > 0)""")
pay_drift = [r for r in pay_rows if int(r[1]) != int(r[2]) or abs(float(r[5])) > 0.0001]

# 4-3) 사용자당 활성 구독은 하나뿐이어야 한다(user_subscriptions.user_id UNIQUE).
dup_subs = q(f"""SELECT user_id, COUNT(*) FROM user_subscriptions
                 WHERE user_id IN ({uids}) GROUP BY user_id HAVING COUNT(*) > 1""")

# 4-4) PENDING 결제는 "불확정으로 판단을 미룬" 흔적이다 — 위반은 아니지만 남기면 안 된다.
pending = q(f"SELECT COUNT(*) FROM payment_records WHERE user_id IN ({uids}) AND status='PENDING'")
pending_n = int(pending[0][0]) if pending else 0
pay_counts = q(f"SELECT status, COUNT(*) FROM payment_records WHERE user_id IN ({uids}) GROUP BY status ORDER BY status")
print(f"\n[결제] 상태: {dict((c[0],int(c[1])) for c in pay_counts)}  (PENDING {pending_n}건 = 불확정 보류)")
print(f"  중복 orderId: {len(dup_orders)}건, 결제↔원장 불일치: {len(pay_drift)}명, 중복 구독행: {len(dup_subs)}명")
for r in dup_orders[:10]: print(f"    orderId={r[0]} 건수={r[1]}")
for r in pay_drift[:10]:
    print(f"    user={r[0]} 성공결제={r[1]}건/{r[3]} 원장={r[2]}건/{r[4]} drift={r[5]}")

pay_violations = len(dup_orders) + len(pay_drift) + len(dup_subs)

violations = len(bad_balance) + len(mismatch) + len(dup_fills) + saga_residue + pay_violations
print(f"\n{'PASS ✅' if violations==0 else f'FAIL ❌ (위반 {violations})'} — 잔고 오차 {len(bad_balance)}, 체결 불일치 {len(mismatch)}, 중복 fill {len(dup_fills)}, Saga 잔류 {saga_residue}, 결제 {pay_violations}")
sys.exit(1 if violations else 0)
