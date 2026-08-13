"use client";

import { useState, useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { usePaperTrade, usePaperPortfolio } from "@/hooks/usePaperTrade";
import TradeReceipt from "@/components/wallet/TradeReceipt";
import { authFetch } from "@/services/api";

interface Stock { id: number; symbol: string; name: string; }
interface Props {
  stock: Stock;
  currentPrice: number;
  side: "BUY" | "SELL";
  maxQuantity?: number;
  onClose: () => void;
}

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

export default function TradeModal({ stock, currentPrice, side, maxQuantity, onClose }: Props) {
  const [quantity, setQuantity] = useState(1);
  const [error, setError] = useState("");
  const [receiptTradeId, setReceiptTradeId] = useState<number | null>(null);
  const { buy, sell } = usePaperTrade();
  const { data: portfolio } = usePaperPortfolio();

  const isBuy = side === "BUY";
  const totalAmount = quantity * currentPrice;
  const cash = portfolio?.cash ?? 0;
  const maxBuy = currentPrice > 0 ? Math.floor(cash / currentPrice) : 0;
  const max = isBuy ? maxBuy : (maxQuantity ?? 0);
  const isValid = quantity > 0 && quantity <= max;
  const isPending = buy.isPending || sell.isPending;

  // 영수증 데이터 조회
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
      if (isBuy) {
        result = await buy.mutateAsync({ stockId: stock.id, quantity });
      } else {
        result = await sell.mutateAsync({ stockId: stock.id, quantity });
      }
      // 영수증 표시 (tradeId 또는 id 필드 사용)
      const tid = result?.tradeId ?? result?.id;
      if (tid) setReceiptTradeId(tid);
      else onClose();
    } catch (e: unknown) {
      setError((e as Error).message);
    }
  };

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === "Escape" && !receiptTradeId) onClose(); };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [onClose, receiptTradeId]);

  // 영수증 표시 중
  if (receiptTradeId && receipt) {
    return <TradeReceipt receipt={receipt} onClose={onClose} />;
  }
  // 영수증 로딩 중 (tradeId는 있는데 receipt 아직 없음)
  if (receiptTradeId && !receipt) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
        <div className="bg-white dark:bg-[#21222c] rounded-2xl p-8 text-center shadow-2xl animate-fade-up">
          <div className="text-2xl mb-2">✅</div>
          <p className="text-gray-900 dark:text-[#f8f8f2] font-semibold">체결 완료</p>
          <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-1">영수증 생성 중...</p>
        </div>
      </div>
    );
  }

  const accentColor = isBuy ? "#ff5050" : "#4a8fd4";

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="bg-white dark:bg-[#282a36] border border-gray-200 dark:border-[#44475a] rounded-2xl
                      w-full max-w-sm mx-4 p-6 shadow-2xl animate-fade-up">

        {/* 헤더 */}
        <div className="flex items-center justify-between mb-5">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold px-2 py-0.5 rounded"
                style={{ backgroundColor: `${accentColor}20`, color: accentColor }}>
                {isBuy ? "매수" : "매도"}
              </span>
              <span className="font-bold text-gray-900 dark:text-[#f8f8f2]">{stock.name}</span>
            </div>
            <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">{stock.symbol}</p>
          </div>
          <button onClick={onClose} className="text-gray-400 dark:text-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2] text-lg transition-colors">✕</button>
        </div>

        {/* 현재가 */}
        <div className="bg-gray-50 dark:bg-[#44475a]/20 rounded-lg p-3 mb-4">
          <div className="flex justify-between text-sm">
            <span className="text-gray-500 dark:text-[#6272a4]">현재가</span>
            <span className="font-mono font-bold text-gray-900 dark:text-[#f8f8f2]">₩{fmt(currentPrice)}</span>
          </div>
          {isBuy && (
            <div className="flex justify-between text-xs mt-1">
              <span className="text-gray-500 dark:text-[#6272a4]">가용 현금</span>
              <span className="font-mono text-gray-900 dark:text-[#f8f8f2]">₩{fmt(cash)}</span>
            </div>
          )}
        </div>

        {/* 수량 입력 */}
        <div className="mb-4">
          <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">수량</label>
          <div className="flex items-center gap-2">
            <button onClick={() => setQuantity(q => Math.max(1, q - 1))}
              className="w-9 h-9 rounded-lg bg-gray-100 dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] font-bold text-lg hover:opacity-80 active:scale-95 transition-all duration-150">−</button>
            <input type="number" min={1} max={max} value={quantity}
              onChange={e => setQuantity(Math.min(max, Math.max(1, Number(e.target.value))))}
              className="flex-1 text-center font-mono text-lg font-bold bg-white dark:bg-[#44475a]/30 text-gray-900 dark:text-[#f8f8f2]
                         border border-gray-300 dark:border-[#44475a] rounded-lg py-2 transition-colors hover:border-gray-400 dark:hover:border-[#6272a4] focus:outline-none focus:border-[#bd93f9]" />
            <button onClick={() => setQuantity(q => Math.min(max, q + 1))}
              className="w-9 h-9 rounded-lg bg-gray-100 dark:bg-[#44475a] text-gray-900 dark:text-[#f8f8f2] font-bold text-lg hover:opacity-80 active:scale-95 transition-all duration-150">+</button>
          </div>
          <button onClick={() => setQuantity(max)} className="text-[10px] mt-1 text-gray-500 dark:text-[#6272a4] hover:text-blue-600 dark:hover:text-[#bd93f9] transition-colors">
            최대 {max}주
          </button>
        </div>

        {/* 주문 금액 */}
        <div className="bg-gray-50 dark:bg-[#44475a]/20 rounded-lg p-3 mb-4">
          <div className="flex justify-between text-sm font-bold">
            <span className="text-gray-500 dark:text-[#6272a4]">주문 금액</span>
            <span className="font-mono" style={{ color: accentColor }}>₩{fmt(totalAmount)}</span>
          </div>
          {isBuy && cash > 0 && (
            <div className="flex justify-between text-xs mt-1">
              <span className="text-gray-500 dark:text-[#6272a4]">주문 후 잔고</span>
              <span className="font-mono text-gray-500 dark:text-[#6272a4]">₩{fmt(cash - totalAmount)}</span>
            </div>
          )}
        </div>

        {error && <p className="text-xs text-[#f6465d] mb-3">{error}</p>}
        {!isValid && quantity > max && (
          <p className="text-xs text-[#f6465d] mb-3">
            {isBuy ? `잔고 부족 (최대 ${max}주 가능)` : `보유 수량 초과 (최대 ${max}주)`}
          </p>
        )}

        <button onClick={handleSubmit} disabled={!isValid || isPending}
          className="w-full py-3 rounded-xl font-bold text-sm text-white active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100"
          style={{ backgroundColor: isValid ? accentColor : undefined }}>
          {isPending ? "처리 중..." : `${isBuy ? "매수" : "매도"} ${fmt(totalAmount)}원`}
        </button>
      </div>
    </div>
  );
}
