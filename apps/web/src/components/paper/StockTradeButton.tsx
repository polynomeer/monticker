"use client";

import { useState, useEffect } from "react";
import { getAccessToken } from "@/services/auth";
import { usePaperPortfolio } from "@/hooks/usePaperTrade";
import TradeModal from "./TradeModal";

interface Props {
  stockId: number;
  symbol: string;
  name: string;
  currentPrice: number;
  /** 차트 위에 얹는 컴팩트 플로팅 티켓 스타일 (기본: 전체 폭 버튼) */
  floating?: boolean;
}

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

export default function StockTradeButton({ stockId, symbol, name, currentPrice, floating = false }: Props) {
  const [modal, setModal] = useState<"BUY" | "SELL" | null>(null);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const { data: portfolio } = usePaperPortfolio();

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);
  if (!isLoggedIn) return null;

  const holding = portfolio?.holdings.find((h: { stockId: number; quantity: number }) => h.stockId === stockId);
  const ownedQty = holding?.quantity ?? 0;

  const trade = (
    <>
      {floating ? (
        <div className="flex items-center rounded-lg overflow-hidden shadow-lg dark:shadow-[0_4px_20px_rgba(0,0,0,0.5)] border border-gray-200 dark:border-dracula-line bg-white/95 dark:bg-dracula-bg/95 backdrop-blur-sm">
          <button
            onClick={() => setModal("SELL")}
            disabled={ownedQty === 0}
            className="px-3 py-2 text-xs font-bold text-[#4a8fd4] hover:bg-[#4a8fd4]/10 active:scale-95 transition-all duration-150 disabled:opacity-30 disabled:hover:bg-transparent">
            매도
          </button>
          <div className="px-2.5 py-2 text-xs font-mono font-semibold text-gray-900 dark:text-dracula-fg border-x border-gray-200 dark:border-dracula-line tabular-nums">
            {fmt(currentPrice)}
          </div>
          <button
            onClick={() => setModal("BUY")}
            className="px-3 py-2 text-xs font-bold text-[#ff5050] hover:bg-[#ff5050]/10 active:scale-95 transition-all duration-150">
            매수
          </button>
        </div>
      ) : (
        <div className="flex gap-2">
          <button
            onClick={() => setModal("BUY")}
            className="flex-1 py-2.5 rounded-xl font-bold text-sm text-white bg-[#ff5050] hover:opacity-90 active:scale-[0.98] transition-all duration-150">
            매수
          </button>
          {ownedQty > 0 && (
            <button
              onClick={() => setModal("SELL")}
              className="flex-1 py-2.5 rounded-xl font-bold text-sm text-white bg-[#4a8fd4] hover:opacity-90 active:scale-[0.98] transition-all duration-150">
              매도 ({ownedQty}주)
            </button>
          )}
        </div>
      )}

      {modal && (
        <TradeModal
          stock={{ id: stockId, symbol, name }}
          currentPrice={currentPrice}
          side={modal}
          maxQuantity={ownedQty}
          onClose={() => setModal(null)}
        />
      )}
    </>
  );

  return trade;
}
