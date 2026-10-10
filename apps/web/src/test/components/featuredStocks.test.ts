import { describe, expect, it, vi } from "vitest";
import { findStockIdBySymbol, resolveFeaturedStocks } from "@/hooks/useFeaturedStocks";

/** 검색 API 대역: query → 결과 목록 */
function fakeFetch(results: Record<string, { id: number; symbol: string }[] | "error">) {
  return vi.fn(async (url: string) => {
    const q = decodeURIComponent(String(url).split("query=")[1] ?? "");
    const r = results[q];
    if (r === "error") return new Response("boom", { status: 500 });
    return new Response(JSON.stringify(r ?? []), { status: 200 });
  }) as unknown as typeof fetch;
}

describe("findStockIdBySymbol", () => {
  it("takes the exact symbol match, not the first search hit", async () => {
    const f = fakeFetch({ "005930": [{ id: 77, symbol: "005935" }, { id: 4, symbol: "005930" }] });
    expect(await findStockIdBySymbol("005930", f)).toBe(4);
  });

  it("picks the row of the given market when the same symbol is listed twice", async () => {
    const f = fakeFetch({ "035420": [{ id: 4, symbol: "035420", market: "KOSDAQ" } as never, { id: 10, symbol: "035420", market: "KOSPI" } as never] });
    expect(await findStockIdBySymbol("035420", f, "KOSPI")).toBe(10);
  });

  it("matches US tickers case-insensitively", async () => {
    expect(await findStockIdBySymbol("aapl", fakeFetch({ aapl: [{ id: 155, symbol: "AAPL" }] }))).toBe(155);
  });

  it("returns null when nothing matches or the request fails", async () => {
    expect(await findStockIdBySymbol("NVDA", fakeFetch({ NVDA: [{ id: 9, symbol: "NVDL" }] }))).toBeNull();
    expect(await findStockIdBySymbol("NVDA", fakeFetch({ NVDA: "error" }))).toBeNull();
  });
});

describe("resolveFeaturedStocks", () => {
  it("keeps the order and drops symbols that are not found", async () => {
    const f = fakeFetch({ "005930": [{ id: 1, symbol: "005930" }], AAPL: [{ id: 155, symbol: "AAPL" }] });
    const list = [{ symbol: "005930", label: "삼성전자" }, { symbol: "000660", label: "SK하이닉스" }, { symbol: "AAPL", label: "AAPL" }];
    expect(await resolveFeaturedStocks(list, f)).toEqual([
      { symbol: "005930", label: "삼성전자", id: 1 },
      { symbol: "AAPL", label: "AAPL", id: 155 },
    ]);
  });
});
