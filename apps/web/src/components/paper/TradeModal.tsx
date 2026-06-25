"use client";

import { useState, useEffect } from "react";
import { usePaperTrade, usePaperPortfolio } from "@/hooks/usePaperTrade";

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
  const { buy, sell } = usePaperTrade();
  const { data: portfolio } = usePaperPortfolio();

  const isBuy = side === "BUY";
  const totalAmount = quantity * currentPrice;
  const cash = portfolio?.cash ?? 0;

  const maxBuy = currentPrice > 0 ? Math.floor(cash / currentPrice) : 0;
  const max = isBuy ? maxBuy : (maxQuantity ?? 0);

  const isValid = quantity > 0 && quantity <= max;
  const isPending = buy.isPending || sell.isPending;

  const handleSubmit = async () => {
    setError("");
    try {
      if (isBuy) {
        await buy.mutateAsync({ stockId: stock.id, quantity });
      } else {
        await sell.mutateAsync({ stockId: stock.id, quantity });
      }
      onClose();
    } catch (e: unknown) {
      setError((e as Error).message);
    }
  };

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [onClose]);

  const accentColor = isBuy ? "#ff5050" : "#4a8fd4";

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="bg-white dark:bg-[#282a36] border dark:border-[#44475a] rounded-2xl
                      w-full max-w-sm mx-4 p-6 shadow-2xl">

        {/* 헤더 */}
        <div className="flex items-center justify-between mb-5">
          <div>
            <div className="flex items-center gap-2">
              <span className="text-xs font-bold px-2 py-0.5 rounded"
                style={{ backgroundColor: `${accentColor}20`, color: accentColor }}>
                {isBuy ? "매수" : "매도"}
              </span>
              <span className="font-bold dark:text-[#f8f8f2]">{stock.name}</span>
            </div>
            <p className="text-xs dark:text-[#6272a4] mt-0.5">{stock.symbol}</p>
          </div>
          <button onClick={onClose} className="dark:text-[#6272a4] hover:dark:text-[#f8f8f2] text-lg">✕</button>
        </div>

        {/* 현재가 */}
        <div className="dark:bg-[#44475a]/20 rounded-lg p-3 mb-4">
          <div className="flex justify-between text-sm">
            <span className="dark:text-[#6272a4]">현재가</span>
            <span className="font-mono font-bold dark:text-[#f8f8f2]">₩{fmt(currentPrice)}</span>
          </div>
          {isBuy && (
            <div className="flex justify-between text-xs mt-1">
              <span className="dark:text-[#6272a4]">가용 현금</span>
              <span className="font-mono dark:text-[#f8f8f2]">₩{fmt(cash)}</span>
            </div>
          )}
        </div>

        {/* 수량 입력 */}
        <div className="mb-4">
          <label className="text-xs dark:text-[#6272a4] mb-1 block">수량</label>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setQuantity(q => Math.max(1, q - 1))}
              className="w-9 h-9 rounded-lg dark:bg-[#44475a] dark:text-[#f8f8f2] font-bold text-lg hover:opacity-80">−</button>
            <input
              type="number" min={1} max={max} value={quantity}
              onChange={e => setQuantity(Math.min(max, Math.max(1, Number(e.target.value))))}
              className="flex-1 text-center font-mono text-lg font-bold dark:bg-[#44475a]/30 dark:text-[#f8f8f2]
                         border dark:border-[#44475a] rounded-lg py-2 focus:outline-none focus:dark:border-[#bd93f9]" />
            <button
              onClick={() => setQuantity(q => Math.min(max, q + 1))}
              className="w-9 h-9 rounded-lg dark:bg-[#44475a] dark:text-[#f8f8f2] font-bold text-lg hover:opacity-80">+</button>
          </div>
          <button
            onClick={() => setQuantity(max)}
            className="text-[10px] mt-1 dark:text-[#6272a4] hover:dark:text-[#bd93f9]">
            최대 {max}주
          </button>
        </div>

        {/* 주문 금액 */}
        <div className="dark:bg-[#44475a]/20 rounded-lg p-3 mb-4">
          <div className="flex justify-between text-sm font-bold">
            <span className="dark:text-[#6272a4]">주문 금액</span>
            <span className="font-mono" style={{ color: accentColor }}>₩{fmt(totalAmount)}</span>
          </div>
          {isBuy && cash > 0 && (
            <div className="flex justify-between text-xs mt-1">
              <span className="dark:text-[#6272a4]">주문 후 잔고</span>
              <span className="font-mono dark:text-[#6272a4]">₩{fmt(cash - totalAmount)}</span>
            </div>
          )}
        </div>

        {/* 오류 메시지 */}
        {error && <p className="text-xs text-[#f6465d] mb-3">{error}</p>}
        {!isValid && quantity > max && (
          <p className="text-xs text-[#f6465d] mb-3">
            {isBuy ? `잔고 부족 (최대 ${max}주 가능)` : `보유 수량 초과 (최대 ${max}주)`}
          </p>
        )}

        {/* 주문 버튼 */}
        <button
          onClick={handleSubmit}
          disabled={!isValid || isPending}
          className="w-full py-3 rounded-xl font-bold text-sm text-white disabled:opacity-40"
          style={{ backgroundColor: isValid ? accentColor : undefined }}>
          {isPending ? "처리 중..." : `${isBuy ? "매수" : "매도"} ${fmt(totalAmount)}원`}
        </button>
      </div>
    </div>
  );
}
