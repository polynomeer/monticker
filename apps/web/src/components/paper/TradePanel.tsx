"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { usePaperTrade, usePaperPortfolio } from "@/hooks/usePaperTrade";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import TradeReceipt from "@/components/wallet/TradeReceipt";

interface Stock { id: number; symbol: string; name: string; }
interface Props {
  stock: Stock;
  currentPrice: number;
}

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

/**
 * 상시 노출되는 모의투자 주문 패널 (StockDetailClient 전용).
 * TradeModal과 같은 훅(usePaperTrade/usePaperPortfolio)을 쓰지만,
 * 모달 팝업이 아니라 항상 화면에 떠 있는 QFEX/Toss 스타일 주문 패널로 렌더링한다.
 * 시장가만 지원한다 — 모의투자 API(usePaperTrade)가 지정가를 받지 않는다.
 */
export default function TradePanel({ stock, currentPrice }: Props) {
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [quantity, setQuantity] = useState(1);
  const [error, setError] = useState("");
  const [receiptTradeId, setReceiptTradeId] = useState<number | null>(null);
  const { buy, sell } = usePaperTrade();
  const { data: portfolio } = usePaperPortfolio();
  const isLoggedIn = !!getAccessToken();

  const isBuy = side === "BUY";
  const totalAmount = quantity * currentPrice;
  const cash = portfolio?.cash ?? 0;
  const holding = portfolio?.holdings.find(h => h.stockId === stock.id);
  const ownedQty = holding?.quantity ?? 0;
  const maxBuy = currentPrice > 0 ? Math.floor(cash / currentPrice) : 0;
  const max = isBuy ? maxBuy : ownedQty;
  const isValid = quantity > 0 && quantity <= max;
  const isPending = buy.isPending || sell.isPending;
  const accentColor = isBuy ? "#ff5050" : "#4a8fd4";

  const { data: receipt } = useQuery({
    queryKey: ["receipt", receiptTradeId],
    queryFn: async () => {
      const res = await authFetch(`/api/paper/trades/${receiptTradeId}/receipt`);
      if (!res.ok) return null;
      return res.json();
    },
    enabled: receiptTradeId != null,
  });

  const handleSubmit = async () => {
    setError("");
    try {
      let result: { tradeId?: number; id?: number } = {};
      if (isBuy) result = await buy.mutateAsync({ stockId: stock.id, quantity });
      else result = await sell.mutateAsync({ stockId: stock.id, quantity });
      const tid = result?.tradeId ?? result?.id;
      if (tid) { setReceiptTradeId(tid); setQuantity(1); }
    } catch (e: unknown) {
      setError((e as Error).message);
    }
  };

  if (receiptTradeId && receipt) {
    return <TradeReceipt receipt={receipt} onClose={() => setReceiptTradeId(null)} />;
  }

  if (!isLoggedIn) {
    return (
      <div className="flex flex-1 min-h-0 flex-col items-center justify-center gap-1.5 px-4 text-center">
        <p className="text-sm font-medium text-gray-900 dark:text-dracula-fg">로그인하고 모의투자를 시작하세요</p>
        <p className="text-xs text-gray-500 dark:text-dracula-comment">가상 자금으로 매매 연습을 해볼 수 있어요</p>
      </div>
    );
  }

  return (
    <div className="flex flex-1 min-h-0 flex-col gap-2.5 overflow-y-auto px-4 py-3">
      <div className="flex gap-1.5">
        <button
          onClick={() => setSide("BUY")}
          className="flex-1 rounded-lg py-2 text-sm font-extrabold transition-all duration-150 active:scale-[0.98]"
          style={{
            backgroundColor: isBuy ? "#ff5050" : "transparent",
            color: isBuy ? "#ffffff" : "#6272a4",
            border: isBuy ? "none" : "1px solid #44475a",
          }}
        >매수</button>
        <button
          onClick={() => setSide("SELL")}
          className="flex-1 rounded-lg py-2 text-sm font-extrabold transition-all duration-150 active:scale-[0.98]"
          style={{
            backgroundColor: !isBuy ? "#4a8fd4" : "transparent",
            color: !isBuy ? "#ffffff" : "#6272a4",
            border: !isBuy ? "none" : "1px solid #44475a",
          }}
        >매도</button>
      </div>

      <div>
        <label className="mb-1 block text-xs text-gray-500 dark:text-dracula-comment">수량 (최대 {max}주)</label>
        <div className="flex items-center gap-1.5">
          <button onClick={() => setQuantity(q => Math.max(1, q - 1))}
            className="h-8 w-8 shrink-0 rounded-lg bg-gray-100 dark:bg-dracula-line font-bold text-gray-900 dark:text-dracula-fg hover:opacity-80 active:scale-95 transition-all duration-150">−</button>
          <input type="number" min={1} max={max} value={quantity}
            onChange={e => setQuantity(Math.min(Math.max(max, 1), Math.max(1, Number(e.target.value))))}
            className="w-full flex-1 rounded-lg border border-gray-300 dark:border-dracula-line bg-white dark:bg-dracula-surface py-1.5 text-center font-mono text-sm font-bold text-gray-900 dark:text-dracula-fg focus:outline-none focus:border-dracula-purple" />
          <button onClick={() => setQuantity(q => Math.min(Math.max(max, 1), q + 1))}
            className="h-8 w-8 shrink-0 rounded-lg bg-gray-100 dark:bg-dracula-line font-bold text-gray-900 dark:text-dracula-fg hover:opacity-80 active:scale-95 transition-all duration-150">+</button>
        </div>
        <div className="mt-1.5 flex gap-1.5">
          {[25, 50, 75, 100].map(pct => (
            <button key={pct}
              onClick={() => setQuantity(Math.max(1, Math.floor(max * pct / 100)))}
              disabled={max <= 0}
              className="flex-1 rounded-md border border-gray-200 dark:border-dracula-line py-1 text-[11px] font-semibold text-gray-500 dark:text-dracula-comment hover:border-gray-400 dark:hover:border-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg active:scale-95 transition-all duration-150 disabled:opacity-40 disabled:active:scale-100">
              {pct}%
            </button>
          ))}
        </div>
      </div>

      <div className="h-px bg-gray-100 dark:bg-white/5" />

      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-500 dark:text-dracula-comment">주문 가능 금액</span>
        <span className="font-mono text-sm font-semibold tabular-nums text-gray-900 dark:text-dracula-fg">₩{fmt(cash)}</span>
      </div>
      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-500 dark:text-dracula-comment">예상 {isBuy ? "매수" : "매도"} 금액</span>
        <span className="font-mono text-sm font-bold tabular-nums" style={{ color: accentColor }}>₩{fmt(totalAmount)}</span>
      </div>

      <div className="h-px bg-gray-100 dark:bg-white/5" />

      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-500 dark:text-dracula-comment">보유 수량</span>
        <span className="font-mono text-sm font-semibold tabular-nums text-gray-900 dark:text-dracula-fg">
          {holding ? `${fmt(holding.quantity)}주 · 평균 ${fmt(holding.avgPrice)}원` : "-"}
        </span>
      </div>
      {holding && (
        <div className="flex items-center justify-between">
          <span className="text-xs text-gray-500 dark:text-dracula-comment">평가 손익</span>
          <span className={`inline-flex items-center rounded px-2 py-0.5 font-mono text-xs font-semibold tabular-nums ${
            holding.pnl >= 0
              ? "bg-market-up/15 text-market-up"
              : "bg-market-down/15 text-market-down"
          }`}>
            {holding.pnl >= 0 ? "+" : ""}{fmt(holding.pnl)}원 ({holding.pnlRate >= 0 ? "+" : ""}{holding.pnlRate.toFixed(2)}%)
          </span>
        </div>
      )}

      {error && <p className="text-xs text-market-down">{error}</p>}
      {quantity > max && (
        <p className="text-xs text-market-down">
          {isBuy ? `잔고 부족 (최대 ${max}주 가능)` : `보유 수량 초과 (최대 ${max}주)`}
        </p>
      )}

      <div className="flex-1" />

      <button onClick={handleSubmit} disabled={!isValid || isPending}
        className="w-full rounded-xl py-2.5 text-sm font-extrabold text-white transition-all duration-150 active:scale-[0.98] disabled:opacity-40 disabled:active:scale-100"
        style={{ backgroundColor: isValid ? accentColor : undefined }}>
        {isPending ? "처리 중..." : `${fmt(currentPrice)}원에 ${isBuy ? "매수하기" : "매도하기"}`}
      </button>
    </div>
  );
}
