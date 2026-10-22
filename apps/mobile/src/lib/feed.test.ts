import { test } from "node:test";
import assert from "node:assert/strict";
import { buildFeed, countToday, dailyLossUsedPct, feedTime } from "./feed.ts";

const now = new Date("2026-10-22T02:00:00Z"); // KST 11:00
const stocks = new Map([[1, { symbol: "005930", name: "삼성전자", changeRate: 4.12 }]]);

test("이벤트·신호를 최신순으로 합치고 이름 모르는 종목은 뺀다", () => {
  const feed = buildFeed({
    events: [
      { id: 1, stockId: 1, eventType: "PRICE_SPIKE", title: "급등", eventTime: "2026-10-22T01:41:00Z" },
      { id: 2, stockId: 99, eventType: "NEWS_PUBLISHED", title: "모름", eventTime: "2026-10-22T01:50:00Z" },
    ],
    signals: [{ id: 7, ruleSetName: "골든크로스", source: "SUBSCRIBED", stockId: 2, symbol: "000660", stockName: "SK하이닉스", direction: "BUY", signalTime: "2026-10-22T00:31:00Z" }],
    stocks,
    heldIds: new Set([1]),
    riskUsedPct: null,
    now,
  });
  assert.deepEqual(feed.map((f) => f.key), ["e1", "s7"]);
  assert.equal(feed[0].kind === "event" && feed[0].relation, "held");
});

test("한도 80% 이상이면 리스크 카드", () => {
  const base = { events: [], signals: [], stocks, heldIds: new Set<number>(), now };
  assert.equal(buildFeed({ ...base, riskUsedPct: 79 }).length, 0);
  assert.equal(buildFeed({ ...base, riskUsedPct: 82 })[0].kind, "risk");
});

test("한도 사용률 — 이익이면 0, 한도가 없으면 null", () => {
  assert.equal(dailyLossUsedPct(-2.46, 3), 82);
  assert.equal(dailyLossUsedPct(1.2, 3), 0);
  assert.equal(dailyLossUsedPct(-1, 0), null);
});

test("오늘 항목 수와 시각 표시는 KST 기준", () => {
  const feed = buildFeed({
    events: [
      { id: 1, stockId: 1, eventType: "PRICE_SPIKE", title: "a", eventTime: "2026-10-22T01:41:00Z" },
      { id: 2, stockId: 1, eventType: "PRICE_SPIKE", title: "b", eventTime: "2026-10-21T14:00:00Z" }, // KST 21일 23:00
    ],
    signals: [], stocks, heldIds: new Set(), riskUsedPct: 90, now,
  });
  assert.equal(countToday(feed, now), 1);
  assert.equal(feedTime("2026-10-22T01:41:00Z", now), "10:41");
  assert.equal(feedTime("2026-10-21T14:00:00Z", now), "어제");
  assert.equal(feedTime("2026-10-18T01:00:00Z", now), "10.18");
});
