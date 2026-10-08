import type { Range52w, WatchlistItem } from "@/components/home/data";

export type MoveDir = "up" | "down";

/**
 * "위로/아래로" 한 칸 이동의 목표 자리(그룹 전체 기준 0부터). 시장 필터로 일부만 보일 때는 **보이는 이웃** 자리로 옮긴다
 * (숨은 종목을 건너뛰어야 화면에서 실제로 한 칸 움직인다). 더 갈 곳이 없으면 null.
 *
 * @param groupItems 그룹 전체(서버 순서)
 * @param visibleIds 화면에 보이는 순서대로의 항목 id(내 순서 정렬일 때만 부른다)
 */
export function moveTarget(groupItems: Pick<WatchlistItem, "id">[], visibleIds: number[], itemId: number, dir: MoveDir): number | null {
  const v = visibleIds.indexOf(itemId);
  if (v < 0) return null;
  const neighbour = visibleIds[dir === "up" ? v - 1 : v + 1];
  if (neighbour == null) return null;
  const target = groupItems.findIndex((i) => i.id === neighbour);
  return target < 0 ? null : target;
}

/** 서버 WatchlistOrdering.move와 같은 규칙 — 낙관적 갱신용. 범위를 넘으면 맨 끝. */
export function applyMove<T extends { id: number }>(items: T[], itemId: number, target: number): T[] {
  const from = items.findIndex((i) => i.id === itemId);
  if (from < 0 || target < 0) return items;
  const rest = items.filter((_, i) => i !== from);
  rest.splice(Math.min(target, rest.length), 0, items[from]);
  return rest;
}

const fmtDate = (iso: string) => iso.replaceAll("-", ".");

/**
 * 52주 고저 라벨. 일봉이 52주를 다 덮으면 "52주 최고/최저", 아니면 "최고/최저"에 실제 기간을 붙인다
 * (짧은 기간의 고저를 52주라고 부르지 않는다).
 */
export function range52wLabels(r: Range52w | null | undefined): { high: string; low: string; sub?: string } {
  if (!r || r.fullPeriod) return { high: "52주 최고", low: "52주 최저" };
  return { high: "최고", low: "최저", sub: `${fmtDate(r.firstDate)}~${fmtDate(r.lastDate)}` };
}
