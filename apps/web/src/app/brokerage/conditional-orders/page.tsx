"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ShieldWarning, CheckCircle, XCircle, HourglassMedium } from "@phosphor-icons/react";
import { type Icon } from "@phosphor-icons/react";
import { getAccessToken } from "@/services/auth";
import { useBrokerageAccount, useConditionalOrders, useCreateConditionalOrder, useCreateOcoOrder, useCancelConditionalOrder } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { ApiError } from "@/services/brokerage";
import { Card } from "@/components/ui/Card";
import type { BrokerageOrderSide, BrokerageOrderType, ConditionalOrderResponse, ConditionalTriggerType } from "@monticker/types";

interface StockHit { id: number; symbol: string; name: string; }

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

const STATUS_META: Record<string, { label: string; icon: Icon; color: string }> = {
  ACTIVE:    { label: "감시 중",     icon: HourglassMedium, color: "text-dracula-orange" },
  TRIGGERED: { label: "발동 처리 중", icon: HourglassMedium, color: "text-dracula-orange" },
  EXECUTED:  { label: "발동 완료",   icon: CheckCircle,     color: "text-dracula-green" },
  CANCELLED: { label: "취소됨",     icon: XCircle,          color: "text-gray-500 dark:text-dracula-comment" },
  EXPIRED:   { label: "만료됨",     icon: XCircle,          color: "text-gray-500 dark:text-dracula-comment" },
  FAILED:    { label: "발동 실패",   icon: XCircle,          color: "text-dracula-red" },
};

const TRIGGER_LABEL: Record<ConditionalTriggerType, string> = {
  STOP_LOSS: "손절",
  TAKE_PROFIT: "익절",
  PRICE_ABOVE: "가격 이상",
  PRICE_BELOW: "가격 이하",
};

function ConditionalOrderRow({ o }: { o: ConditionalOrderResponse }) {
  const meta = STATUS_META[o.status] ?? { label: o.status, icon: HourglassMedium, color: "text-gray-500" };
  const { toast } = useToast();
  const cancelOrder = useCancelConditionalOrder();

  const handleCancel = async () => {
    try {
      await cancelOrder.mutateAsync(o.id);
      toast({ type: "success", title: "취소 완료", message: "조건부 주문이 취소되었습니다." });
    } catch (e) {
      toast({ type: "error", title: "취소 실패", message: (e as Error).message });
    }
  };

  return (
    <Card className="p-4 flex items-center gap-3">
      <meta.icon size={18} weight="bold" className={meta.color} aria-hidden />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-xs font-medium ${o.side === "BUY" ? "text-dracula-red" : "text-dracula-cyan"}`}>{o.side === "BUY" ? "매수" : "매도"}</span>
          <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">{o.symbol}</span>
          <span className="text-xs text-gray-500 dark:text-dracula-comment">{TRIGGER_LABEL[o.triggerType]} ₩{fmt(o.triggerPrice)}</span>
          {o.ocoGroupId && <span className="text-[10px] px-1.5 py-0.5 rounded bg-dracula-purple/15 text-dracula-purple font-semibold">OCO</span>}
          <span className={`text-xs ${meta.color}`}>{meta.label}</span>
        </div>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
          {o.quantity}주 · {o.orderType === "LIMIT" ? `지정가 ₩${fmt(o.limitPrice ?? 0)}` : "시장가"}로 발동
          {" · "}{new Date(o.createdAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
        </p>
        {o.failReason && <p className="text-xs text-dracula-red mt-0.5">{o.failReason}</p>}
      </div>
      {o.status === "ACTIVE" && (
        <button
          onClick={handleCancel}
          disabled={cancelOrder.isPending}
          className="shrink-0 px-3 py-1.5 rounded-lg border border-dracula-red/40 text-dracula-red text-xs font-medium hover:bg-dracula-red/10 transition-colors disabled:opacity-40"
        >
          {cancelOrder.isPending ? "취소 중..." : "취소"}
        </button>
      )}
    </Card>
  );
}

export default function ConditionalOrderPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<StockHit[]>([]);
  const [stock, setStock] = useState<StockHit | null>(null);
  const [currentPrice, setCurrentPrice] = useState(0);
  const [mode, setMode] = useState<"SINGLE" | "OCO">("SINGLE");
  const [side, setSide] = useState<BrokerageOrderSide>("SELL");
  const [quantity, setQuantity] = useState(1);
  const [triggerType, setTriggerType] = useState<ConditionalTriggerType>("STOP_LOSS");
  const [triggerPrice, setTriggerPrice] = useState("");
  const [orderType, setOrderType] = useState<BrokerageOrderType>("MARKET");
  const [limitPrice, setLimitPrice] = useState("");
  const [stopLossPrice, setStopLossPrice] = useState("");
  const [takeProfitPrice, setTakeProfitPrice] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: account, isLoading: accountLoading } = useBrokerageAccount();
  const { data: ordersData, isLoading: ordersLoading } = useConditionalOrders(page, !!account);
  const createSingle = useCreateConditionalOrder();
  const createOco = useCreateOcoOrder();
  const { toast } = useToast();

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
    setFormError(null);
    const r = await fetch(`/api/stocks/${hit.id}/price`);
    const data = r.ok ? await r.json() : null;
    setCurrentPrice(data?.price ?? 0);
  };

  const isSingleValid = !!stock && quantity > 0 && Number(triggerPrice) > 0 && (orderType === "MARKET" || Number(limitPrice) > 0);
  const isOcoValid = !!stock && quantity > 0 && Number(stopLossPrice) > 0 && Number(takeProfitPrice) > 0;
  const isPending = createSingle.isPending || createOco.isPending;

  const handleSubmitSingle = async () => {
    if (!stock) return;
    setFormError(null);
    try {
      await createSingle.mutateAsync({
        symbol: stock.symbol,
        side,
        quantity,
        leg: {
          triggerType,
          triggerPrice: Number(triggerPrice),
          orderType,
          limitPrice: orderType === "LIMIT" ? Number(limitPrice) : undefined,
        },
      });
      toast({ type: "success", title: "등록 완료", message: "조건부 주문이 등록되었습니다." });
      setTriggerPrice("");
      setLimitPrice("");
    } catch (e) {
      setFormError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  const handleSubmitOco = async () => {
    if (!stock) return;
    setFormError(null);
    try {
      await createOco.mutateAsync({
        symbol: stock.symbol,
        side,
        quantity,
        legs: [
          { triggerType: "STOP_LOSS", triggerPrice: Number(stopLossPrice), orderType: "MARKET" },
          { triggerType: "TAKE_PROFIT", triggerPrice: Number(takeProfitPrice), orderType: "MARKET" },
        ],
      });
      toast({ type: "success", title: "OCO 등록 완료", message: "손절/익절 조건이 등록되었습니다." });
      setStopLossPrice("");
      setTakeProfitPrice("");
    } catch (e) {
      setFormError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  const orders = ordersData?.content ?? [];

  if (!isLoggedIn) return (
    <div className="max-w-3xl mx-auto p-6 text-center py-20">
      <p className="text-gray-500 dark:text-dracula-comment mb-4">조건부 주문을 이용하려면 로그인이 필요합니다.</p>
      <Link href="/login" className="inline-block bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg text-white px-6 py-2 rounded-lg font-medium hover:opacity-90 active:scale-[0.98] transition-all duration-150">로그인</Link>
    </div>
  );

  if (!accountLoading && !account) return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 text-center">
      <Card className="p-6">
        <p className="text-gray-900 dark:text-dracula-fg font-semibold mb-1">연동된 계좌가 없습니다</p>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mb-4">조건부 주문을 등록하려면 먼저 증권사 계좌를 연동하세요.</p>
        <Link href="/brokerage/connect" className="inline-block px-4 py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-sm font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150">
          계좌 연동하기
        </Link>
      </Card>
    </div>
  );

  return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-8">
        <h1 className="text-xl font-bold text-gray-900 dark:text-dracula-fg">조건부 주문</h1>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">가격 조건이 충족되면 자동으로 실제 주문이 제출됩니다</p>
      </div>

      <Card className="p-5" outerClassName="mb-6">
        {/* 종목 검색 */}
        <div className="relative mb-4">
          <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종목</label>
          {stock ? (
            <div className="flex items-center justify-between rounded-lg border border-gray-300 dark:border-dracula-line px-4 py-2.5">
              <div>
                <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">{stock.name}</span>
                <span className="text-xs text-gray-500 dark:text-dracula-comment ml-2">{stock.symbol}</span>
              </div>
              <button onClick={() => { setStock(null); setFormError(null); }} className="text-xs text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg">
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
            {currentPrice > 0 && (
              <div className="bg-gray-50 dark:bg-dracula-line/20 rounded-lg p-3 mb-4 flex justify-between text-sm">
                <span className="text-gray-500 dark:text-dracula-comment">현재가</span>
                <span className="font-mono font-bold text-gray-900 dark:text-dracula-fg">₩{fmt(currentPrice)}</span>
              </div>
            )}

            {/* 매수/매도 */}
            <div className="grid grid-cols-2 gap-2 mb-4">
              {(["BUY", "SELL"] as const).map(s => (
                <button key={s} onClick={() => setSide(s)}
                  className={`py-2.5 rounded-lg text-sm font-bold transition-all duration-150 ${
                    side === s
                      ? s === "BUY" ? "bg-dracula-red/15 text-dracula-red border border-dracula-red/40" : "bg-dracula-cyan/15 text-dracula-cyan border border-dracula-cyan/40"
                      : "bg-gray-100 dark:bg-dracula-line/30 text-gray-500 dark:text-dracula-comment border border-transparent"
                  }`}>
                  {s === "BUY" ? "매수" : "매도"}
                </button>
              ))}
            </div>

            {/* 단일 조건 / OCO */}
            <div className="flex gap-1 mb-4 border-b border-gray-200 dark:border-dracula-line">
              {(["SINGLE", "OCO"] as const).map(m => (
                <button key={m} onClick={() => { setMode(m); setFormError(null); }}
                  className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
                    ${mode === m ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple" : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"}`}>
                  {m === "SINGLE" ? "단일 조건" : "OCO (손절+익절)"}
                </button>
              ))}
            </div>

            {mode === "SINGLE" ? (
              <>
                <div className="mb-4">
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">트리거 조건</label>
                  <div className="grid grid-cols-2 gap-2">
                    {(Object.keys(TRIGGER_LABEL) as ConditionalTriggerType[]).map(t => (
                      <button key={t} onClick={() => setTriggerType(t)}
                        className={`py-2 rounded-lg text-xs font-semibold transition-all duration-150 ${
                          triggerType === t
                            ? "bg-blue-600/15 dark:bg-dracula-purple/15 text-blue-600 dark:text-dracula-purple border border-blue-600/40 dark:border-dracula-purple/40"
                            : "bg-gray-100 dark:bg-dracula-line/30 text-gray-500 dark:text-dracula-comment border border-transparent"
                        }`}>
                        {TRIGGER_LABEL[t]}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="mb-4">
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">트리거 가격</label>
                  <input type="number" min={0} value={triggerPrice} onChange={e => setTriggerPrice(e.target.value)}
                    placeholder={`현재가 ₩${fmt(currentPrice)}`}
                    className="w-full rounded-lg border px-4 py-2.5 text-sm font-mono border-gray-300 bg-white text-gray-900 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple transition-all duration-150" />
                </div>

                <div className="flex gap-1 mb-4 border-b border-gray-200 dark:border-dracula-line">
                  {(["MARKET", "LIMIT"] as const).map(t => (
                    <button key={t} onClick={() => setOrderType(t)}
                      className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
                        ${orderType === t ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple" : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"}`}>
                      발동 시 {t === "MARKET" ? "시장가" : "지정가"}
                    </button>
                  ))}
                </div>

                {orderType === "LIMIT" && (
                  <div className="mb-4">
                    <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">지정가</label>
                    <input type="number" min={0} value={limitPrice} onChange={e => setLimitPrice(e.target.value)}
                      className="w-full rounded-lg border px-4 py-2.5 text-sm font-mono border-gray-300 bg-white text-gray-900 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple transition-all duration-150" />
                  </div>
                )}
              </>
            ) : (
              <>
                <p className="text-xs text-gray-500 dark:text-dracula-comment mb-4">
                  손절가와 익절가를 동시에 등록합니다 — 하나가 발동되면 나머지는 자동으로 취소되고, 발동 시 시장가로 제출됩니다.
                </p>
                <div className="mb-4">
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">손절가 (이 가격 이하로 떨어지면 발동)</label>
                  <input type="number" min={0} value={stopLossPrice} onChange={e => setStopLossPrice(e.target.value)}
                    placeholder={`현재가 ₩${fmt(currentPrice)}보다 낮게`}
                    className="w-full rounded-lg border px-4 py-2.5 text-sm font-mono border-gray-300 bg-white text-gray-900 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg focus:outline-none focus:ring-2 focus:ring-dracula-red/50 focus:border-dracula-red transition-all duration-150" />
                </div>
                <div className="mb-4">
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">익절가 (이 가격 이상 오르면 발동)</label>
                  <input type="number" min={0} value={takeProfitPrice} onChange={e => setTakeProfitPrice(e.target.value)}
                    placeholder={`현재가 ₩${fmt(currentPrice)}보다 높게`}
                    className="w-full rounded-lg border px-4 py-2.5 text-sm font-mono border-gray-300 bg-white text-gray-900 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg focus:outline-none focus:ring-2 focus:ring-dracula-green/50 focus:border-dracula-green transition-all duration-150" />
                </div>
              </>
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
            </div>

            {formError && (
              <div className="flex items-start gap-2 rounded-lg border border-dracula-red/40 bg-dracula-red/10 p-3 mb-4">
                <ShieldWarning size={18} weight="bold" className="text-dracula-red shrink-0 mt-0.5" aria-hidden />
                <div>
                  <p className="text-sm font-semibold text-dracula-red">등록에 실패했습니다</p>
                  <p className="text-xs text-dracula-red/80 mt-0.5">{formError}</p>
                </div>
              </div>
            )}

            <button
              onClick={mode === "SINGLE" ? handleSubmitSingle : handleSubmitOco}
              disabled={!(mode === "SINGLE" ? isSingleValid : isOcoValid) || isPending}
              className="w-full py-3 rounded-xl font-bold text-sm text-white bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100"
            >
              {isPending ? "등록 중..." : "조건부 주문 등록"}
            </button>
          </>
        )}
      </Card>

      <h2 className="text-sm font-bold text-gray-900 dark:text-dracula-fg mb-3">등록된 조건부 주문</h2>
      {ordersLoading ? (
        <div className="space-y-2">{[1, 2].map(i => <div key={i} className="h-16 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}</div>
      ) : orders.length === 0 ? (
        <div className="text-center py-16 border border-dashed border-gray-300 dark:border-dracula-line rounded-xl text-gray-500 dark:text-dracula-comment text-sm">
          등록된 조건부 주문이 없습니다.
        </div>
      ) : (
        <>
          <div className="space-y-2">{orders.map(o => <ConditionalOrderRow key={o.id} o={o} />)}</div>
          {(ordersData?.totalPages ?? 0) > 1 && (
            <div className="flex justify-center gap-3 mt-6">
              {page > 0 && <button onClick={() => setPage(p => p - 1)} className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-sm font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment transition-all duration-150">이전</button>}
              {page < (ordersData?.totalPages ?? 1) - 1 && <button onClick={() => setPage(p => p + 1)} className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-sm font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment transition-all duration-150">다음</button>}
            </div>
          )}
        </>
      )}

      <p className="text-xs text-gray-500 dark:text-dracula-comment text-center mt-8">
        같은 계좌를 HTS/앱 등 다른 경로로도 거래하면 조건부 주문이 이를 인지하지 못해 의도치 않은 중복 매매가 발생할 수 있습니다.
      </p>
    </div>
  );
}
