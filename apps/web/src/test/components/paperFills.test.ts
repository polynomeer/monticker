import { describe, expect, it, vi } from "vitest";
import { fetchPaperFills, historyParams } from "@/hooks/usePaperFills";
import { fillsToTradeMarkers } from "@/components/portfolio/Insights";
import { bucketOf, bucketStartOf } from "@/components/stock/chart/tradeMarkers";
import type { TradeHistory } from "@/hooks/usePaperTrade";

const row = (id: number, stockId: number, side: string, tradedAt = "2026-10-05T01:00:00Z"): TradeHistory => ({
  id, side, stockId, symbol: "005930", name: "삼성전자", quantity: 3, price: 71000, amount: 213000, tradedAt,
});

const FROM = Date.parse("2026-01-01T15:00:00Z") / 1000; // KST 2026-01-02 00:00
const full = () => Array.from({ length: 100 }, (_, i) => row(i, 7, "BUY"));

/** 본 목록(from 있음)과 이전 체결 확인(to 있음)을 나눠 답하는 가짜 페이지 */
function fakePages(main: (page: number) => TradeHistory[] | null, earlier: TradeHistory[] | null = []) {
  return vi.fn(async (p: URLSearchParams) => (p.has("to") ? earlier : main(Number(p.get("page")))));
}

describe("historyParams", () => {
  it("종목·구간을 서버 필터로 보낸다(ISO-8601, from 포함·to 제외)", () => {
    const p = historyParams({ stockId: 7, page: 2, size: 100, from: FROM });
    expect(p.get("stockId")).toBe("7");
    expect(p.get("page")).toBe("2");
    expect(p.get("size")).toBe("100");
    expect(p.get("from")).toBe("2026-01-01T15:00:00.000Z");
    expect(p.has("to")).toBe(false);
    expect(historyParams({ stockId: 7, page: 0, size: 1, to: FROM }).get("to")).toBe("2026-01-01T15:00:00.000Z");
  });
});

describe("fetchPaperFills", () => {
  it("선택 종목·차트 구간만 요청하고 100건 미만 페이지가 오면 멈춘다", async () => {
    const page = fakePages((p) => (p === 0 ? full() : [row(100, 7, "SELL")]));
    const r = await fetchPaperFills({ stockId: 7, fromSec: FROM }, page, 20);
    const mainCalls = page.mock.calls.map(([p]) => p).filter((p) => !p.has("to"));
    expect(mainCalls).toHaveLength(2);
    mainCalls.forEach((p) => {
      expect(p.get("stockId")).toBe("7");
      expect(p.get("from")).toBe(new Date(FROM * 1000).toISOString());
    });
    expect(r.fills).toHaveLength(101);
    expect(r.truncated).toBe(false);
    expect(r.hasEarlier).toBe(false);
  });

  it("구간 이전 체결은 1건짜리 요청 하나로만 확인한다", async () => {
    const page = fakePages(() => [row(1, 7, "BUY")], [row(0, 7, "BUY", "2025-12-01T00:00:00Z")]);
    const r = await fetchPaperFills({ stockId: 7, fromSec: FROM }, page, 20);
    const earlierCalls = page.mock.calls.map(([p]) => p).filter((p) => p.has("to"));
    expect(earlierCalls).toHaveLength(1);
    expect(earlierCalls[0].get("size")).toBe("1");
    expect(earlierCalls[0].has("from")).toBe(false);
    expect(r.hasEarlier).toBe(true);
    expect(r.fills).toHaveLength(1);
  });

  it("최대 페이지까지 꽉 차면 잘렸다고 알린다", async () => {
    const page = fakePages(() => full());
    const r = await fetchPaperFills({ stockId: 7, fromSec: FROM }, page, 2);
    expect(page.mock.calls.filter(([p]) => !p.has("to"))).toHaveLength(2);
    expect(r.truncated).toBe(true);
  });

  it("조회 실패는 빈 목록으로 숨기지 않고 오류로 올린다", async () => {
    await expect(fetchPaperFills({ stockId: 7, fromSec: FROM }, fakePages(() => null), 2)).rejects.toThrow();
    await expect(fetchPaperFills({ stockId: 7, fromSec: FROM }, fakePages(() => [], null), 2)).rejects.toThrow();
  });
});

describe("bucketStartOf", () => {
  it("일봉 버킷 시작은 KST 자정이고 같은 버킷 안에서 같다", () => {
    const t = Date.parse("2026-01-02T05:30:00Z") / 1000; // KST 14:30
    expect(bucketStartOf(t, "1d")).toBe(FROM);
    expect(bucketOf(bucketStartOf(t, "1d"), "1d")).toBe(bucketOf(t, "1d"));
    expect(bucketOf(bucketStartOf(t, "1d") - 1, "1d")).toBe(bucketOf(t, "1d") - 1);
  });
});

describe("fillsToTradeMarkers", () => {
  it("선택한 종목의 체결만, 시각을 epoch seconds로", () => {
    const m = fillsToTradeMarkers([row(1, 7, "BUY"), row(2, 8, "BUY"), row(3, 7, "SELL"), row(4, 7, "BUY", "bad")], 7);
    expect(m).toEqual([
      { id: 1, time: Date.parse("2026-10-05T01:00:00Z") / 1000, side: "BUY", price: 71000, qty: 3 },
      { id: 3, time: Date.parse("2026-10-05T01:00:00Z") / 1000, side: "SELL", price: 71000, qty: 3 },
    ]);
  });
});
