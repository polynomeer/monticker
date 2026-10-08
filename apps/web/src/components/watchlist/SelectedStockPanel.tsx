"use client";

import { useQuery } from "@tanstack/react-query";
import StockChart from "@/components/stock/chart/StockChart";
import { BtnLink, EventBadge, Panel, Stat, dirClass, fmtNum, fmtPct } from "@/components/terminal";
import { fetchCandles, fetchEvents, stockKeys } from "@/hooks/useStockChart";
import type { ScreenerItem } from "@/hooks/useScreener";
import { eventLabel, kstTime, type WatchlistItem } from "@/components/home/data";
import { range52wLabels } from "./order";

/** 오른쪽 "선택 종목" 패널 — 최근 40개 일봉 차트, 52주 고저(서버 계산), PER, 최근 이벤트. */
export default function SelectedStockPanel({ item, quote }: { item: WatchlistItem | null; quote?: ScreenerItem }) {
  const stockId = item?.stockId ?? null;

  const { data: candles = [], isLoading } = useQuery({
    queryKey: stockId ? stockKeys.candles(stockId, "1Y") : ["disabled"],
    queryFn: () => fetchCandles(stockId!, "1Y"),
    enabled: !!stockId,
    staleTime: 5 * 60_000,
  });
  const { data: events = [] } = useQuery({
    queryKey: stockId ? stockKeys.events(stockId) : ["disabled"],
    queryFn: () => fetchEvents(stockId!),
    enabled: !!stockId,
    staleTime: 30_000,
  });

  if (!item) {
    return (
      <Panel tabs={["선택 종목"]} actions={["expand"]} className="flex-[1_1_320px]">
        <p className="py-10 text-center text-13 text-tm-muted">종목을 선택하면 요약이 표시됩니다.</p>
      </Panel>
    );
  }

  // 52주 고저 — 관심종목 응답(range52w, candles_1d 기준). 일봉이 52주를 다 덮지 못하면 실제 기간을 함께 적는다.
  const range = item.range52w ?? null;
  const labels = range52wLabels(range);
  const recent = [...events].sort((a, b) => b.time - a.time).slice(0, 3);
  const rate = quote?.changeRate ?? null;

  return (
    <Panel tabs={["선택 종목"]} actions={["expand"]} className="flex-[1_1_320px]">
      <div className="flex items-start justify-between">
        <div className="flex flex-col gap-0.5">
          <span className="text-base font-bold">{item.name}</span>
          <span className="num text-[1.375rem] font-semibold">{quote ? fmtNum(quote.price) : "—"}</span>
          <span className={`num text-13 ${dirClass(rate)}`}>
            {quote ? `${rate! >= 0 ? "▲" : "▼"} ${fmtNum(Math.abs(quote.changeAmount))} ${fmtPct(rate)}` : "—"}
          </span>
        </div>
        <BtnLink kind="ghost" size="sm" href={`/stocks/${item.symbol}`} className="h-[34px]">차트</BtnLink>
      </div>

      <div className="overflow-hidden rounded-lg">
        {isLoading ? (
          <div className="h-[200px] animate-pulse rounded-lg bg-tm-inner" />
        ) : (
          <StockChart candles={candles.slice(-40)} height={200} />
        )}
      </div>

      <div className="grid grid-cols-2 gap-2.5">
        <Stat label={labels.high} value={fmtNum(range?.high ?? null)} sub={labels.sub} />
        <Stat label={labels.low} value={fmtNum(range?.low ?? null)} sub={labels.sub} />
        <Stat label="PER" value={quote?.per != null ? quote.per.toFixed(1) : "—"} />
        <Stat label="외국인 순매수" value="—" sub="준비 중" />
      </div>

      <div className="flex flex-col gap-0.5">
        <span className="pb-1 text-2xs text-tm-muted">최근 이벤트</span>
        {recent.length === 0 ? (
          <span className="border-t border-tm-line py-2 text-xs text-tm-muted">최근 이벤트가 없습니다.</span>
        ) : (
          recent.map((e) => (
            <div key={e.id} className="flex gap-2 border-t border-tm-line py-[7px] text-xs">
              <EventBadge type={eventLabel(e.eventType)} />
              <span className="min-w-0 flex-1 truncate">{e.title}</span>
              <span className="num text-tm-muted">{kstTime(new Date(e.time * 1000).toISOString())}</span>
            </div>
          ))
        )}
      </div>

      <BtnLink kind="soft" icon="bell" full href={`/stocks/${item.symbol}?openAlert=1`}>가격 알림 설정</BtnLink>
    </Panel>
  );
}
