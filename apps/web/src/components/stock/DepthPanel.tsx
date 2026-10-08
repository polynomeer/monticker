"use client";

import { memo } from "react";
import DepthChart from "./chart/DepthChart";
import { useOrderBook } from "./OrderBook";

const fmt = (n: number) => n.toLocaleString("ko-KR");

/**
 * 이벤트 패널의 "호가 깊이" 탭 — 호가 패널과 같은 호가(같은 쿼리 키, 1초 폴링)를 누적해 그린다. 백엔드 추가 없음.
 * 호가는 최우선 10단계 안팎이라 시장 전체 깊이가 아니라 "보이는 호가"의 누적이다.
 */
function DepthPanel({ stockId, domestic }: { stockId: number; domestic: boolean }) {
  const { data: d, isLoading } = useOrderBook(stockId);

  if (isLoading) {
    return <div className="h-[220px] animate-pulse rounded-lg bg-tm-inner" aria-busy="true" aria-label="호가 깊이 불러오는 중" />;
  }
  const bids = d?.bids ?? [];
  const asks = d?.asks ?? [];
  const bidTotal = bids.reduce((s, l) => s + l.quantity, 0);
  const askTotal = asks.reduce((s, l) => s + l.quantity, 0);

  return (
    <div className="flex flex-col gap-2 pt-1.5">
      <DepthChart bids={bids} asks={asks} height={220} priceDigits={domestic ? 0 : 2} />
      {(bids.length > 0 || asks.length > 0) && (
        <div className="flex justify-between text-2xs text-tm-muted">
          <span>매수 누적 <b className="num text-up">{fmt(bidTotal)}</b>주 · {bids.length}단계</span>
          <span>매도 누적 <b className="num text-down">{fmt(askTotal)}</b>주 · {asks.length}단계</span>
        </div>
      )}
    </div>
  );
}

export default memo(DepthPanel);
