"use client";

import { memo } from "react";
import { useQuery } from "@tanstack/react-query";
import { stockKeys } from "@/hooks/useStockChart";
import { cn } from "@/lib/utils";

interface OrderBookLevel { price: number; quantity: number; amount: number; }
interface OrderBookData {
  stockId: number; symbol: string; currentPrice: number;
  asks: OrderBookLevel[]; bids: OrderBookLevel[];
}

interface Props {
  stockId: number;
  /** 전일 종가 — 주면 각 호가의 등락률 열을 채운다 */
  prevClose?: number | null;
}

const fmt = (n: number) => n.toLocaleString("ko-KR");

/** 같은 가격 호가를 하나로 합친다 — 공급원이 중복 레벨을 보내도 행 key가 겹치지 않게(React key 중복 경고·행 누락 방지). */
export function mergeLevels(levels: OrderBookLevel[]): OrderBookLevel[] {
  const byPrice = new Map<number, OrderBookLevel>();
  for (const l of levels) {
    const prev = byPrice.get(l.price);
    byPrice.set(l.price, prev ? { price: l.price, quantity: prev.quantity + l.quantity, amount: prev.amount + l.amount } : { ...l });
  }
  return [...byPrice.values()];
}

/**
 * 호가 조회(1초 폴링). 호가 패널과 "호가 깊이" 탭이 같은 쿼리 키를 써서 요청은 하나만 나간다.
 */
export function useOrderBook(stockId: number, enabled = true) {
  return useQuery<OrderBookData | null>({
    queryKey: stockKeys.orderbook(stockId),
    queryFn: async () => {
      const res = await fetch(`/api/stocks/${stockId}/orderbook`);
      if (!res.ok) return null;
      const raw: OrderBookData = await res.json();
      return { ...raw, asks: mergeLevels(raw.asks), bids: mergeLevels(raw.bids) };
    },
    refetchInterval: 1000,
    staleTime: 1000,
    enabled,
  });
}

/**
 * 시안의 호가 패널 본문 — 매도 호가(위, 하락색) · 현재가 줄 · 매수 호가(아래, 상승색) · 잔량 합계 막대.
 * 1초 폴링. 호가 데이터만 바뀌므로 이 컴포넌트 안에서만 다시 그려진다(부모는 리렌더되지 않는다).
 */
function OrderBook({ stockId, prevClose }: Props) {
  const { data: d, isLoading } = useOrderBook(stockId);

  if (isLoading) {
    return <div className="mx-2.5 h-72 animate-pulse rounded-lg bg-tm-inner" aria-busy="true" aria-label="호가 불러오는 중" />;
  }
  if (!d || (d.asks.length === 0 && d.bids.length === 0)) {
    return <p className="m-0 px-2.5 py-10 text-center text-13 text-tm-muted">호가 데이터가 없습니다.</p>;
  }

  const maxQty = Math.max(1, ...d.asks.map((a) => a.quantity), ...d.bids.map((b) => b.quantity));
  const askTotal = d.asks.reduce((s, a) => s + a.quantity, 0);
  const bidTotal = d.bids.reduce((s, b) => s + b.quantity, 0);
  const total = askTotal + bidTotal || 1;
  const bestAsk = d.asks.length ? Math.min(...d.asks.map((a) => a.price)) : null;
  const bestBid = d.bids.length ? Math.max(...d.bids.map((b) => b.price)) : null;
  const spread = bestAsk != null && bestBid != null ? bestAsk - bestBid : null;
  const chg = (p: number) => (prevClose ? `${((p - prevClose) / prevClose) * 100 >= 0 ? "+" : ""}${(((p - prevClose) / prevClose) * 100).toFixed(2)}%` : "—");
  const curUp = prevClose == null || d.currentPrice >= prevClose;

  const Row = ({ level, side }: { level: OrderBookLevel; side: "ask" | "bid" }) => (
    <div className="relative grid grid-cols-3 px-2.5 py-[3px] text-[0.78125rem]">
      <span
        aria-hidden
        className={cn("absolute bottom-px right-0 top-px opacity-[.14]", side === "ask" ? "bg-down" : "bg-up")}
        style={{ width: `${Math.round((level.quantity / maxQty) * 100)}%` }}
      />
      <span className={cn("num relative", side === "ask" ? "text-down" : "text-up")}>{fmt(level.price)}</span>
      <span className="num relative text-right">{fmt(level.quantity)}</span>
      <span className="num relative text-right text-tm-muted">{chg(level.price)}</span>
    </div>
  );

  return (
    <div className="flex flex-col">
      <div className="grid grid-cols-3 px-2.5 pb-1.5 text-2xs text-tm-muted" aria-hidden>
        <span>호가</span>
        <span className="text-right">잔량</span>
        <span className="text-right">등락</span>
      </div>
      <div aria-label="호가 목록">
        {[...d.asks].sort((a, b) => b.price - a.price).map((l) => (
          <Row key={`a${l.price}`} level={l} side="ask" />
        ))}
        <div className="my-1 flex items-center justify-between bg-tm-inner px-2.5 py-2 text-xs">
          <span className={cn("num text-base font-semibold", curUp ? "text-up" : "text-down")}>
            {fmt(d.currentPrice)} {curUp ? "▲" : "▼"}
          </span>
          <span className="text-tm-muted">{spread != null ? <>스프레드 <span className="num">{fmt(spread)}</span>원</> : "—"}</span>
        </div>
        {[...d.bids].sort((a, b) => b.price - a.price).map((l) => (
          <Row key={`b${l.price}`} level={l} side="bid" />
        ))}
      </div>
      <div className="flex flex-col gap-1.5 px-2.5 pt-2.5">
        <div className="flex justify-between text-2xs text-tm-muted">
          <span>매도 잔량 <b className="num text-down">{fmt(askTotal)}</b></span>
          <span>매수 잔량 <b className="num text-up">{fmt(bidTotal)}</b></span>
        </div>
        <div className="flex h-1.5 overflow-hidden rounded-full" aria-hidden>
          <div className="bg-down" style={{ width: `${(askTotal / total) * 100}%` }} />
          <div className="bg-up" style={{ width: `${(bidTotal / total) * 100}%` }} />
        </div>
      </div>
    </div>
  );
}

export default memo(OrderBook);
