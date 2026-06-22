"use client";

import { useEffect, useState } from "react";
import { useStockPrice } from "@/hooks/useStockPrice";

interface Props {
  symbol: string;
}

function ConnectionBadge({ state }: { state: "connecting" | "connected" | "disconnected" }) {
  if (state === "connecting") {
    return (
      <span className="flex items-center gap-1 text-xs text-gray-400">
        <span className="inline-block w-2 h-2 rounded-full bg-gray-400" />
        연결 중...
      </span>
    );
  }
  if (state === "connected") {
    return (
      <span className="flex items-center gap-1 text-xs text-green-600">
        <span className="inline-block w-2 h-2 rounded-full bg-green-500" />
        실시간
      </span>
    );
  }
  return (
    <span className="flex items-center gap-1 text-xs text-red-500">
      <span className="inline-block w-2 h-2 rounded-full bg-red-500" />
      연결 끊김
    </span>
  );
}

export default function PriceCard({ symbol }: Props) {
  const [stockId, setStockId] = useState<number | null>(null);
  const [resolving, setResolving] = useState(true);

  useEffect(() => {
    fetch(`/api/stocks/search?query=${encodeURIComponent(symbol)}`)
      .then(r => r.json())
      .then((stocks: { id: number; symbol: string }[]) => {
        const match = stocks.find(s => s.symbol === symbol);
        if (match) setStockId(match.id);
      })
      .catch(() => {})
      .finally(() => setResolving(false));
  }, [symbol]);

  if (resolving) {
    return (
      <div className="border border-gray-200 rounded-lg p-4 animate-pulse">
        <div className="h-8 bg-gray-100 rounded w-32 mb-2" />
        <div className="h-4 bg-gray-100 rounded w-24" />
      </div>
    );
  }

  if (stockId === null) {
    return (
      <div className="border border-gray-200 rounded-lg p-4 text-gray-400">
        시세 데이터 없음 (워커가 실행 중이어야 합니다)
      </div>
    );
  }

  return <PriceCardInner symbol={symbol} stockId={stockId} />;
}

function PriceCardInner({ symbol, stockId }: { symbol: string; stockId: number }) {
  const { price, state } = useStockPrice(stockId);

  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <div className="flex items-center justify-between mb-2">
        <span className="text-sm text-gray-500">{symbol}</span>
        <ConnectionBadge state={state} />
      </div>
      {price ? (
        <>
          <div className="text-3xl font-bold">
            {price.price.toLocaleString()}
          </div>
          {price.volume && (
            <p className="text-sm text-gray-500 mt-1">
              거래량 {price.volume.toLocaleString()}
            </p>
          )}
          {price.timestamp && (
            <p className="text-xs text-gray-400 mt-1">
              {new Date(price.timestamp).toLocaleTimeString("ko-KR")}
            </p>
          )}
        </>
      ) : (
        <div className="text-gray-400 text-sm mt-1">가격 로딩 중...</div>
      )}
    </div>
  );
}
