/**
 * 관심종목 여러 개를 한 번에 담는다(온보딩 3단계). 서버에 일괄 추가 API가 없어 기존 단건 API를 **순서대로** 부른다.
 *
 * - 멱등: 이미 어느 그룹에든 있는 종목은 요청하지 않는다(별표 표시 useWatchlistIds와 같은 기준). 그룹이 없으면 "관심종목"
 *   그룹을 하나 만든다.
 * - 부분 실패: 실패한 종목을 모아 돌려준다. 서버는 중복을 400으로 돌려주므로(경합으로 다른 탭이 먼저 넣은 경우) 실패가
 *   있으면 그룹을 다시 읽어 이미 들어가 있는 것은 성공으로 친다.
 * - 동시 요청을 쏘지 않는다 — 단건 API를 N개 동시에 때리면 레이트 리밋·순서가 흔들린다. 최대 개수도 제한한다.
 */
export const BULK_WATCHLIST_MAX = 20;

export interface WatchlistGroupLite { id: number; items: { stockId: number }[] }

export interface BulkAddResult {
  added: number[];
  alreadyWatched: number[];
  failed: number[];
}

type Fetch = (input: string, init?: RequestInit) => Promise<Response>;

async function loadGroups(fetcher: Fetch): Promise<WatchlistGroupLite[]> {
  const r = await fetcher("/api/watchlists");
  if (!r.ok) throw new Error("관심종목 목록을 불러오지 못했습니다.");
  return r.json();
}

const watchedIds = (groups: WatchlistGroupLite[]) => new Set(groups.flatMap((g) => g.items.map((i) => i.stockId)));

export async function addStocksToWatchlist(stockIds: number[], fetcher: Fetch): Promise<BulkAddResult> {
  const unique = Array.from(new Set(stockIds)).slice(0, BULK_WATCHLIST_MAX);
  if (unique.length === 0) return { added: [], alreadyWatched: [], failed: [] };

  let groups = await loadGroups(fetcher);
  const watched = watchedIds(groups);
  const alreadyWatched = unique.filter((id) => watched.has(id));
  const todo = unique.filter((id) => !watched.has(id));
  if (todo.length === 0) return { added: [], alreadyWatched, failed: [] };

  let groupId = groups[0]?.id;
  if (groupId == null) {
    const r = await fetcher("/api/watchlists/groups", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name: "관심종목" }),
    });
    if (!r.ok) return { added: [], alreadyWatched, failed: todo };
    groupId = ((await r.json()) as { id: number }).id;
  }

  const added: number[] = [];
  let failed: number[] = [];
  for (const stockId of todo) {
    try {
      const r = await fetcher(`/api/watchlists/groups/${groupId}/items`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId }),
      });
      (r.ok ? added : failed).push(stockId);
    } catch {
      failed.push(stockId);
    }
  }

  if (failed.length > 0) {
    // 경합으로 이미 들어간 것(서버가 중복이라 400)은 실패가 아니다
    try {
      groups = await loadGroups(fetcher);
      const now = watchedIds(groups);
      alreadyWatched.push(...failed.filter((id) => now.has(id)));
      failed = failed.filter((id) => !now.has(id));
    } catch {
      /* 다시 읽지 못하면 실패로 남긴다 — 사용자가 다시 시도할 수 있다 */
    }
  }
  return { added, alreadyWatched, failed };
}
