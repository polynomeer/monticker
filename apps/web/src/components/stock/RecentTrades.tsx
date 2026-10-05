"use client";

import { useQuery } from "@tanstack/react-query";
import { fmtNum } from "@/components/terminal";
import { cn } from "@/lib/utils";

export interface RecentTrade {
  price: number;
  volume: number;
  tradeTime: string;
  direction: "UP" | "DOWN" | "FLAT";
  /** 시세 출처 — KIS·TOSS는 실시세, MOCK은 합성 시세 */
  source: "KIS" | "TOSS" | "MOCK" | "UNKNOWN";
}

/** 종목별 최근 체결 틱(최신순) — 서버 pod가 받은 실시간 틱의 링 버퍼다. 영속 이력이 아니다. */
export function useRecentTrades(stockId: number, limit = 50) {
  return useQuery<RecentTrade[]>({
    queryKey: ["stocks", stockId, "trades", limit],
    queryFn: async () => {
      const r = await fetch(`/api/stocks/${stockId}/trades?limit=${limit}`);
      if (!r.ok) throw new Error("체결 조회 실패");
      return r.json();
    },
    refetchInterval: 2_000,
  });
}

const time = (iso: string) =>
  new Date(iso).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });

/** 호가 패널 "체결" 탭·매칭 화면 체결 테이프 공용 리스트 */
export default function RecentTrades({ stockId, limit = 50, maxRows }: { stockId: number; limit?: number; maxRows?: number }) {
  const { data = [], isLoading, isError } = useRecentTrades(stockId, limit);
  const rows = maxRows ? data.slice(0, maxRows) : data;
  const simulated = data.some((t) => t.source !== "KIS" && t.source !== "TOSS");

  if (isLoading) return <div className="m-1.5 h-24 animate-pulse rounded-lg bg-tm-inner" />;
  if (isError) return <p className="m-0 py-8 text-center text-13 text-tm-muted">체결 내역을 불러오지 못했습니다.</p>;
  if (rows.length === 0) {
    return <p className="m-0 py-8 text-center text-13 text-tm-muted">아직 들어온 체결이 없습니다. 장중에 실시간으로 쌓입니다.</p>;
  }
  return (
    <div className="flex flex-col">
      <div className="grid grid-cols-[1fr_1fr_auto] gap-2 px-3 pb-1 text-2xs text-tm-muted">
        <span>시각</span><span className="text-right">체결가</span><span className="w-16 text-right">수량</span>
      </div>
      {rows.map((t, i) => (
        <div key={`${t.tradeTime}-${i}`} className="grid grid-cols-[1fr_1fr_auto] gap-2 px-3 py-[3px] text-xs">
          <span className="num text-tm-muted">{time(t.tradeTime)}</span>
          <span className={cn("num text-right", t.direction === "UP" ? "text-up" : t.direction === "DOWN" ? "text-down" : "")}>
            {fmtNum(t.price)}
          </span>
          <span className="num w-16 text-right text-tm-soft">{fmtNum(t.volume)}</span>
        </div>
      ))}
      {simulated && <p className="m-0 px-3 pt-2 text-2xs text-tm-muted">합성(모의) 시세가 섞여 있습니다.</p>}
    </div>
  );
}
