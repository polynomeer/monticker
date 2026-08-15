"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { CheckCircle, Prohibit, Check, X } from "@phosphor-icons/react";
import { authFetch } from "@/services/api";
import { Card } from "@/components/ui/Card";

// ── Types ──────────────────────────────────────────────────────────────────

interface OrderDto {
  id: number; stockId: number; side: string; orderType: string;
  quantity: number; limitPrice: number | null; filledQty: number;
  avgFillPrice: number | null; status: string;
  rejectReason: string | null; createdAt: string;
}

interface FillDto {
  id: number; orderId: number; stockId: number; side: string;
  quantity: number; fillPrice: number; amount: number; fee: number; filledAt: string;
}

interface RiskCheckResult {
  approved: boolean; blockedBy: string | null; severity: string;
  checks: { rule: string; passed: boolean; detail: string; current: number; limit: number }[];
}

interface SubmitOrderResponse {
  order: OrderDto; fills: FillDto[]; message: string;
}

// ── Helpers ────────────────────────────────────────────────────────────────

const STATUS_STYLE: Record<string, string> = {
  PENDING:          "text-dracula-orange bg-dracula-orange/10",
  PARTIALLY_FILLED: "text-dracula-purple bg-dracula-purple/10",
  FILLED:           "text-dracula-green bg-dracula-green/10",
  CANCELLED:        "text-gray-500 bg-gray-100 dark:text-dracula-comment dark:bg-dracula-line",
  REJECTED:         "text-dracula-red bg-dracula-red/10",
};

const RULE_LABEL: Record<string, string> = {
  DAILY_LOSS: "일일 손실 한도", CONCENTRATION: "종목 집중도",
  VAR: "VaR 한도", POSITION_COUNT: "최대 종목 수", TRADING_FREQUENCY: "주문 빈도",
};

function won(n: number) { return n.toLocaleString("ko-KR"); }

// ── Risk Preview ────────────────────────────────────────────────────────────

function RiskPreview({ result }: { result: RiskCheckResult }) {
  return (
    <div className={`mt-3 p-3 rounded-xl border text-xs space-y-2 animate-fade-up
      ${result.approved ? "border-dracula-green/30 bg-dracula-green/5" : "border-dracula-red/30 bg-dracula-red/5"}`}>
      <div className="flex items-center gap-2 font-semibold">
        {result.approved
          ? <CheckCircle size={16} weight="bold" className="text-dracula-green" aria-hidden />
          : <Prohibit size={16} weight="bold" className="text-dracula-red" aria-hidden />}
        <span className={result.approved ? "text-dracula-green" : "text-dracula-red"}>
          {result.approved ? "리스크 한도 통과" : result.blockedBy ?? "리스크 한도 초과"}
        </span>
      </div>
      {result.checks.map(c => (
        <div key={c.rule} className="flex items-center justify-between">
          <span className={`inline-flex items-center gap-1 ${c.passed ? "text-gray-500 dark:text-dracula-comment" : "text-dracula-red font-medium"}`}>
            {c.passed ? <Check size={12} weight="bold" aria-hidden /> : <X size={12} weight="bold" aria-hidden />} {RULE_LABEL[c.rule] ?? c.rule}
          </span>
          <span className={c.passed ? "text-gray-500 dark:text-dracula-comment" : "text-dracula-red"}>{c.detail}</span>
        </div>
      ))}
    </div>
  );
}

// ── Order Form ──────────────────────────────────────────────────────────────

function OrderForm() {
  const qc = useQueryClient();
  const [stockId, setStockId] = useState(2);
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [orderType, setOrderType] = useState<"MARKET" | "LIMIT">("MARKET");
  const [quantity, setQuantity] = useState(10);
  const [limitPrice, setLimitPrice] = useState("");
  const [riskResult, setRiskResult] = useState<RiskCheckResult | null>(null);
  const [result, setResult] = useState<SubmitOrderResponse | null>(null);

  const STOCKS = [
    { id: 2, label: "삼성전자" }, { id: 3, label: "SK하이닉스" },
    { id: 9, label: "현대차" },   { id: 10, label: "NAVER" },
    { id: 5, label: "AAPL" },    { id: 6, label: "NVDA" },
  ];

  const riskCheckMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch("/api/risk/check", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId, side, orderType, quantity,
          limitPrice: orderType === "LIMIT" && limitPrice ? parseFloat(limitPrice) : null }),
      });
      return res.json() as Promise<RiskCheckResult>;
    },
    onSuccess: (data) => { setRiskResult(data); setResult(null); },
  });

  const submitMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch("/api/matching/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId, side, orderType, quantity,
          limitPrice: orderType === "LIMIT" && limitPrice ? parseFloat(limitPrice) : null }),
      });
      if (!res.ok) { const e = await res.json(); throw new Error(e.message ?? "주문 실패"); }
      return res.json() as Promise<SubmitOrderResponse>;
    },
    onSuccess: (data) => {
      setResult(data); setRiskResult(null);
      qc.invalidateQueries({ queryKey: ["matching", "orders"] });
    },
  });

  const isBuy = side === "BUY";

  return (
    <Card className="p-5 space-y-4">
      <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">주문 입력</h2>

      {/* 종목 + 방향 */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종목</label>
          <select value={stockId} onChange={e => setStockId(+e.target.value)}
            className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm px-3 py-2 transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50">
            {STOCKS.map(s => <option key={s.id} value={s.id}>{s.label}</option>)}
          </select>
        </div>
        <div>
          <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">매수/매도</label>
          <div className="flex rounded-lg overflow-hidden border border-gray-300 dark:border-dracula-line">
            {(["BUY","SELL"] as const).map(s => (
              <button key={s} onClick={() => setSide(s)}
                className={`flex-1 py-2 text-sm font-semibold transition-colors duration-150
                  ${side === s
                    ? s === "BUY" ? "bg-[#ff5050] text-white" : "bg-[#4a8fd4] text-white"
                    : "bg-white dark:bg-dracula-bg text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"}`}>
                {s === "BUY" ? "매수" : "매도"}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* 주문 유형 + 수량 */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">주문 유형</label>
          <div className="flex rounded-lg overflow-hidden border border-gray-300 dark:border-dracula-line">
            {(["MARKET","LIMIT"] as const).map(t => (
              <button key={t} onClick={() => setOrderType(t)}
                className={`flex-1 py-2 text-xs font-semibold transition-colors duration-150
                  ${orderType === t ? "bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg" : "bg-white dark:bg-dracula-bg text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"}`}>
                {t === "MARKET" ? "시장가" : "지정가"}
              </button>
            ))}
          </div>
        </div>
        <div>
          <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">수량</label>
          <input type="number" min={1} value={quantity} onChange={e => setQuantity(+e.target.value)}
            className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm px-3 py-2 transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50" />
        </div>
      </div>

      {/* 지정가 입력 */}
      {orderType === "LIMIT" && (
        <div>
          <label htmlFor="limit-price" className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">지정가 (원)</label>
          <input id="limit-price" type="number" value={limitPrice} onChange={e => setLimitPrice(e.target.value)}
            placeholder="예: 70000"
            className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm px-3 py-2 transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50" />
        </div>
      )}

      {/* 리스크 체크 결과 */}
      {riskResult && <RiskPreview result={riskResult} />}

      {/* 체결 결과 */}
      {result && (
        <div className="p-3 rounded-xl border border-dracula-green/30 bg-dracula-green/5 text-xs space-y-1 animate-fade-up">
          <p className="font-semibold text-dracula-green inline-flex items-center gap-1.5">
            <CheckCircle size={14} weight="bold" aria-hidden /> {result.message}
          </p>
          <p className="text-gray-500 dark:text-dracula-comment">상태: <span className="text-gray-900 dark:text-dracula-fg">{result.order.status}</span></p>
          {result.fills.map(f => (
            <p key={f.id} className="text-gray-500 dark:text-dracula-comment">
              체결: {f.quantity}주 @ {won(f.fillPrice)}원
              <span className="ml-2 text-dracula-red">수수료 {won(f.fee)}원</span>
            </p>
          ))}
        </div>
      )}

      {/* 버튼 */}
      <div className="grid grid-cols-2 gap-2">
        <button onClick={() => riskCheckMutation.mutate()} disabled={riskCheckMutation.isPending}
          className="py-2.5 rounded-xl border border-dracula-purple text-dracula-purple text-sm font-semibold hover:bg-dracula-purple/10 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100">
          {riskCheckMutation.isPending ? "확인 중..." : "리스크 사전 확인"}
        </button>
        <button onClick={() => submitMutation.mutate()} disabled={submitMutation.isPending}
          className={`py-2.5 rounded-xl text-sm font-bold text-white active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100
            ${isBuy ? "bg-[#ff5050] hover:bg-[#ff3030]" : "bg-[#4a8fd4] hover:bg-[#3a7fc4]"}`}>
          {submitMutation.isPending ? "처리 중..." : `${isBuy ? "매수" : "매도"} 주문`}
        </button>
      </div>

      {submitMutation.isError && (
        <p className="text-xs text-dracula-red">{(submitMutation.error as Error).message}</p>
      )}
    </Card>
  );
}

// ── Active Orders ───────────────────────────────────────────────────────────

function ActiveOrders() {
  const qc = useQueryClient();
  const { data: orders = [] } = useQuery<OrderDto[]>({
    queryKey: ["matching", "orders"],
    queryFn: async () => {
      const r = await authFetch("/api/matching/orders");
      if (!r.ok) return [];
      return r.json();
    },
    refetchInterval: 5000,
  });

  const cancelMutation = useMutation({
    mutationFn: async (orderId: number) => {
      await authFetch(`/api/matching/orders/${orderId}`, { method: "DELETE" });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["matching", "orders"] }),
  });

  if (orders.length === 0) return (
    <div className="p-4 rounded-xl border border-dashed border-gray-300 dark:border-dracula-line text-center text-xs text-gray-500 dark:text-dracula-comment">
      미체결 주문 없음
    </div>
  );

  return (
    <div className="space-y-2">
      {orders.map((o: OrderDto) => (
        <Card key={o.id} className="flex items-center justify-between p-3 text-xs">
          <div>
            <div className="flex items-center gap-2 mb-0.5">
              <span className={`font-bold ${o.side === "BUY" ? "text-[#ff5050]" : "text-[#4a8fd4]"}`}>
                {o.side === "BUY" ? "매수" : "매도"}
              </span>
              <span className="text-gray-900 dark:text-dracula-fg tabular-nums">{o.quantity}주</span>
              {o.limitPrice && <span className="text-gray-500 dark:text-dracula-comment tabular-nums">@ {won(o.limitPrice)}원</span>}
              <span className="text-gray-500 dark:text-dracula-comment">{o.orderType === "MARKET" ? "시장가" : "지정가"}</span>
            </div>
            <div className="text-gray-500 dark:text-dracula-comment tabular-nums">
              체결 {o.filledQty}/{o.quantity}주
              {o.avgFillPrice && <span className="ml-1">평균 {won(o.avgFillPrice)}원</span>}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_STYLE[o.status] ?? ""}`}>
              {o.status}
            </span>
            {(o.status === "PENDING" || o.status === "PARTIALLY_FILLED") && (
              <button onClick={() => cancelMutation.mutate(o.id)}
                className="text-dracula-red hover:opacity-70 active:scale-95 transition-transform font-medium">취소</button>
            )}
          </div>
        </Card>
      ))}
    </div>
  );
}

// ── Page ────────────────────────────────────────────────────────────────────

export default function MatchingPage() {
  const { data: fills = [] } = useQuery<FillDto[]>({
    queryKey: ["matching", "fills"],
    queryFn: async () => {
      const r = await authFetch("/api/matching/fills");
      if (!r.ok) return [];
      return r.json();
    },
  });

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-6">
        <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">체결 엔진</h1>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
          CLOB 기반 주문서 — 가격 우선 · 시간 우선 매칭 · 부분체결 · 슬리피지
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* 좌: 주문 입력 */}
        <div className="space-y-4">
          <OrderForm />
        </div>

        {/* 우: 주문 현황 + 체결 이력 */}
        <div className="space-y-4">
          <div>
            <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mb-3">미체결 주문</h2>
            <ActiveOrders />
          </div>

          <div>
            <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mb-3">최근 체결</h2>
            {fills.length === 0 ? (
              <div className="p-4 rounded-xl border border-dashed border-gray-300 dark:border-dracula-line text-center text-xs text-gray-500 dark:text-dracula-comment">
                체결 내역 없음
              </div>
            ) : (
              <div className="space-y-2">
                {fills.slice(0, 10).map((f: FillDto) => (
                  <Card key={f.id} className="flex justify-between items-center p-3 text-xs">
                    <div className="flex items-center gap-2">
                      <span className={`font-bold ${f.side === "BUY" ? "text-[#ff5050]" : "text-[#4a8fd4]"}`}>
                        {f.side === "BUY" ? "매수" : "매도"}
                      </span>
                      <span className="text-gray-900 dark:text-dracula-fg tabular-nums">{f.quantity}주</span>
                      <span className="text-gray-500 dark:text-dracula-comment tabular-nums">@ {won(f.fillPrice)}원</span>
                    </div>
                    <div className="text-right">
                      <p className="text-gray-900 dark:text-dracula-fg tabular-nums">{won(f.amount)}원</p>
                      <p className="text-gray-500 dark:text-dracula-comment tabular-nums">수수료 {won(f.fee)}원</p>
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
