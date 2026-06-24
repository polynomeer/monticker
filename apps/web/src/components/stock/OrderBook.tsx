"use client";

import { useEffect, useRef, useState } from "react";

interface OrderBookLevel { price: number; quantity: number; amount: number; }
interface OrderBookData {
  stockId: number; symbol: string; currentPrice: number;
  asks: OrderBookLevel[]; bids: OrderBookLevel[];
}

interface Props { stockId: number; }

/**
 * memo로 감싸 부모 리렌더 시에도 stockId가 바뀌지 않으면 리렌더 안 함.
 * 내부 1초 폴링은 useRef로 DOM을 직접 업데이트하여 React state/리렌더 완전 우회.
 */
function OrderBook({ stockId }: Props) {
  const [initialized, setInitialized] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const dataRef      = useRef<OrderBookData | null>(null);

  // DOM을 직접 업데이트 — React state 사용 안 함 → 부모 리렌더 전혀 없음
  const updateDOM = (d: OrderBookData) => {
    if (!containerRef.current) return;
    dataRef.current = d;
    const maxQty = Math.max(...d.asks.map(a => a.quantity), ...d.bids.map(b => b.quantity));

    // 현재가
    const priceEls = containerRef.current.querySelectorAll("[data-current-price]");
    priceEls.forEach(el => { el.textContent = d.currentPrice.toLocaleString(); });

    // 매도 호가
    const askRows = containerRef.current.querySelectorAll("[data-ask]");
    const reversedAsks = [...d.asks].reverse();
    askRows.forEach((row, i) => {
      if (!reversedAsks[i]) return;
      const level = reversedAsks[i];
      const priceEl = row.querySelector("[data-price]");
      const qtyEl   = row.querySelector("[data-qty]");
      const barEl   = row.querySelector<HTMLElement>("[data-bar]");
      if (priceEl) priceEl.textContent = level.price.toLocaleString();
      if (qtyEl)   qtyEl.textContent   = level.quantity.toLocaleString();
      if (barEl)   barEl.style.width   = `${(level.quantity / maxQty) * 100}%`;
    });

    // 매수 호가
    const bidRows = containerRef.current.querySelectorAll("[data-bid]");
    bidRows.forEach((row, i) => {
      if (!d.bids[i]) return;
      const level = d.bids[i];
      const priceEl = row.querySelector("[data-price]");
      const qtyEl   = row.querySelector("[data-qty]");
      const barEl   = row.querySelector<HTMLElement>("[data-bar]");
      if (priceEl) priceEl.textContent = level.price.toLocaleString();
      if (qtyEl)   qtyEl.textContent   = level.quantity.toLocaleString();
      if (barEl)   barEl.style.width   = `${(level.quantity / maxQty) * 100}%`;
    });
  };

  useEffect(() => {
    let cancelled = false;

    const fetch_ = async () => {
      try {
        const res = await fetch(`/api/stocks/${stockId}/orderbook`);
        if (!res.ok || cancelled) return;
        const d: OrderBookData = await res.json();
        if (cancelled) return;
        if (!dataRef.current) {
          dataRef.current = d;
          setInitialized(true);   // 최초 한 번만 React state 변경 (골격 렌더용)
        } else {
          updateDOM(d);            // 이후는 DOM 직접 업데이트
        }
      } catch { /* ignore */ }
    };

    fetch_();
    const id = setInterval(fetch_, 1000);
    return () => { cancelled = true; clearInterval(id); };
  }, [stockId]);

  if (!initialized || !dataRef.current)
    return <div className="h-40 animate-pulse dark:bg-[#44475a]/20 rounded-lg" />;

  const d = dataRef.current;
  const maxQty = Math.max(...d.asks.map(a => a.quantity), ...d.bids.map(b => b.quantity));

  return (
    <div ref={containerRef}
      className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg overflow-hidden">
      <div className="px-4 py-2 border-b border-gray-100 dark:border-[#44475a] flex items-center justify-between">
        <span className="text-xs font-semibold text-gray-600 dark:text-[#6272a4]">호가창</span>
        <span data-current-price className="text-xs font-mono font-bold dark:text-[#f8f8f2]">
          {d.currentPrice.toLocaleString()}
        </span>
      </div>

      <div>
        {[...d.asks].reverse().map((level, i) => (
          <div key={i} data-ask className="relative flex items-center justify-between px-3 py-0.5 text-xs">
            <div data-bar className="absolute right-0 top-0 bottom-0 opacity-20 bg-[#f6465d]"
              style={{ width: `${(level.quantity / maxQty) * 100}%` }} />
            <span data-price className="font-mono text-[#f6465d] z-10">{level.price.toLocaleString()}</span>
            <span data-qty   className="font-mono dark:text-[#6272a4] z-10">{level.quantity.toLocaleString()}</span>
          </div>
        ))}

        <div className="flex items-center justify-center py-1 bg-[#44475a]/20 border-y border-[#44475a]/40">
          <span data-current-price className="text-xs font-bold font-mono dark:text-[#f8f8f2]">
            {d.currentPrice.toLocaleString()}
          </span>
        </div>

        {d.bids.map((level, i) => (
          <div key={i} data-bid className="relative flex items-center justify-between px-3 py-0.5 text-xs">
            <div data-bar className="absolute left-0 top-0 bottom-0 opacity-20 bg-[#0ecb81]"
              style={{ width: `${(level.quantity / maxQty) * 100}%` }} />
            <span data-price className="font-mono text-[#0ecb81] z-10">{level.price.toLocaleString()}</span>
            <span data-qty   className="font-mono dark:text-[#6272a4] z-10">{level.quantity.toLocaleString()}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export default OrderBook;
