"use client";

import { fmtNum } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/portfolio/PaperStates";
import type { BookLevel, OrderBookData, OrderDto } from "./data";

/**
 * 시안 Matching "오더북 (CLOB)" — 가격 | 잔량 | 대기열.
 * 호가 API는 가격별 잔량만 주므로 대기열은 잔량 막대 하나로 그리고, 같은 가격에 걸린 내 지정가 주문을 표시한다.
 * (주문별 대기열·내 대기 순번은 호가 API에 개별 주문이 없어 준비 중)
 */
export function ClobBook({ book, loading, myOrders }: { book: OrderBookData | null | undefined; loading: boolean; myOrders: OrderDto[] }) {
  if (loading && !book) return <Skeleton className="mx-2.5 h-72" />;
  if (!book) return <p className="m-0 px-2.5 py-10 text-center text-13 text-tm-muted">호가를 불러오지 못했습니다.</p>;

  const max = Math.max(1, ...book.asks.map((a) => a.quantity), ...book.bids.map((b) => b.quantity));
  const mine = (price: number, side: "BUY" | "SELL") =>
    myOrders.find((o) => o.stockId === book.stockId && o.side === side && o.limitPrice === price && (o.status === "PENDING" || o.status === "PARTIALLY_FILLED"));
  const bestAsk = book.asks[0]?.price;
  const bestBid = book.bids[0]?.price;

  const row = (l: BookLevel, side: "a" | "b") => {
    const my = mine(l.price, side === "a" ? "SELL" : "BUY");
    const w = (l.quantity / max) * 100;
    return (
      <div key={`${side}${l.price}`} role="row" className="grid grid-cols-[70px_60px_1fr] items-center gap-2.5 px-2.5 py-[5px] text-[0.78rem]">
        <span role="cell" className={cn("num", side === "a" ? "text-down" : "text-up")}>{fmtNum(l.price)}</span>
        <span role="cell" className="num text-right">{fmtNum(l.quantity)}</span>
        <div role="cell" className="flex min-w-0 items-center gap-[3px]">
          <span className={cn("h-4 rounded-[3px] opacity-35", side === "a" ? "bg-down" : "bg-up")} style={{ width: `${Math.max(w, 4)}%` }} />
          {my && (
            <>
              <span className="h-4 w-3 flex-none rounded-[3px] bg-dracula-purple" title={`내 주문 ORD-${my.id}`} />
              <span className="ml-1.5 whitespace-nowrap text-2xs text-dracula-purple">내 주문 · {fmtNum(my.quantity - my.filledQty)}주 대기</span>
            </>
          )}
        </div>
      </div>
    );
  };

  return (
    <div role="table" aria-label="호가 대기열">
      <div role="row" className="grid grid-cols-[70px_60px_1fr] gap-2.5 px-2.5 pb-1.5 text-2xs text-tm-muted">
        <span role="columnheader">가격</span>
        <span role="columnheader" className="text-right">잔량</span>
        <span role="columnheader">대기열 (시간 우선)</span>
      </div>
      {[...book.asks].reverse().map((l) => row(l, "a"))}
      <div className="bg-tm-inner px-2.5 py-2 text-xs text-tm-muted">
        현재가 <b className="num text-dracula-fg">{fmtNum(book.currentPrice)}</b>
        {bestAsk != null && bestBid != null && <> · 스프레드 <span className="num">{fmtNum(bestAsk - bestBid)}</span>원</>}
      </div>
      {book.bids.map((l) => row(l, "b"))}
    </div>
  );
}
