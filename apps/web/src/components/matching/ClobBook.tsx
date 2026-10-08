"use client";

import { fmtNum } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { Skeleton } from "@/components/portfolio/PaperStates";
import { QUEUE_DEFINITION, queueLevelAt, type BookLevel, type OrderBookData, type OrderDto, type OrderQueueSnapshot, type QueueLevel } from "./data";

const sideLabel = (side: string) => (side === "BUY" ? "매수" : "매도");

/** 가격 하나의 모의 지정가 대기열 — 접수 순 조각. 남의 조각은 건수·잔량 합, 내 조각은 보라색. */
function QueueSlices({ level, maxQty }: { level: QueueLevel; maxQty: number }) {
  return (
    <span className="flex min-w-0 items-center gap-px" aria-label={`모의 대기 ${level.orderCount}건 ${fmtNum(level.quantity)}주`}>
      {level.slices.map((s) => (
        <span
          key={`${s.startPosition}`}
          className={cn("h-4 flex-none rounded-[2px]", s.mine ? "bg-dracula-purple" : "bg-tm-line2")}
          style={{ width: `${Math.max((s.quantity / maxQty) * 60, 3)}%` }}
          title={s.mine ? `내 주문 ORD-${s.orderId} · ${s.startPosition}번째 · ${fmtNum(s.quantity)}주` : `다른 주문 ${s.orderCount}건 · ${fmtNum(s.quantity)}주`}
        />
      ))}
    </span>
  );
}

/**
 * 시안 Matching "오더북 (CLOB)" — 가격 | 잔량 | 대기열.
 * 가격·잔량 막대는 시장 호가, 대기열 조각은 같은 가격에 걸린 모의 지정가(ADR-096): 남의 주문은 이어진 구간의 건수·잔량만,
 * 내 주문은 한 건씩 보라색으로 그리고 "대기 N번째"를 붙인다. 대기열을 못 불러오면 예전처럼 내 잔량만 표시한다.
 */
export function ClobBook({
  book, loading, myOrders, queue,
}: { book: OrderBookData | null | undefined; loading: boolean; myOrders: OrderDto[]; queue?: OrderQueueSnapshot | null }) {
  if (loading && !book) return <Skeleton className="mx-2.5 h-72" />;
  if (!book) return <p className="m-0 px-2.5 py-10 text-center text-13 text-tm-muted">호가를 불러오지 못했습니다.</p>;

  const max = Math.max(1, ...book.asks.map((a) => a.quantity), ...book.bids.map((b) => b.quantity));
  const maxQueueQty = Math.max(1, ...(queue?.bids ?? []).map((l) => l.quantity), ...(queue?.asks ?? []).map((l) => l.quantity));
  const mine = (price: number, side: "BUY" | "SELL") =>
    myOrders.find((o) => o.stockId === book.stockId && o.side === side && o.limitPrice === price && (o.status === "PENDING" || o.status === "PARTIALLY_FILLED"));
  const bestAsk = book.asks[0]?.price;
  const bestBid = book.bids[0]?.price;

  const sameStock = queue?.stockId === book.stockId ? queue : null;
  const shown = new Set([...book.asks.map((l) => `SELL:${l.price}`), ...book.bids.map((l) => `BUY:${l.price}`)]);
  const offBook = (sameStock?.mine ?? []).filter((m) => !shown.has(`${m.side}:${m.price}`));

  const row = (l: BookLevel, side: "a" | "b") => {
    const s = side === "a" ? "SELL" : "BUY";
    const level = queueLevelAt(sameStock, s, l.price);
    const positions = (sameStock?.mine ?? []).filter((m) => m.side === s && m.price === l.price);
    const my = positions.length === 0 ? mine(l.price, s) : undefined;
    const w = (l.quantity / max) * 100;
    return (
      <div key={`${side}${l.price}`} role="row" className="grid grid-cols-[70px_60px_1fr] items-center gap-2.5 px-2.5 py-[5px] text-[0.78rem]">
        <span role="cell" className={cn("num", side === "a" ? "text-down" : "text-up")}>{fmtNum(l.price)}</span>
        <span role="cell" className="num text-right">{fmtNum(l.quantity)}</span>
        <div role="cell" className="flex min-w-0 items-center gap-[3px]">
          <span className={cn("h-4 flex-none rounded-[3px] opacity-35", side === "a" ? "bg-down" : "bg-up")} style={{ width: `${Math.max(level ? w * 0.4 : w, 4)}%` }} />
          {level && <QueueSlices level={level} maxQty={maxQueueQty} />}
          {positions.length > 0 && (
            <span className="ml-1.5 whitespace-nowrap text-2xs text-dracula-purple" title={QUEUE_DEFINITION}>
              내 주문 · 대기 {positions[0].position}번째{positions.length > 1 ? ` 외 ${positions.length - 1}건` : ""}
            </span>
          )}
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
        <span role="columnheader" title={QUEUE_DEFINITION}>대기열 (모의 지정가 · 접수 순)</span>
      </div>
      {[...book.asks].reverse().map((l) => row(l, "a"))}
      <div className="bg-tm-inner px-2.5 py-2 text-xs text-tm-muted">
        현재가 <b className="num text-dracula-fg">{fmtNum(book.currentPrice)}</b>
        {bestAsk != null && bestBid != null && <> · 스프레드 <span className="num">{fmtNum(bestAsk - bestBid)}</span>원</>}
      </div>
      {book.bids.map((l) => row(l, "b"))}
      {offBook.length > 0 && (
        <ul className="m-0 mt-1.5 list-none border-t border-tm-line px-2.5 pt-1.5 text-2xs text-tm-muted" aria-label="호가 범위 밖 내 주문">
          {offBook.map((m) => (
            <li key={m.orderId} className="flex justify-between gap-2 py-0.5">
              <span>{sideLabel(m.side)} <span className="num">{fmtNum(m.price)}</span> · {fmtNum(m.remainingQuantity)}주</span>
              <span className="text-dracula-purple">대기 {m.position}번째 / {m.levelOrderCount}건</span>
            </li>
          ))}
        </ul>
      )}
      {sameStock && <p className="m-0 px-2.5 pt-1.5 text-2xs text-tm-muted">{QUEUE_DEFINITION}</p>}
    </div>
  );
}
