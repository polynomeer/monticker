"use client";

import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import StockChart from "@/components/stock/chart/StockChart";
import type { CandleData, SignalMarker } from "@/components/stock/chart/types";
import { Icon, IconBtn, Seg, SelectBox } from "@/components/terminal";
import { advance, candleIndexAt, nextOrderIndex, stepFor } from "./replayEngine";

export interface ReplayOrder {
  time: string;
  type: string;
  stockId?: number | null;
  stockSymbol: string | null;
  stockName?: string | null;
}

const SPEEDS = [{ value: "1", label: "1×" }, { value: "4", label: "4×" }, { value: "16", label: "16×" }];

async function fetchIntraday(stockId: number, date: string): Promise<CandleData[]> {
  const r = await fetch(`/api/stocks/${stockId}/candles/intraday?date=${date}`);
  if (!r.ok) throw new Error("분봉 조회 실패");
  const raw: { time: number; open: string; high: string; low: string; close: string; volume?: number }[] = await r.json();
  return raw.map((c) => ({ time: c.time, open: +c.open, high: +c.high, low: +c.low, close: +c.close, volume: c.volume ?? 0 }));
}

const hhmm = (sec: number) => new Date(sec * 1000).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false });

/**
 * 지갑 · 주문 리플레이 — 그날 거래한 종목의 1분봉을 재생하면서 내 매수(▲)·매도(▼)가 그 시점에 나타난다.
 * 재생은 프론트에서만 한다(분봉은 `/api/stocks/{id}/candles/intraday?date=`).
 */
export default function CandleReplay({ date, events }: { date: string; events: ReplayOrder[] }) {
  const trades = useMemo(() => events.filter((e) => (e.type === "BUY" || e.type === "SELL") && e.stockId != null), [events]);
  const stocks = useMemo(() => {
    const m = new Map<number, string>();
    trades.forEach((t) => m.set(t.stockId as number, t.stockName ?? t.stockSymbol ?? `종목 #${t.stockId}`));
    return [...m.entries()];
  }, [trades]);
  const [stockId, setStockId] = useState<number | null>(null);
  const activeStock = stockId != null && stocks.some(([id]) => id === stockId) ? stockId : stocks[0]?.[0] ?? null;

  const { data: candles = [], isLoading, isError } = useQuery({
    queryKey: ["stocks", activeStock, "intraday", date],
    queryFn: () => fetchIntraday(activeStock as number, date),
    enabled: activeStock != null,
    staleTime: 5 * 60_000,
  });

  const [cursor, setCursor] = useState(-1);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState("1");

  // 종목·날짜가 바뀌면 처음부터 — 전부 보인 상태(정지)로 시작해 개요를 먼저 보여 준다
  useEffect(() => { setPlaying(false); setCursor(candles.length - 1); }, [candles]);

  useEffect(() => {
    if (!playing || candles.length === 0) return;
    const { candles: n, intervalMs } = stepFor(Number(speed));
    const id = setInterval(() => {
      setCursor((c) => {
        const next = advance(c, n, candles.length);
        if (next >= candles.length - 1) setPlaying(false);
        return next;
      });
    }, intervalMs);
    return () => clearInterval(id);
  }, [playing, speed, candles.length]);

  const orderTimes = useMemo(
    () => trades.filter((t) => t.stockId === activeStock).map((t) => Math.floor(new Date(t.time).getTime() / 1000)),
    [trades, activeStock],
  );
  const visible = useMemo(() => candles.slice(0, Math.max(0, cursor) + 1), [candles, cursor]);
  const markers: SignalMarker[] = useMemo(() => {
    const lastTime = visible[visible.length - 1]?.time ?? -Infinity;
    return trades
      .filter((t) => t.stockId === activeStock)
      .map((t, i) => ({ id: i, time: Math.floor(new Date(t.time).getTime() / 1000), direction: t.type as "BUY" | "SELL", label: "내 주문" }))
      .filter((m) => candleIndexAt(candles, m.time) <= candleIndexAt(candles, lastTime));
  }, [trades, activeStock, visible, candles]);

  if (stocks.length === 0) return null;

  const play = () => {
    if (cursor >= candles.length - 1) setCursor(0);
    setPlaying((p) => !p);
  };
  const toNextOrder = () => {
    const n = nextOrderIndex(candles, orderTimes, cursor);
    setPlaying(false);
    setCursor(n ?? candles.length - 1);
  };
  const progress = candles.length > 1 ? (Math.max(0, cursor) / (candles.length - 1)) * 100 : 0;

  return (
    <div className="flex flex-col gap-2.5">
      {stocks.length > 1 && (
        <SelectBox aria-label="재생할 종목" value={activeStock ?? ""} onChange={(e) => setStockId(Number(e.target.value))}>
          {stocks.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </SelectBox>
      )}
      {isLoading ? (
        <div className="h-[260px] animate-pulse rounded-lg bg-tm-inner" />
      ) : isError ? (
        <p role="alert" className="m-0 text-13 text-[#ff8a8a]">분봉을 불러오지 못했습니다.</p>
      ) : candles.length === 0 ? (
        <p className="m-0 rounded-lg bg-tm-inner py-8 text-center text-13 text-tm-muted">이날 이 종목의 분봉 기록이 없습니다.</p>
      ) : (
        <StockChart candles={visible} height={260} signalMarkers={markers} />
      )}
      <div className="flex flex-wrap items-center gap-2.5">
        <IconBtn name="skipb" label="처음으로" size={36} onClick={() => { setPlaying(false); setCursor(0); }} disabled={candles.length === 0} />
        <button
          type="button"
          aria-label={playing ? "일시정지" : "재생"}
          onClick={play}
          disabled={candles.length === 0}
          className="grid h-11 w-11 place-items-center rounded-full bg-dracula-purple text-tm-page disabled:opacity-50"
        >
          <Icon name={playing ? "pause" : "play"} size={18} strokeWidth={2.4} />
        </button>
        <IconBtn name="skipf" label="다음 주문으로" size={36} onClick={toNextOrder} disabled={candles.length === 0} />
        <input
          type="range"
          aria-label="재생 위치"
          min={0}
          max={Math.max(0, candles.length - 1)}
          value={Math.max(0, cursor)}
          onChange={(e) => { setPlaying(false); setCursor(Number(e.target.value)); }}
          disabled={candles.length === 0}
          className="min-w-[160px] flex-1 accent-[#bd93f9]"
          style={{ backgroundSize: `${progress}% 100%` }}
        />
        <span className="num text-xs text-tm-muted">
          {candles.length ? `${hhmm(candles[Math.max(0, cursor)].time)} / ${hhmm(candles[candles.length - 1].time)}` : "—"}
        </span>
        <Seg options={SPEEDS} value={speed} onChange={setSpeed} size="lg" />
      </div>
    </div>
  );
}
