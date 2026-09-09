"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ShieldWarning, CheckCircle } from "@phosphor-icons/react";
import { getAccessToken } from "@/services/auth";
import { useBrokerageAccount, useBrokerageBalance, useSubmitBrokerageOrder } from "@/hooks/useBrokerage";
import { ApiError } from "@/services/brokerage";
import { Card } from "@/components/ui/Card";
import OrderProposalCard from "@/components/ai/OrderProposalCard";
import type { BrokerageOrderResponse, BrokerageOrderSide, BrokerageOrderType } from "@monticker/types";

interface StockHit { id: number; symbol: string; name: string; }

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

const STATUS_META: Record<string, { label: string; color: string }> = {
  SUBMITTED:        { label: "접수됨",   color: "text-dracula-orange" },
  FILLED:           { label: "체결 완료", color: "text-dracula-green" },
  PARTIALLY_FILLED: { label: "부분 체결", color: "text-dracula-cyan" },
  CANCELLED:        { label: "취소됨",   color: "text-gray-500 dark:text-dracula-comment" },
  REJECTED:         { label: "거부됨",   color: "text-dracula-red" },
};

export default function BrokerageOrderPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<StockHit[]>([]);
  const [stock, setStock] = useState<StockHit | null>(null);
  const [currentPrice, setCurrentPrice] = useState(0);
  const [side, setSide] = useState<BrokerageOrderSide>("BUY");
  const [orderType, setOrderType] = useState<BrokerageOrderType>("MARKET");
  const [quantity, setQuantity] = useState(1);
  const [limitPrice, setLimitPrice] = useState("");
  const [riskBlock, setRiskBlock] = useState<string | null>(null);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [result, setResult] = useState<BrokerageOrderResponse | null>(null);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: account, isLoading: accountLoading } = useBrokerageAccount();
  const { data: balance } = useBrokerageBalance(!!account);
  const submitOrder = useSubmitBrokerageOrder();

  useEffect(() => {
    if (searchQuery.length < 1) { setSearchResults([]); return; }
    const timer = setTimeout(async () => {
      const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(searchQuery)}`);
      if (r.ok) setSearchResults((await r.json()).slice(0, 6));
    }, 200);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  const selectStock = async (hit: StockHit) => {
    setStock(hit);
    setSearchQuery("");
    setSearchResults([]);
    setRiskBlock(null);
    setOrderError(null);
    setResult(null);
    const r = await fetch(`/api/stocks/${hit.id}/price`);
    const data = r.ok ? await r.json() : null;
    setCurrentPrice(data?.price ?? 0);
  };

  const holding = balance?.holdings.find(h => h.symbol === stock?.symbol);
  const cash = balance?.cash ?? 0;
  const priceForEstimate = orderType === "LIMIT" ? Number(limitPrice) || 0 : currentPrice;
  const estimatedAmount = quantity * priceForEstimate;
  const maxQty = side === "BUY"
    ? (priceForEstimate > 0 ? Math.floor(cash / priceForEstimate) : 0)
    : (holding?.quantity ?? 0);

  const isValid =
    !!stock &&
    quantity > 0 &&
    (orderType === "MARKET" || Number(limitPrice) > 0);

  const handleSubmit = async () => {
    if (!stock) return;
    setRiskBlock(null);
    setOrderError(null);
    setResult(null);
    try {
      const order = await submitOrder.mutateAsync({
        symbol: stock.symbol,
        side,
        orderType,
        quantity,
        limitPrice: orderType === "LIMIT" ? Number(limitPrice) : undefined,
      });
      setResult(order);
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) {
        setRiskBlock(e.message);
      } else {
        setOrderError((e as Error).message);
      }
    }
  };

  if (!isLoggedIn) return (
    <div className="max-w-3xl mx-auto p-6 text-center py-20">
      <p className="text-gray-500 dark:text-dracula-comment mb-4">실전투자를 이용하려면 로그인이 필요합니다.</p>
      <Link href="/login" className="inline-block bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg text-white px-6 py-2 rounded-lg font-medium hover:opacity-90 active:scale-[0.98] transition-all duration-150">로그인</Link>
    </div>
  );

  if (!accountLoading && !account) return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 text-center">
      <Card className="p-6">
        <p className="text-gray-900 dark:text-dracula-fg font-semibold mb-1">연동된 계좌가 없습니다</p>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mb-4">주문을 넣으려면 먼저 증권사 계좌를 연동하세요.</p>
        <Link href="/brokerage/connect" className="inline-block px-4 py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-sm font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150">
          계좌 연동하기
        </Link>
      </Card>
    </div>
  );

  return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-8">
        <h1 className="text-xl font-bold text-gray-900 dark:text-dracula-fg">실전 주문</h1>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">실제 증권사 계좌로 체결되는 주문입니다</p>
      </div>

      <Card className="p-4 flex items-center justify-between" outerClassName="mb-5">
        <span className="text-xs text-gray-500 dark:text-dracula-comment">가용 현금</span>
        <span className="font-mono font-bold text-gray-900 dark:text-dracula-fg">₩{fmt(cash)}</span>
      </Card>

      {stock && (
        <OrderProposalCard
          stockId={stock.id}
          onApprove={setSide}
          disclaimer="이 제안은 투자자문이 아니며, 실제 계좌에 대한 참고용 정보입니다. 최종 투자 판단과 책임은 본인에게 있습니다."
        />
      )}

      <Card className="p-5" outerClassName={stock ? "mt-5" : undefined}>
        {/* 종목 검색 */}
        <div className="relative mb-4">
          <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종목</label>
          {stock ? (
            <div className="flex items-center justify-between rounded-lg border border-gray-300 dark:border-dracula-line px-4 py-2.5">
              <div>
                <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">{stock.name}</span>
                <span className="text-xs text-gray-500 dark:text-dracula-comment ml-2">{stock.symbol}</span>
              </div>
              <button onClick={() => { setStock(null); setResult(null); setRiskBlock(null); setOrderError(null); }} className="text-xs text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg">
                변경
              </button>
            </div>
          ) : (
            <input
              type="text"
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              placeholder="종목명 또는 코드 검색"
              className="w-full rounded-lg border px-4 py-2.5 text-sm border-gray-300 bg-white text-gray-900 placeholder-gray-400 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg dark:placeholder-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple transition-all duration-150"
            />
          )}
          {searchResults.length > 0 && (
            <div className="absolute z-10 mt-1 w-full rounded-lg border border-gray-200 dark:border-dracula-line bg-white dark:bg-dracula-surface shadow-lg overflow-hidden">
              {searchResults.map(r => (
                <button key={r.id} onClick={() => selectStock(r)} className="w-full text-left px-4 py-2.5 text-sm hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors">
                  <span className="font-medium text-gray-900 dark:text-dracula-fg">{r.name}</span>
                  <span className="text-xs text-gray-500 dark:text-dracula-comment ml-2">{r.symbol}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        {stock && (
          <>
            {/* 매수/매도 */}
            <div className="grid grid-cols-2 gap-2 mb-4">
              {(["BUY", "SELL"] as const).map(s => (
                <button key={s} onClick={() => { setSide(s); setQuantity(1); }}
                  className={`py-2.5 rounded-lg text-sm font-bold transition-all duration-150 ${
                    side === s
                      ? s === "BUY" ? "bg-dracula-red/15 text-dracula-red border border-dracula-red/40" : "bg-dracula-cyan/15 text-dracula-cyan border border-dracula-cyan/40"
                      : "bg-gray-100 dark:bg-dracula-line/30 text-gray-500 dark:text-dracula-comment border border-transparent"
                  }`}>
                  {s === "BUY" ? "매수" : "매도"}
                </button>
              ))}
            </div>

            {/* 시장가/지정가 */}
            <div className="flex gap-1 mb-4 border-b border-gray-200 dark:border-dracula-line">
              {(["MARKET", "LIMIT"] as const).map(t => (
                <button key={t} onClick={() => setOrderType(t)}
                  className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
                    ${orderType === t ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple" : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"}`}>
                  {t === "MARKET" ? "시장가" : "지정가"}
                </button>
              ))}
            </div>

            {orderType === "MARKET" ? (
              <div className="bg-gray-50 dark:bg-dracula-line/20 rounded-lg p-3 mb-4 flex justify-between text-sm">
                <span className="text-gray-500 dark:text-dracula-comment">현재가</span>
                <span className="font-mono font-bold text-gray-900 dark:text-dracula-fg">₩{fmt(currentPrice)}</span>
              </div>
            ) : (
              <div className="mb-4">
                <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">지정가</label>
                <input type="number" min={0} value={limitPrice} onChange={e => setLimitPrice(e.target.value)}
                  placeholder={`현재가 ₩${fmt(currentPrice)}`}
                  className="w-full rounded-lg border px-4 py-2.5 text-sm font-mono border-gray-300 bg-white text-gray-900 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple transition-all duration-150" />
              </div>
            )}

            {/* 수량 */}
            <div className="mb-4">
              <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">수량</label>
              <div className="flex items-center gap-2">
                <button onClick={() => setQuantity(q => Math.max(1, q - 1))}
                  className="w-9 h-9 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-900 dark:text-dracula-fg font-bold text-lg hover:opacity-80 active:scale-95 transition-all duration-150">−</button>
                <input type="number" min={1} value={quantity}
                  onChange={e => setQuantity(Math.max(1, Number(e.target.value)))}
                  className="flex-1 text-center font-mono text-lg font-bold bg-white dark:bg-dracula-line/30 text-gray-900 dark:text-dracula-fg
                             border border-gray-300 dark:border-dracula-line rounded-lg py-2 transition-colors focus:outline-none focus:border-dracula-purple" />
                <button onClick={() => setQuantity(q => q + 1)}
                  className="w-9 h-9 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-900 dark:text-dracula-fg font-bold text-lg hover:opacity-80 active:scale-95 transition-all duration-150">+</button>
              </div>
              {maxQty > 0 && (
                <div className="flex gap-1.5 mt-2">
                  {[25, 50, 75, 100].map(pct => (
                    <button key={pct} onClick={() => setQuantity(Math.max(1, Math.floor(maxQty * pct / 100)))}
                      className="flex-1 py-1 rounded-md text-[11px] font-medium bg-gray-100 dark:bg-dracula-line/50 text-gray-600 dark:text-dracula-comment hover:bg-gray-200 dark:hover:bg-dracula-line active:scale-95 transition-all duration-150">
                      {pct}%
                    </button>
                  ))}
                </div>
              )}
              <p className="text-[10px] mt-1 text-gray-500 dark:text-dracula-comment">
                {side === "BUY" ? `최대 매수 가능 ${fmt(maxQty)}주` : `보유 수량 ${fmt(holding?.quantity ?? 0)}주`}
              </p>
            </div>

            {/* 주문 금액 */}
            <div className="bg-gray-50 dark:bg-dracula-line/20 rounded-lg p-3 mb-4 flex justify-between text-sm font-bold">
              <span className="text-gray-500 dark:text-dracula-comment">예상 주문 금액</span>
              <span className="font-mono text-gray-900 dark:text-dracula-fg">₩{fmt(estimatedAmount)}</span>
            </div>

            {riskBlock && (
              <div className="flex items-start gap-2 rounded-lg border border-dracula-red/40 bg-dracula-red/10 p-3 mb-4">
                <ShieldWarning size={18} weight="bold" className="text-dracula-red shrink-0 mt-0.5" aria-hidden />
                <div>
                  <p className="text-sm font-semibold text-dracula-red">리스크 게이트에 의해 주문이 차단되었습니다</p>
                  <p className="text-xs text-dracula-red/80 mt-0.5">{riskBlock}</p>
                </div>
              </div>
            )}

            {orderError && (
              <div className="flex items-start gap-2 rounded-lg border border-dracula-orange/40 bg-dracula-orange/10 p-3 mb-4">
                <ShieldWarning size={18} weight="bold" className="text-dracula-orange shrink-0 mt-0.5" aria-hidden />
                <div>
                  <p className="text-sm font-semibold text-dracula-orange">주문 제출에 실패했습니다</p>
                  <p className="text-xs text-dracula-orange/80 mt-0.5">{orderError}</p>
                </div>
              </div>
            )}

            {result && !riskBlock && !orderError && (
              <div className="flex items-start gap-2 rounded-lg border border-dracula-green/40 bg-dracula-green/10 p-3 mb-4">
                <CheckCircle size={18} weight="bold" className="text-dracula-green shrink-0 mt-0.5" aria-hidden />
                <div>
                  <p className="text-sm font-semibold text-dracula-green">
                    {STATUS_META[result.status]?.label ?? result.status}
                  </p>
                  <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
                    {result.filledQty > 0 && result.avgFillPrice
                      ? `${result.filledQty}주 @ ₩${fmt(result.avgFillPrice)} 체결`
                      : "증권사에 주문이 접수되었습니다."}
                  </p>
                </div>
              </div>
            )}

            <button onClick={handleSubmit} disabled={!isValid || submitOrder.isPending}
              className={`w-full py-3 rounded-xl font-bold text-sm text-white active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100 ${
                side === "BUY" ? "bg-dracula-red" : "bg-dracula-cyan text-dracula-bg"
              }`}>
              {submitOrder.isPending ? "처리 중..." : `${side === "BUY" ? "매수" : "매도"} 주문 제출`}
            </button>
          </>
        )}
      </Card>

      <p className="text-xs text-gray-500 dark:text-dracula-comment text-center mt-8">
        실제 자금이 이동합니다. 제출 전 수량과 가격을 다시 확인하세요.
      </p>
    </div>
  );
}
