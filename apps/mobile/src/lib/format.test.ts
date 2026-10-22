import { test } from "node:test";
import assert from "node:assert/strict";
import { formatPct, formatPrice, formatSignedWon, formatWon, sparklinePoints } from "./format.ts";

test("등락률은 부호와 소수 둘째 자리", () => {
  assert.equal(formatPct(4.123), "+4.12%");
  assert.equal(formatPct(-3.05), "-3.05%");
  assert.equal(formatPct(0), "0.00%");
});

test("원화는 쉼표, 손익은 부호", () => {
  assert.equal(formatWon(9998402.4), "9,998,402");
  assert.equal(formatSignedWon(2820), "+2,820");
  assert.equal(formatSignedWon(-12400), "-12,400");
  assert.equal(formatSignedWon(0.2), "0");
});

test("해외 가격은 달러 둘째 자리", () => {
  assert.equal(formatPrice(141.2, "USD"), "$141.20");
  assert.equal(formatPrice(71255), "71,255");
});

test("스파크라인 좌표는 위가 큰 값, 평평하면 가운데", () => {
  const pts = sparklinePoints([1, 3, 2], 60, 28, 2);
  assert.deepEqual(pts[0], [0, 26]);
  assert.deepEqual(pts[1], [30, 2]);
  assert.equal(pts[2][0], 60);
  assert.deepEqual(sparklinePoints([5, 5], 10, 20).map((p) => p[1]), [10, 10]);
});
