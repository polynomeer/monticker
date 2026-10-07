"use client";

import { useMemo } from "react";
import type { Holding } from "@/hooks/usePaperTrade";
import { Donut, Notice, dirClass, fmtSigned } from "@/components/terminal";
import StockChart from "@/components/stock/chart/StockChart";
import type { EventMarker, IndicatorKey, OrderLine, TradeMarker } from "@/components/stock/chart/types";
import { bucketOf } from "@/components/stock/chart/tradeMarkers";
import { useStockChart } from "@/hooks/useStockChart";
import { usePaperFills } from "@/hooks/usePaperFills";
import type { TradeHistory } from "@/hooks/usePaperTrade";
import type { StockMeta } from "./useStockMeta";
import { EmptyNote, Skeleton } from "./PaperStates";

// ── 손익 기여도 ─────────────────────────────────────────────────────────
/** 종목별 평가손익을 0을 중심으로 좌(손실)·우(이익) 막대로 나눈다. */
export function PnlContribution({ holdings }: { holdings: Holding[] }) {
  if (holdings.length === 0) return <EmptyNote>보유 종목이 없습니다.</EmptyNote>;
  const rows = [...holdings].sort((a, b) => b.pnl - a.pnl);
  const max = Math.max(...rows.map((h) => Math.abs(h.pnl)), 1);
  const sum = rows.reduce((a, h) => a + h.pnl, 0);
  return (
    <>
      <ul className="m-0 flex list-none flex-col gap-3 p-0" aria-label="종목별 손익 기여도">
        {rows.map((h) => {
          const w = (Math.abs(h.pnl) / max) * 100;
          return (
            <li key={h.stockId} className="grid grid-cols-[90px_1fr_1fr_80px] items-center gap-2 text-xs">
              <span className="truncate text-tm-soft">{h.name}</span>
              <div className="flex justify-end">{h.pnl < 0 && <div className="h-3.5 rounded-l-[3px] bg-down" style={{ width: `${w}%` }} />}</div>
              <div>{h.pnl > 0 && <div className="h-3.5 rounded-r-[3px] bg-up" style={{ width: `${w}%` }} />}</div>
              <span className={`num text-right ${dirClass(h.pnl)}`}>{fmtSigned(h.pnl)}</span>
            </li>
          );
        })}
      </ul>
      <span className="text-2xs text-tm-muted">평가손익 {fmtSigned(sum)}원을 종목별로 분해</span>
    </>
  );
}

// ── 위험 집중도 ─────────────────────────────────────────────────────────
const SECTOR_COLORS = ["#bd93f9", "#8be9fd", "#ffb86c", "#ff79c6", "#50fa7b", "#f1fa8c"];
const CASH_COLOR = "#44475a";

export interface SectorSlice { name: string; value: number; pct: number; color: string; }

/** 섹터별 평가액(+현금) 비중. 섹터를 모르는 종목은 "미분류"로 묶는다. */
export function useSectorSlices(holdings: Holding[], cash: number, meta: Map<number, StockMeta>) {
  return useMemo(() => {
    const by = new Map<string, number>();
    holdings.forEach((h) => {
      const s = meta.get(h.stockId)?.sector || "미분류";
      by.set(s, (by.get(s) ?? 0) + h.value);
    });
    const stockTotal = holdings.reduce((a, h) => a + h.value, 0);
    const total = stockTotal + Math.max(cash, 0) || 1;
    const sectors = [...by.entries()].sort((a, b) => b[1] - a[1]);
    const slices: SectorSlice[] = sectors.map(([name, value], i) => ({ name, value, pct: (value / total) * 100, color: SECTOR_COLORS[i % SECTOR_COLORS.length] }));
    if (cash > 0) slices.push({ name: "현금", value: cash, pct: (cash / total) * 100, color: CASH_COLOR });
    const top = sectors[0];
    const topShareOfStocks = top && stockTotal > 0 ? (top[1] / stockTotal) * 100 : 0;
    return { slices, top: top?.[0] ?? null, topShareOfStocks, stockTotal };
  }, [holdings, cash, meta]);
}

export function SectorConcentration({ holdings, cash, meta }: { holdings: Holding[]; cash: number; meta: Map<number, StockMeta> }) {
  const { slices, top, topShareOfStocks } = useSectorSlices(holdings, cash, meta);
  if (slices.length === 0) return <EmptyNote>보유 자산이 없습니다.</EmptyNote>;
  const topSlice = slices.find((s) => s.name === top);
  return (
    <>
      <div className="flex flex-wrap items-center gap-[18px]">
        <Donut
          parts={slices.map((s) => ({ value: s.value, color: s.color }))}
          center={topSlice ? [`${topSlice.pct.toFixed(1)}%`, topSlice.name] : ["—", ""]}
          label="섹터별 자산 비중 도넛 차트"
        />
        <ul className="m-0 flex min-w-[150px] flex-1 list-none flex-col gap-2 p-0">
          {slices.map((s) => (
            <li key={s.name} className="flex justify-between text-xs">
              <span className="flex items-center gap-2">
                <span className="h-2.5 w-2.5 rounded-[3px]" style={{ background: s.color }} />
                {s.name}
              </span>
              <span className="num">{s.pct.toFixed(1)}%</span>
            </li>
          ))}
        </ul>
      </div>
      {top && top !== "미분류" && topShareOfStocks >= 50 && (
        <Notice tone="warn" icon="alert">
          {top} 섹터가 주식 비중의 {topShareOfStocks.toFixed(0)}%입니다. 한 섹터 이벤트에 계좌 전체가 크게 흔들릴 수 있어요.
        </Notice>
      )}
    </>
  );
}

// ── 평균단가 오버레이 ────────────────────────────────────────────────────
const NO_EVENTS: EventMarker[] = [];
const NO_INDICATORS: IndicatorKey[] = [];

/** 한 종목의 모의 체결을 차트 거래 마커로 바꾼다. 시각을 읽을 수 없는 행은 버린다. */
export function fillsToTradeMarkers(fills: TradeHistory[], stockId: number): TradeMarker[] {
  return fills
    .filter((f) => f.stockId === stockId && (f.side === "BUY" || f.side === "SELL"))
    .map((f) => ({ id: f.id, time: Math.floor(Date.parse(f.tradedAt) / 1000), side: f.side as "BUY" | "SELL", price: Number(f.price), qty: Number(f.quantity) }))
    .filter((t) => Number.isFinite(t.time));
}

/** 보유 종목 일봉 위에 내 평균단가를 가로선으로, 내 모의 체결을 매수▲·매도▼ 마커로 겹친다. */
export function AvgPriceOverlay({ holding }: { holding: Holding | null }) {
  const { candles, loading } = useStockChart(holding?.stockId ?? null, "1d");
  const { data: fillData } = usePaperFills(!!holding);
  const stockId = holding?.stockId;
  const trades = useMemo(
    () => (fillData && stockId != null ? fillsToTradeMarkers(fillData.fills, stockId) : []),
    [fillData, stockId],
  );
  const firstCandle = candles[0]?.time;
  const outOfRange = firstCandle == null ? 0 : trades.filter((t) => bucketOf(t.time, "1d") < bucketOf(firstCandle, "1d")).length;
  const avg = holding?.avgPrice;
  // 포트폴리오는 5초마다 새 객체로 오므로 평균단가 값이 바뀔 때만 선을 새로 만든다(차트 재생성 방지)
  const lines: OrderLine[] = useMemo(
    () => (avg != null ? [{ id: -1, price: avg, side: "BUY", label: `내 평균단가 ${Math.round(avg).toLocaleString("ko-KR")}` }] : []),
    [avg],
  );
  if (!holding) return <EmptyNote>보유 종목을 고르면 평균단가선을 겹쳐 보여 줍니다.</EmptyNote>;
  if (loading && candles.length === 0) return <Skeleton className="h-[230px]" />;
  return (
    <>
      <div className="overflow-hidden rounded-lg">
        <StockChart candles={candles} events={NO_EVENTS} height={230} orderLines={lines} trades={trades} interval="1d" enabledIndicators={NO_INDICATORS} />
      </div>
      {(outOfRange > 0 || fillData?.truncated) && (
        <span className="text-2xs text-tm-muted">
          {outOfRange > 0 && `차트 구간 이전 체결 ${outOfRange}건은 표시하지 않음`}
          {outOfRange > 0 && fillData?.truncated && " · "}
          {fillData?.truncated && "최근 500건 체결까지만 표시"}
        </span>
      )}
    </>
  );
}
