"use client";

import { useEffect, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { cn } from "@/lib/utils";
import { CheckCircle, Prohibit, Check, X, Sparkle } from "@phosphor-icons/react";
import { authFetch } from "@/services/api";
import { Card } from "@/components/ui/Card";
import OrderBook from "@/components/stock/OrderBook";

const STOCKS = [
  { id: 2, label: "삼성전자" }, { id: 3, label: "SK하이닉스" },
  { id: 9, label: "현대차" },   { id: 10, label: "NAVER" },
  { id: 5, label: "AAPL" },    { id: 6, label: "NVDA" },
];

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

interface OrderProposalDto {
  id: number; stockId: number; side: "BUY" | "SELL" | "HOLD";
  reasoning: string; status: "PENDING" | "APPROVED" | "REJECTED";
  createdAt: string; expiresAt: string;
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

// ── AI 주문 제안 (ADR-036) ────────────────────────────────────────────────────

function AiProposalCard({ stockId, onApprove }: { stockId: number; onApprove: (side: "BUY" | "SELL") => void }) {
  const [proposal, setProposal] = useState<OrderProposalDto | null>(null);

  // 종목이 바뀌면 이전 제안은 더 이상 유효하지 않다.
  useEffect(() => setProposal(null), [stockId]);

  const createMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch("/api/ai/order-proposals", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId }),
      });
      if (!res.ok) { const e = await res.json(); throw new Error(e.message ?? "제안 생성 실패"); }
      return res.json() as Promise<OrderProposalDto>;
    },
    onSuccess: (data) => setProposal(data),
  });

  const approveMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch(`/api/ai/order-proposals/${proposal!.id}/approve`, { method: "POST" });
      if (!res.ok) { const e = await res.json(); throw new Error(e.message ?? "승인 실패"); }
      return res.json() as Promise<OrderProposalDto>;
    },
    onSuccess: (data) => {
      setProposal(data);
      if (data.side === "BUY" || data.side === "SELL") onApprove(data.side);
    },
  });

  const rejectMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch(`/api/ai/order-proposals/${proposal!.id}/reject`, { method: "POST" });
      if (!res.ok) { const e = await res.json(); throw new Error(e.message ?? "거부 실패"); }
      return res.json() as Promise<OrderProposalDto>;
    },
    onSuccess: (data) => setProposal(data),
  });

  const isExpired = proposal ? new Date(proposal.expiresAt).getTime() < Date.now() : false;
  const sideStyle = proposal?.side === "BUY" ? "border-[#ff5050]/30 bg-[#ff5050]/5"
    : proposal?.side === "SELL" ? "border-[#4a8fd4]/30 bg-[#4a8fd4]/5"
    : "border-gray-300 dark:border-dracula-line bg-gray-50 dark:bg-dracula-bg";
  const sideLabel = proposal?.side === "BUY" ? "매수 제안" : proposal?.side === "SELL" ? "매도 제안" : "보류 제안";
  const sideColor = proposal?.side === "BUY" ? "text-[#ff5050]" : proposal?.side === "SELL" ? "text-[#4a8fd4]" : "text-gray-500 dark:text-dracula-comment";

  return (
    <Card className="p-5 space-y-3">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg inline-flex items-center gap-1.5">
          <Sparkle size={14} weight="bold" className="text-dracula-purple" aria-hidden /> AI 주문 제안
        </h2>
        <button onClick={() => createMutation.mutate()} disabled={createMutation.isPending}
          className="shrink-0 text-xs px-3 py-1.5 rounded-lg bg-dracula-purple/10 text-dracula-purple font-medium hover:bg-dracula-purple/20 active:scale-95 transition-all duration-150 disabled:opacity-40">
          {createMutation.isPending ? "생성 중..." : "제안 받기"}
        </button>
      </div>
      <p className="text-[11px] text-gray-400 dark:text-dracula-comment">
        이 제안은 투자자문이 아니며, 모의투자 참고용 시뮬레이션 정보입니다.
      </p>

      {createMutation.isError && (
        <p className="text-xs text-dracula-red">{(createMutation.error as Error).message}</p>
      )}

      {proposal && (
        <div className={`p-3 rounded-xl border text-xs space-y-2 animate-fade-up ${sideStyle}`}>
          <div className="flex items-center justify-between">
            <span className={`font-bold ${sideColor}`}>{sideLabel}</span>
            <span className="text-gray-400 dark:text-dracula-comment">
              {proposal.status === "APPROVED" ? "승인됨"
                : proposal.status === "REJECTED" ? "거부됨"
                : isExpired ? "만료됨"
                : `~${new Date(proposal.expiresAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}까지 유효`}
            </span>
          </div>
          <p className="text-gray-600 dark:text-dracula-fg">{proposal.reasoning}</p>

          {proposal.status === "PENDING" && !isExpired && proposal.side !== "HOLD" && (
            <div className="grid grid-cols-2 gap-2 pt-1">
              <button onClick={() => rejectMutation.mutate()} disabled={rejectMutation.isPending}
                className="py-1.5 rounded-lg border border-gray-300 dark:border-dracula-line text-gray-500 dark:text-dracula-comment text-xs font-medium hover:bg-gray-100 dark:hover:bg-dracula-line/30 active:scale-95 transition-all duration-150 disabled:opacity-40">
                거부
              </button>
              <button onClick={() => approveMutation.mutate()} disabled={approveMutation.isPending}
                className="py-1.5 rounded-lg bg-dracula-purple text-white text-xs font-semibold hover:opacity-90 active:scale-95 transition-all duration-150 disabled:opacity-40">
                승인 — 주문폼에 반영
              </button>
            </div>
          )}
          {(approveMutation.isError || rejectMutation.isError) && (
            <p className="text-dracula-red">{((approveMutation.error ?? rejectMutation.error) as Error).message}</p>
          )}
        </div>
      )}
    </Card>
  );
}

// ── Order Form ──────────────────────────────────────────────────────────────

function OrderForm({ stockId, setStockId, presetSide }: { stockId: number; setStockId: (id: number) => void; presetSide?: "BUY" | "SELL" }) {
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
          <div className="relative flex h-9 rounded-lg overflow-hidden border border-gray-300 dark:border-dracula-line">
            <button onClick={() => setSide("BUY")}
              style={{ clipPath: "polygon(0 0, 100% 0, 82% 100%, 0 100%)" }}
              className={cn(
                "flex-1 pl-3 pr-4 text-sm font-semibold transition-colors duration-150 -mr-3",
                side === "BUY" ? "bg-[#ff5050] text-white" : "bg-white dark:bg-dracula-bg text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"
              )}>
              매수
            </button>
            <button onClick={() => setSide("SELL")}
              style={{ clipPath: "polygon(18% 0, 100% 0, 100% 100%, 0 100%)" }}
              className={cn(
                "flex-1 pl-4 pr-3 text-sm font-semibold transition-colors duration-150",
                side === "SELL" ? "bg-[#4a8fd4] text-white" : "bg-white dark:bg-dracula-bg text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"
              )}>
              매도
            </button>
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

// ── Order History (미체결 주문 / 최근 체결 탭 통합) ──────────────────────────

function OrderHistoryPanel() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<"orders" | "fills">("orders");

  const { data: orders = [] } = useQuery<OrderDto[]>({
    queryKey: ["matching", "orders"],
    queryFn: async () => {
      const r = await authFetch("/api/matching/orders");
      if (!r.ok) return [];
      return r.json();
    },
    refetchInterval: 5000,
  });

  const { data: fills = [] } = useQuery<FillDto[]>({
    queryKey: ["matching", "fills"],
    queryFn: async () => {
      const r = await authFetch("/api/matching/fills");
      if (!r.ok) return [];
      return r.json();
    },
  });

  const cancelMutation = useMutation({
    mutationFn: async (orderId: number) => {
      await authFetch(`/api/matching/orders/${orderId}`, { method: "DELETE" });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["matching", "orders"] }),
  });

  return (
    <Card className="overflow-hidden">
      <div className="flex gap-1 px-3 pt-2 border-b border-gray-200 dark:border-dracula-line">
        {([
          { key: "orders", label: `미체결 주문 (${orders.length})` },
          { key: "fills",  label: "최근 체결" },
        ] as const).map(t => (
          <button key={t.key} onClick={() => setTab(t.key)}
            className={cn(
              "px-3 py-2 text-xs font-medium transition-colors border-b-2 -mb-px",
              tab === t.key
                ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple"
                : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"
            )}>
            {t.label}
          </button>
        ))}
      </div>

      <div className="p-3">
        {tab === "orders" ? (
          orders.length === 0 ? (
            <p className="py-8 text-center text-xs text-gray-500 dark:text-dracula-comment">미체결 주문 없음</p>
          ) : (
            <div className="space-y-2">
              {orders.map((o: OrderDto) => (
                <div key={o.id} className="flex items-center justify-between p-3 rounded-lg bg-gray-50 dark:bg-dracula-bg text-xs">
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
                </div>
              ))}
            </div>
          )
        ) : (
          fills.length === 0 ? (
            <p className="py-8 text-center text-xs text-gray-500 dark:text-dracula-comment">체결 내역 없음</p>
          ) : (
            <div className="space-y-2">
              {fills.slice(0, 10).map((f: FillDto) => (
                <div key={f.id} className="flex justify-between items-center p-3 rounded-lg bg-gray-50 dark:bg-dracula-bg text-xs">
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
                </div>
              ))}
            </div>
          )
        )}
      </div>
    </Card>
  );
}

// ── Page ────────────────────────────────────────────────────────────────────

export default function MatchingPage() {
  const [stockId, setStockId] = useState(2);
  const [presetSide, setPresetSide] = useState<"BUY" | "SELL" | undefined>(undefined);

  return (
    <div className="max-w-6xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-6">
        <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">체결 엔진</h1>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
          CLOB 기반 주문서 — 가격 우선 · 시간 우선 매칭 · 부분체결 · 슬리피지
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-6">
        {/* 좌: 호가창 + AI 제안 */}
        <div className="space-y-6">
          <OrderBook stockId={stockId} />
          <AiProposalCard stockId={stockId} onApprove={setPresetSide} />
        </div>

        {/* 우: 주문 입력 */}
        <OrderForm stockId={stockId} setStockId={setStockId} presetSide={presetSide} />
      </div>

      {/* 하단: 미체결 주문 / 최근 체결 (탭 통합) */}
      <div className="mt-6">
        <OrderHistoryPanel />
      </div>
    </div>
  );
}
