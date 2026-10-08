import { describe, it, expect, vi } from "vitest";
import { BULK_WATCHLIST_MAX, addStocksToWatchlist, type WatchlistGroupLite } from "@/lib/watchlistBulk";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** 메모리 위 가짜 watchlist API — 같은 종목 중복은 서버처럼 400. */
function fakeApi(initial: WatchlistGroupLite[], opts: { failIds?: number[]; raceIds?: number[] } = {}) {
  const groups = structuredClone(initial);
  const calls: string[] = [];
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    calls.push(`${init?.method ?? "GET"} ${url}`);
    if (url === "/api/watchlists" && !init?.method) return json(groups);
    if (url === "/api/watchlists/groups" && init?.method === "POST") {
      const g = { id: 100 + groups.length, items: [] };
      groups.push(g);
      return json(g);
    }
    const m = url.match(/^\/api\/watchlists\/groups\/(\d+)\/items$/);
    if (m && init?.method === "POST") {
      const stockId = JSON.parse(String(init.body)).stockId as number;
      const g = groups.find((x) => x.id === Number(m[1]))!;
      if (opts.raceIds?.includes(stockId)) {
        g.items.push({ stockId }); // 다른 탭이 먼저 넣었다 → 서버는 중복이라 400
        return new Response(null, { status: 400 });
      }
      if (opts.failIds?.includes(stockId)) return new Response(null, { status: 500 });
      if (g.items.some((i) => i.stockId === stockId)) return new Response(null, { status: 400 });
      g.items.push({ stockId });
      return json({ id: stockId, stockId });
    }
    return new Response(null, { status: 404 });
  });
  return { fetcher, calls, groups };
}

describe("addStocksToWatchlist", () => {
  it("이미 어느 그룹에든 있는 종목은 요청하지 않는다(멱등)", async () => {
    const api = fakeApi([{ id: 1, items: [{ stockId: 10 }] }, { id: 2, items: [{ stockId: 20 }] }]);
    const r = await addStocksToWatchlist([10, 20, 30, 30], api.fetcher);
    expect(r).toEqual({ added: [30], alreadyWatched: [10, 20], failed: [] });
    expect(api.calls.filter((c) => c.startsWith("POST"))).toEqual(["POST /api/watchlists/groups/1/items"]);
  });

  it("두 번 불러도 같은 결과 — 두 번째는 쓰지 않는다", async () => {
    const api = fakeApi([{ id: 1, items: [] }]);
    await addStocksToWatchlist([1, 2], api.fetcher);
    const before = api.calls.length;
    const r = await addStocksToWatchlist([1, 2], api.fetcher);
    expect(r).toEqual({ added: [], alreadyWatched: [1, 2], failed: [] });
    expect(api.calls.slice(before).every((c) => c.startsWith("GET"))).toBe(true);
  });

  it("그룹이 없으면 하나 만들고 담는다", async () => {
    const api = fakeApi([]);
    const r = await addStocksToWatchlist([5], api.fetcher);
    expect(r.added).toEqual([5]);
    expect(api.calls).toContain("POST /api/watchlists/groups");
  });

  it("부분 실패는 실패한 종목만 돌려주고 나머지는 계속 담는다", async () => {
    const api = fakeApi([{ id: 1, items: [] }], { failIds: [2] });
    const r = await addStocksToWatchlist([1, 2, 3], api.fetcher);
    expect(r).toEqual({ added: [1, 3], alreadyWatched: [], failed: [2] });
  });

  it("경합으로 이미 들어간 종목(서버 400)은 다시 읽어 실패가 아닌 것으로 친다", async () => {
    const api = fakeApi([{ id: 1, items: [] }], { raceIds: [7] });
    const r = await addStocksToWatchlist([7, 8], api.fetcher);
    expect(r).toEqual({ added: [8], alreadyWatched: [7], failed: [] });
  });

  it("네트워크 오류도 실패로 모은다", async () => {
    const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
      if (!init?.method) return json([{ id: 1, items: [] }]);
      throw new TypeError("network");
    });
    const r = await addStocksToWatchlist([1], fetcher);
    expect(r.failed).toEqual([1]);
  });

  it("한 번에 최대 개수까지만 보낸다", async () => {
    const api = fakeApi([{ id: 1, items: [] }]);
    const ids = Array.from({ length: BULK_WATCHLIST_MAX + 5 }, (_, i) => i + 1);
    const r = await addStocksToWatchlist(ids, api.fetcher);
    expect(r.added).toHaveLength(BULK_WATCHLIST_MAX);
  });

  it("빈 선택이면 아무 요청도 하지 않는다", async () => {
    const api = fakeApi([]);
    await addStocksToWatchlist([], api.fetcher);
    expect(api.calls).toEqual([]);
  });
});
