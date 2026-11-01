"use client";

import { useEffect, useId, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { usePaperPortfolio } from "@/hooks/usePaperTrade";
import { Btn, BuySell, Field, Icon, KV, Seg, SelectBox, fmtNum } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { estimateMarketFill, type FillDto, type OrderBookData, type OrderDto } from "./data";

// V-L7 — 주문 폼에 수량 상한이 전혀 없어 1e9 같은 값이 클라 검사를 그대로 통과했다.
const MAX_ORDER_QUANTITY = 1_000_000;

interface RiskCheckResult {
  approved: boolean; blockedBy: string | null; severity: string;
  checks: { rule: string; passed: boolean; detail: string; current: number; limit: number }[];
}

interface SubmitOrderResponse { order: OrderDto; fills: FillDto[]; message: string; }

const RULE_LABEL: Record<string, string> = {
  DAILY_LOSS: "일일 손실 한도", CONCENTRATION: "종목 집중도",
  VAR: "VaR 한도", POSITION_COUNT: "최대 종목 수", TRADING_FREQUENCY: "주문 빈도",
};

const ORDER_TYPES = [{ value: "LIMIT", label: "지정가" }, { value: "MARKET", label: "시장가" }] as const;

function RiskPreview({ result }: { result: RiskCheckResult }) {
  return (
    <div className={cn("flex flex-col gap-2 rounded-[10px] border px-3.5 py-3 text-xs", result.approved ? "border-dracula-green/35 bg-dracula-green/12" : "border-danger/40 bg-danger/15")}>
      <div className="flex items-center gap-2 font-semibold">
        <Icon name={result.approved ? "check" : "alert"} size={15} className={result.approved ? "text-dracula-green" : "text-danger"} />
        <span className={result.approved ? "text-dracula-green" : "text-danger"}>
          {result.approved ? "리스크 한도 통과" : result.blockedBy ?? "리스크 한도 초과"}
        </span>
      </div>
      {result.checks.map((c) => (
        <div key={c.rule} className="flex items-center justify-between gap-2">
          <span className={c.passed ? "text-tm-muted" : "font-medium text-danger"}>
            {c.passed ? "✓" : "✕"} {RULE_LABEL[c.rule] ?? c.rule}
          </span>
          <span className={c.passed ? "text-tm-muted" : "text-danger"}>{c.detail}</span>
        </div>
      ))}
    </div>
  );
}

/** 시안 Matching "주문 입력" — 모의투자 CLOB 엔진으로 주문을 낸다. */
export function OrderForm({ stockId, setStockId, stocks, presetSide, book }: {
  stockId: number;
  setStockId: (id: number) => void;
  /** 고를 수 있는 종목(useFeaturedStocks) — id는 종목 코드로 찾은 값이다 */
  stocks: { id: number; label: string }[];
  presetSide?: "BUY" | "SELL";
  book: OrderBookData | null | undefined;
}) {
  const qc = useQueryClient();
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [orderType, setOrderType] = useState<"MARKET" | "LIMIT">("MARKET");
  const [quantity, setQuantity] = useState(10);
  const [limitPrice, setLimitPrice] = useState("");
  const [riskResult, setRiskResult] = useState<RiskCheckResult | null>(null);
  const [result, setResult] = useState<SubmitOrderResponse | null>(null);

  // ADR-036 — AI 제안 승인 시 이 폼에 방향만 반영한다. 실제 제출은 사용자가 수량을
  // 확인하고 아래 주문 버튼을 직접 눌러야 한다 — 승인이 곧바로 주문으로 이어지지 않는다.
  useEffect(() => {
    if (presetSide) setSide(presetSide);
  }, [presetSide]);

  const parsedLimitPrice = parseFloat(limitPrice);
  const isBuy = side === "BUY";
  const isQuantityValid = Number.isInteger(quantity) && quantity > 0;
  const isLimitPriceValid = orderType !== "LIMIT" || (limitPrice !== "" && Number.isFinite(parsedLimitPrice) && parsedLimitPrice > 0);
  const isValid = isQuantityValid && isLimitPriceValid;

  const est = estimateMarketFill(book, side, quantity);
  // 리스크 사전 확인(DryRunCheckRequest)은 estimatedPrice가 필수다 — 지정가는 그 가격, 시장가는 호가로 추정
  const estimatedPrice = orderType === "LIMIT" ? parsedLimitPrice : est?.avg ?? book?.currentPrice ?? null;
  const amount = estimatedPrice != null && Number.isFinite(estimatedPrice) ? estimatedPrice * quantity : null;

  // 체결 엔진은 모의계좌 현금에서 예약한다(매수: 지정가 × 수량, 시장가는 호가로 추정한 금액). 넘으면 입력 즉시 알리고 제출을 막는다 —
  // "리스크 사전 확인"을 누르기 전까지 모르는 일이 없게. 잔고를 아직 못 읽었으면 판단하지 않는다(서버가 최종 판정).
  const qtyErrorId = useId();
  const { data: portfolio } = usePaperPortfolio();
  const cash = portfolio?.cash ?? null;
  const unitPrice = estimatedPrice != null && Number.isFinite(estimatedPrice) && estimatedPrice > 0 ? estimatedPrice : null;
  const maxBuyQty = cash != null && unitPrice != null ? Math.floor(cash / unitPrice) : null;
  const overCash = isBuy && cash != null && amount != null && unitPrice != null && amount > cash;
  const cashError = overCash ? `가용 현금 ${fmtNum(cash)}원을 넘습니다 · 최대 ${fmtNum(maxBuyQty)}주` : null;

  const orderPayload = () => ({
    stockId, side, orderType, quantity,
    limitPrice: orderType === "LIMIT" ? parsedLimitPrice : null,
  });

  const riskCheckMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch("/api/risk/check", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ...orderPayload(), estimatedPrice }),
      });
      if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.message ?? "리스크 확인 실패"); }
      return res.json() as Promise<RiskCheckResult>;
    },
    onSuccess: (data) => { setRiskResult(data); setResult(null); },
  });

  const submitMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch("/api/matching/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(orderPayload()),
      });
      if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.message ?? "주문 실패"); }
      return res.json() as Promise<SubmitOrderResponse>;
    },
    onSuccess: (data) => {
      setResult(data); setRiskResult(null);
      qc.invalidateQueries({ queryKey: ["matching", "orders"] });
      qc.invalidateQueries({ queryKey: ["matching", "fills"] });
    },
  });

  return (
    <>
      <SelectBox label="종목" value={stockId} onChange={(e) => setStockId(+e.target.value)} aria-label="종목">
        {stocks.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
      </SelectBox>
      <BuySell value={side} onChange={setSide} />
      <Seg options={ORDER_TYPES} value={orderType} onChange={setOrderType} full size="lg" />
      {orderType === "LIMIT" && (
        <Field id="limit-price" label="지정가" aria-label="지정가 (원)" unit="원" type="number" min={0} placeholder="예: 70000" value={limitPrice} onChange={(e) => setLimitPrice(e.target.value)} className="flex-none" />
      )}
      <Field
        label="수량" aria-label="수량" unit="주" type="number" min={1} max={MAX_ORDER_QUANTITY} value={quantity}
        aria-invalid={overCash || undefined} aria-describedby={cashError ? qtyErrorId : undefined}
        onChange={(e) => setQuantity(Math.min(MAX_ORDER_QUANTITY, Math.max(1, Math.floor(Number(e.target.value) || 1))))}
        className="flex-none"
      />
      {cashError && <p id={qtyErrorId} role="alert" className="m-0 text-xs text-danger">{cashError}</p>}
      <KV k={isBuy ? "예약될 금액" : "예상 체결 금액"} v={amount != null ? `${fmtNum(amount)}원` : "—"} />
      <KV
        k="예상 슬리피지 (시장가 시)"
        v={est ? `${est.slip > 0 ? "+" : ""}${est.slip.toFixed(2)}%${est.shortfall > 0 ? " · 잔량 부족" : ""}` : "—"}
        valueClassName="text-dracula-yellow"
      />

      {riskResult && <RiskPreview result={riskResult} />}
      {riskCheckMutation.isError && <p role="alert" className="m-0 text-xs text-danger">{(riskCheckMutation.error as Error).message}</p>}

      {result && (
        <div role="status" className="flex flex-col gap-1 rounded-[10px] border border-dracula-green/35 bg-dracula-green/12 px-3.5 py-3 text-xs">
          <p className="m-0 inline-flex items-center gap-1.5 font-semibold text-dracula-green">
            <Icon name="check" size={14} /> {result.message}
          </p>
          <p className="m-0 text-tm-muted">상태: <span className="text-dracula-fg">{result.order.status}</span></p>
          {result.fills.map((f) => (
            <p key={f.id} className="num m-0 text-tm-muted">
              체결: {f.quantity}주 @ {fmtNum(f.fillPrice)}원 <span className="ml-2 text-danger">수수료 {fmtNum(f.fee)}원</span>
            </p>
          ))}
        </div>
      )}

      <div className="flex gap-2">
        <Btn kind="ghost" size="lg" className="flex-1" onClick={() => riskCheckMutation.mutate()} disabled={riskCheckMutation.isPending || !isValid || estimatedPrice == null}>
          {riskCheckMutation.isPending ? "확인 중..." : "리스크 사전 확인"}
        </Btn>
      </div>
      <Btn kind={isBuy ? "buy" : "sell"} size="lg" full onClick={() => submitMutation.mutate()} disabled={submitMutation.isPending || !isValid || overCash}>
        {submitMutation.isPending ? "처리 중..." : `${isBuy ? "매수" : "매도"} 주문 제출`}
      </Btn>
      {submitMutation.isError && <p role="alert" className="m-0 text-xs text-danger">{(submitMutation.error as Error).message}</p>}
      <p className="m-0 text-xs leading-normal text-tm-muted">지정가는 약 3초마다 접수 순으로 확인해, 1분봉 종가가 지정가에 닿으면 그 값으로 전량 체결됩니다(가격·시간 우선 매칭이 아닙니다).</p>
    </>
  );
}
