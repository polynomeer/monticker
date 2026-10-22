import { test } from "node:test";
import assert from "node:assert/strict";
import { allocation, forwardStats, ledgerFlow, ledgerTitle, ledgerTone, weeksSince } from "./account.ts";

test("원장 이동 방향", () => {
  assert.equal(ledgerFlow("CASH_RESERVED", -39000), "현금 → 예약금");
  assert.equal(ledgerFlow("FILL", -40453), "현금 → 평가액");
  assert.equal(ledgerFlow("FILL", 71000), "평가액 → 현금");
  assert.equal(ledgerFlow("SUBSCRIPTION_PAYMENT", -1), "");
});

test("원장 제목은 설명 우선, 없으면 유형 이름", () => {
  assert.equal(ledgerTitle({ eventType: "FILL", amount: 1, description: "매수 체결 · 삼성전자 1주" }), "매수 체결 · 삼성전자 1주");
  assert.equal(ledgerTitle({ eventType: "CASH_RESERVED", amount: 1, description: null }), "주문 예약");
  assert.equal(ledgerTone("CASH_RESERVED"), "caution");
});

test("막대 비율은 작은 칸도 최소 폭", () => {
  const [cash, reserved, held] = allocation([9_888_147, 39_000, 71_255]);
  assert.ok(cash > 95);
  assert.equal(reserved, 1.5);
  assert.equal(allocation([0, 0]).join(), "0,0");
});

test("포워드 수익·낙폭", () => {
  const s = forwardStats(10_000_000, 10_840_000, [{ equity: 1, drawdown: -0.061 }, { equity: 1, drawdown: -0.02 }]);
  assert.equal(Math.round(s.returnPct! * 10) / 10, 8.4);
  assert.equal(Math.round(s.mddPct! * 10) / 10, -6.1);
  assert.equal(forwardStats(0, 1, []).returnPct, null);
});

test("몇 주째", () => {
  const now = new Date("2026-10-22T00:00:00Z");
  assert.equal(weeksSince("2026-10-21T00:00:00Z", now), 1);
  assert.equal(weeksSince("2026-08-06T00:00:00Z", now), 12);
});
