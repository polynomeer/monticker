// 전략 마켓 "공유 전략" 총수 — `GET /api/quant/market/count`(목록과 같은 공개 범위만 센다).
import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

export function useMarketTotal() {
  return useQuery<number>({
    queryKey: ["quant", "market", "count"],
    queryFn: async () => {
      const res = await authFetch("/api/quant/market/count");
      if (!res.ok) throw new Error("공유 전략 수 조회 실패");
      const body: { total?: unknown } = await res.json();
      if (typeof body.total !== "number") throw new Error("공유 전략 수 응답 형식 오류");
      return body.total;
    },
    staleTime: 60_000,
  });
}

/** 총수를 못 받았으면 "N+개"로 추정하지 않고 "—"로 둔다. */
export function sharedTotalLabel(total: number | undefined): string {
  return total == null ? "—" : `${total.toLocaleString("ko-KR")}개`;
}

/** 다음 페이지가 있는가 — 총수가 있으면 그걸로, 없으면 이번 페이지가 꽉 찼는지로 판단한다. */
export function hasNextPage(page: number, pageSize: number, shownOnPage: number, total: number | undefined): boolean {
  if (total != null) return (page + 1) * pageSize < total;
  return shownOnPage === pageSize;
}
