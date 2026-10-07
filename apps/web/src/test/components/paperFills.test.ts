import { describe, expect, it, vi } from "vitest";
import { fetchPaperFills } from "@/hooks/usePaperFills";
import { fillsToTradeMarkers } from "@/components/portfolio/Insights";
import type { TradeHistory } from "@/hooks/usePaperTrade";

const row = (id: number, stockId: number, side: string, tradedAt = "2026-10-05T01:00:00Z"): TradeHistory => ({
  id, side, stockId, symbol: "005930", name: "삼성전자", quantity: 3, price: 71000, amount: 213000, tradedAt,
});

describe("fetchPaperFills", () => {
  it("100건 미만 페이지가 오면 멈춘다 — 체결마다 따로 부르지 않는다", async () => {
    const page = vi.fn(async (p: number) => (p === 0 ? Array.from({ length: 100 }, (_, i) => row(i, 1, "BUY")) : [row(100, 1, "SELL")]));
    const r = await fetchPaperFills(page, 5);
    expect(page).toHaveBeenCalledTimes(2);
    expect(r.fills).toHaveLength(101);
    expect(r.truncated).toBe(false);
  });

  it("최대 페이지까지 꽉 차면 잘렸다고 알린다", async () => {
    const page = vi.fn(async () => Array.from({ length: 100 }, (_, i) => row(i, 1, "BUY")));
    const r = await fetchPaperFills(page, 2);
    expect(page).toHaveBeenCalledTimes(2);
    expect(r.truncated).toBe(true);
  });

  it("조회 실패는 빈 목록으로 숨기지 않고 오류로 올린다", async () => {
    await expect(fetchPaperFills(async () => null, 2)).rejects.toThrow();
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
