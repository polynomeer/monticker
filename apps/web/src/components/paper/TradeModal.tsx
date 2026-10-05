"use client";

import { useState, useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { Btn, Icon, IconBtn, KV } from "@/components/terminal";
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
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70">
        <div role="status" className="flex flex-col items-center gap-1 rounded-[10px] border border-tm-line2 bg-tm-panel px-8 py-6 text-center">
          <span className="text-dracula-green"><Icon name="check" size={26} strokeWidth={2.6} /></span>
          <p className="m-0 text-sm font-semibold text-dracula-fg">체결 완료</p>
          <p className="m-0 text-xs text-tm-muted">영수증 생성 중...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <section role="dialog" aria-modal="true" aria-label={`${stock.name} ${isBuy ? "매수" : "매도"}`}
        className="mx-4 flex w-full max-w-sm flex-col rounded-[10px] border border-tm-line2 bg-tm-panel text-13 text-dracula-fg shadow-glow-line">
        <div className="flex items-center gap-1.5 border-b border-tm-line px-2 py-1.5">
          <span className="inline-flex h-[30px] items-center gap-2 rounded-md bg-tm-raised px-2.5 font-semibold">
            <span className={isBuy ? "text-up" : "text-down"}>{isBuy ? "매수" : "매도"}</span>
            {stock.name}
            <span className="num text-2xs font-normal text-tm-muted">{stock.symbol}</span>
          </span>
          <IconBtn name="x" label="닫기" size={28} iconSize={15} className="ml-auto" onClick={onClose} />
        </div>

        <div className="flex flex-col gap-3 p-3.5">
          <div className="flex flex-col gap-1.5 rounded-lg bg-tm-inner p-3">
            <KV k="현재가" v={`${fmt(currentPrice)}원`} valueClassName="font-semibold" />
            {isBuy && <KV k="가용 현금" v={`${fmt(cash)}원`} />}
          </div>

          <div className="flex flex-col gap-2">
            <label htmlFor="trade-modal-qty" className="text-2xs text-tm-muted">수량</label>
            <div className="flex items-center gap-2">
              <IconBtn name="minus" label="수량 줄이기" size={36} className="bg-tm-raised" onClick={() => setQuantity(q => Math.max(1, q - 1))} />
              <input id="trade-modal-qty" type="number" min={1} max={max} value={quantity}
                onChange={e => setQuantity(Math.min(max, Math.max(1, Number(e.target.value))))}
                className="num h-10 min-w-0 flex-1 rounded-lg border border-tm-line bg-tm-inner text-center text-base font-semibold text-dracula-fg outline-none focus:border-dracula-purple" />
              <IconBtn name="plus" label="수량 늘리기" size={36} className="bg-tm-raised" onClick={() => setQuantity(q => Math.min(max, q + 1))} />
            </div>
            <div className="flex gap-1.5">
              {[25, 50, 75, 100].map(pct => (
                <button key={pct} type="button"
                  onClick={() => setQuantity(Math.max(1, Math.floor(max * pct / 100)))}
                  disabled={max <= 0}
                  className="h-7 flex-1 rounded-md bg-tm-inner text-2xs text-tm-muted hover:text-dracula-fg disabled:opacity-40">
                  {pct}%
                </button>
              ))}
            </div>
            <span className="num text-2xs text-tm-muted">최대 {max}주</span>
          </div>

          <div className="flex flex-col gap-1.5 rounded-lg bg-tm-inner p-3">
            <KV k="주문 금액" v={`${fmt(totalAmount)}원`} valueClassName={`font-bold ${isBuy ? "text-up" : "text-down"}`} />
            {isBuy && cash > 0 && <KV k="주문 후 잔고" v={`${fmt(cash - totalAmount)}원`} valueClassName="text-tm-muted" />}
          </div>

          {error && <p role="alert" className="m-0 text-xs text-[#ff8a8a]">{error}</p>}
          {!isValid && quantity > max && (
            <p className="m-0 text-xs text-[#ff8a8a]">
              {isBuy ? `잔고 부족 (최대 ${max}주 가능)` : `보유 수량 초과 (최대 ${max}주)`}
            </p>
          )}

          <Btn kind={isBuy ? "buy" : "sell"} size="lg" full onClick={handleSubmit} disabled={!isValid || isPending}>
            {isPending ? "처리 중..." : `${isBuy ? "매수" : "매도"} ${fmt(totalAmount)}원`}
          </Btn>
        </div>
      </section>
    </div>
  );
}
