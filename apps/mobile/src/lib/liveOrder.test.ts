import { test } from "node:test";
import assert from "node:assert/strict";
import { classifyLiveResult, concentrationAfter, priceDeviationPct } from "./liveOrder.ts";

const holdings = [{ symbol: "005930", quantity: 18, currentPrice: 71255 }];

test("주문 뒤 집중도 — 매수는 더하고 매도는 뺀다", () => {
  const after = concentrationAfter(3_412_560, holdings, "005930", "BUY", 2, 71000)!;
  assert.equal(Math.round(after * 10) / 10, 41.6);
  assert.equal(concentrationAfter(3_412_560, holdings, "005930", "SELL", 18, 71000), 0);
  assert.equal(concentrationAfter(0, holdings, "005930", "BUY", 1, 1), null);
});

test("4xx는 확정 거절, 5xx·연결 끊김은 결과 불명", () => {
  assert.equal(classifyLiveResult(422, { message: "종목 집중도 50% 초과" }).kind, "rejected");
  assert.match(classifyLiveResult(422, { message: "x" }).message, /리스크 한도/);
  assert.equal(classifyLiveResult(423, { message: "실거래 주문이 일시 중단되었습니다" }).message, "실거래 주문이 일시 중단되었습니다");
  assert.equal(classifyLiveResult(503, null).kind, "unknown");
  assert.equal(classifyLiveResult(null, null).kind, "unknown");
});

test("200 응답은 주문 상태로 판정", () => {
  assert.equal(classifyLiveResult(200, { status: "SUBMITTED" }).kind, "accepted");
  assert.equal(classifyLiveResult(200, { status: "UNKNOWN" }).kind, "unknown");
  assert.equal(classifyLiveResult(200, { status: "REJECTED", rejectReason: "주문가능금액 부족" }).kind, "rejected");
});

test("확정 거절이 아닌 4xx와 본문 없는 2xx는 결과 불명", () => {
  assert.equal(classifyLiveResult(408, null).kind, "unknown");
  assert.equal(classifyLiveResult(409, { message: "x" }).kind, "unknown");
  assert.equal(classifyLiveResult(200, null).kind, "unknown");
  assert.equal(classifyLiveResult(200, { status: "SUBMITTED", needsReview: true }).kind, "unknown");
  assert.equal(classifyLiveResult(200, { status: "EXPIRED" }).kind, "unknown");
});

test("지정가 괴리율", () => {
  assert.equal(priceDeviationPct(78_100, 71_000)!.toFixed(1), "10.0");
  assert.equal(priceDeviationPct(1, 0), null);
});
