import { test } from "node:test";
import assert from "node:assert/strict";
import { clampQuantity, isOrderable, maxQuantity, newIdempotencyKey, parseAmount, quickQuantity } from "./order.ts";
import { candleQuery, movingAverage } from "./chart.ts";

test("최대 수량 — 매수는 현금 ÷ 단가, 매도는 보유", () => {
  assert.equal(maxQuantity("BUY", 9_888_147, 71_000, 0), 139);
  assert.equal(maxQuantity("BUY", 9_888_147, 0, 0), 0);
  assert.equal(maxQuantity("SELL", 0, 71_000, 18), 18);
});

test("빠른 수량은 최소 1주, 최대 0이면 0", () => {
  assert.equal(quickQuantity(139, 10), 13);
  assert.equal(quickQuantity(139, 100), 139);
  assert.equal(quickQuantity(3, 10), 1);
  assert.equal(quickQuantity(0, 50), 0);
});

test("수량 입력은 0~최대 정수", () => {
  assert.equal(clampQuantity(12.7, 10), 10);
  assert.equal(clampQuantity(-3, 10), 0);
  assert.equal(clampQuantity(Number.NaN, 10), 0);
});

test("금액 입력과 주문 가능 판정", () => {
  assert.equal(parseAmount("71,000"), 71000);
  assert.equal(parseAmount(""), 0);
  assert.equal(isOrderable(10, 71000, 139), true);
  assert.equal(isOrderable(0, 71000, 139), false);
  assert.equal(isOrderable(140, 71000, 139), false);
  assert.equal(isOrderable(1, 0, 139), false);
});

test("중복 방지 키는 시도마다 다르다", () => {
  assert.notEqual(newIdempotencyKey(1, () => 0.1), newIdempotencyKey(1, () => 0.2));
});

test("1년은 일봉 + from, 나머지는 간격 그대로", () => {
  assert.equal(candleQuery("15m", new Date()), "interval=15m");
  assert.match(candleQuery("1y", new Date("2026-10-22T00:00:00Z")), /^interval=1d&from=2025-10-22/);
});

test("이동평균은 n개가 모일 때부터", () => {
  assert.deepEqual(movingAverage([1, 2, 3, 4], 2), [null, 1.5, 2.5, 3.5]);
});
