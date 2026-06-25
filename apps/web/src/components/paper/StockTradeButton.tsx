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
}

export default function StockTradeButton({ stockId, symbol, name, currentPrice }: Props) {
  const [modal, setModal] = useState<"BUY" | "SELL" | null>(null);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const { data: portfolio } = usePaperPortfolio();

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);
  if (!isLoggedIn) return null;

  const holding = portfolio?.holdings.find((h: { stockId: number; quantity: number }) => h.stockId === stockId);
  const ownedQty = holding?.quantity ?? 0;

  return (
    <>
      <div className="flex gap-2">
        <button
          onClick={() => setModal("BUY")}
          className="flex-1 py-2.5 rounded-xl font-bold text-sm text-white bg-[#ff5050] hover:opacity-90">
          매수
        </button>
        {ownedQty > 0 && (
          <button
            onClick={() => setModal("SELL")}
            className="flex-1 py-2.5 rounded-xl font-bold text-sm text-white bg-[#4a8fd4] hover:opacity-90">
            매도 ({ownedQty}주)
          </button>
        )}
      </div>

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
}
