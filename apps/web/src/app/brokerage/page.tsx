"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ShieldCheck, HourglassMedium, CheckCircle, XCircle } from "@phosphor-icons/react";
import { type Icon } from "@phosphor-icons/react";
import { getAccessToken } from "@/services/auth";
import { useBrokerageAccount, useBrokerageBalance, useBrokerageOrders, useBrokerageSettlements, useCancelBrokerageOrder } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { brokerageProviderLabel } from "@/lib/brokerageProvider";
import type { BrokerageOrderResponse, BrokerageSettlementResponse } from "@monticker/types";

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }
function pnlColor(n: number) { return n > 0 ? "text-dracula-red" : n < 0 ? "text-dracula-cyan" : "text-gray-500 dark:text-dracula-comment"; }

const ORDER_STATUS_META: Record<string, { label: string; icon: Icon; color: string }> = {
  SUBMITTED:        { label: "접수됨",   icon: HourglassMedium, color: "text-dracula-orange" },
  FILLED:           { label: "체결 완료", icon: CheckCircle,     color: "text-dracula-green" },
  PARTIALLY_FILLED: { label: "부분 체결", icon: HourglassMedium, color: "text-dracula-cyan" },
  CANCELLED:        { label: "취소됨",   icon: XCircle,          color: "text-gray-500 dark:text-dracula-comment" },
  REJECTED:         { label: "거부됨",   icon: XCircle,          color: "text-dracula-red" },
};

const SETTLEMENT_STATUS_META: Record<string, { label: string; color: string }> = {
  PENDING: { label: "대기 중",   color: "text-dracula-orange" },
  SETTLED: { label: "정산 완료", color: "text-dracula-green" },
  FAILED:  { label: "실패",      color: "text-dracula-red" },
};

function OrderRow({ o }: { o: BrokerageOrderResponse }) {
  const meta = ORDER_STATUS_META[o.status] ?? { label: o.status, icon: HourglassMedium, color: "text-gray-500" };
  const { toast } = useToast();
  const cancelOrder = useCancelBrokerageOrder();

  const handleCancel = async () => {
    try {
      await cancelOrder.mutateAsync(o.id);
      toast({ type: "success", title: "취소 완료", message: "주문이 취소되었습니다." });
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
          <span className="text-xs text-gray-500 dark:text-dracula-comment">{o.quantity}주</span>
          <span className={`text-xs ${meta.color}`}>{meta.label}</span>
        </div>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
          {o.avgFillPrice ? `체결가 ₩${fmt(o.avgFillPrice)}` : o.orderType === "LIMIT" ? `지정가 ₩${fmt(o.limitPrice ?? 0)}` : "시장가"}
          {" · "}{new Date(o.submittedAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
        </p>
        {o.rejectReason && <p className="text-xs text-dracula-red mt-0.5">{o.rejectReason}</p>}
      </div>
      {o.status === "SUBMITTED" && (
        <button
          onClick={handleCancel}
          disabled={cancelOrder.isPending}
          className="shrink-0 px-3 py-1.5 rounded-lg border border-dracula-red/40 text-dracula-red text-xs font-medium hover:bg-dracula-red/10 transition-colors disabled:opacity-40"
        >
          {cancelOrder.isPending ? "취소 중..." : "주문 취소"}
        </button>
      )}
    </Card>
  );
}

function SettlementRow({ s }: { s: BrokerageSettlementResponse }) {
  const meta = SETTLEMENT_STATUS_META[s.status] ?? { label: s.status, color: "text-gray-500" };
  return (
    <Card className="p-4 flex items-center justify-between gap-3">
      <div className="min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-xs font-medium ${s.side === "BUY" ? "text-dracula-red" : "text-dracula-cyan"}`}>{s.side === "BUY" ? "매수" : "매도"}</span>
          <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">{s.symbol}</span>
          <span className={`text-xs ${meta.color}`}>{meta.label}</span>
        </div>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
          정산 예정일 {new Date(s.settleDate).toLocaleDateString("ko-KR")}
        </p>
      </div>
      <p className={`text-sm font-semibold shrink-0 ${s.side === "BUY" ? "text-dracula-red" : "text-dracula-cyan"}`}>
        {s.side === "BUY" ? "-" : "+"}{fmt(s.netAmount)}원
      </p>
    </Card>
  );
}

export default function BrokerageDashboardPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [tab, setTab] = useState<"holdings" | "orders" | "settlements">("holdings");
  const [ordersPage, setOrdersPage] = useState(0);
  const [settlementsPage, setSettlementsPage] = useState(0);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: account, isLoading: accountLoading } = useBrokerageAccount();
  const { data: balance, isLoading: balanceLoading } = useBrokerageBalance(!!account);
  const { data: ordersData, isLoading: ordersLoading } = useBrokerageOrders(ordersPage, !!account && tab === "orders");
  const { data: settlementsData, isLoading: settlementsLoading } = useBrokerageSettlements(settlementsPage, !!account && tab === "settlements");

  if (!isLoggedIn) return (
    <div className="max-w-3xl mx-auto p-6 text-center py-20">
      <p className="text-gray-500 dark:text-dracula-comment mb-4">실전투자를 이용하려면 로그인이 필요합니다.</p>
      <Link href="/login" className="inline-block bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg text-white px-6 py-2 rounded-lg font-medium hover:opacity-90 active:scale-[0.98] transition-all duration-150">로그인</Link>
    </div>
  );

  if (accountLoading) return (
    <div className="max-w-3xl mx-auto p-6">
      <div className="h-32 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer mb-4" />
    </div>
  );

  if (!account) return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 text-center">
      <Card className="p-8">
        <ShieldCheck size={32} weight="duotone" className="text-gray-400 dark:text-dracula-comment mx-auto mb-3" aria-hidden />
        <p className="text-gray-900 dark:text-dracula-fg font-semibold mb-1">연동된 증권사 계좌가 없습니다</p>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mb-4">
          BYOK(Bring Your Own Key) 방식으로 본인 명의의 증권사 API 키를 연동해 실전 주문을 체결할 수 있습니다.
        </p>
        <Link href="/brokerage/connect" className="inline-block px-5 py-2.5 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-sm font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150">
          계좌 연동하기
        </Link>
      </Card>
    </div>
  );

  const holdings = balance?.holdings ?? [];
  const orders = ordersData?.content ?? [];
  const settlements = settlementsData?.content ?? [];

  return (
    <div className="max-w-3xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-8 flex items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-bold text-gray-900 dark:text-dracula-fg">실전투자</h1>
          <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">계좌번호 {account.accountNumber}</p>
        </div>
        <Link href="/brokerage/orders"
          className="shrink-0 px-4 py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-sm font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150">
          주문하기
        </Link>
      </div>

      {/* 계좌 상태 */}
      <Card className="p-4 flex items-center justify-between gap-4 mb-6" outerClassName="mb-6">
        <div className="flex items-center gap-3">
          <ShieldCheck size={22} weight="duotone" className={account.tokenValid ? "text-dracula-green" : "text-dracula-orange"} aria-hidden />
          <div>
            <p className="text-xs text-gray-500 dark:text-dracula-comment">{brokerageProviderLabel(account.provider)} 계좌 연동됨</p>
            <p className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mt-0.5">
              {account.tokenValid ? "정상 연결" : "재인증 필요"}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {!account.tokenValid && (
            <Link href="/brokerage/connect"
              className="px-3 py-1.5 rounded-lg bg-dracula-red/15 text-dracula-red text-xs font-semibold hover:bg-dracula-red/25 transition-colors">
              재연동
            </Link>
          )}
          <Badge variant={account.isActive ? "up" : "neutral"}>{account.isActive ? "활성" : "비활성"}</Badge>
        </div>
      </Card>

      {/* 잔고 요약 */}
      {balanceLoading ? (
        <div className="h-24 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer mb-6" />
      ) : balance && (
        <div className="grid grid-cols-2 gap-3 mb-6">
          <Card className="p-4">
            <p className="text-xs text-gray-500 dark:text-dracula-comment">가용 현금</p>
            <p className="text-lg font-bold font-mono text-gray-900 dark:text-dracula-fg mt-0.5">₩{fmt(balance.cash)}</p>
          </Card>
          <Card className="p-4">
            <p className="text-xs text-gray-500 dark:text-dracula-comment">총 평가금액</p>
            <p className="text-lg font-bold font-mono text-gray-900 dark:text-dracula-fg mt-0.5">₩{fmt(balance.totalEvaluated)}</p>
          </Card>
        </div>
      )}

      {/* 탭 */}
      <div className="flex gap-1 mb-6 border-b border-gray-200 dark:border-dracula-line">
        {([
          { key: "holdings", label: "보유종목" },
          { key: "orders", label: "주문내역" },
          { key: "settlements", label: "정산내역" },
        ] as const).map(t => (
          <button key={t.key} onClick={() => setTab(t.key)}
            className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
              ${tab === t.key ? "border-blue-600 dark:border-dracula-purple text-blue-600 dark:text-dracula-purple" : "border-transparent text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"}`}>
            {t.label}
          </button>
        ))}
      </div>

      {/* 보유종목 */}
      {tab === "holdings" && (
        holdings.length === 0 ? (
          <div className="text-center py-16 border border-dashed border-gray-300 dark:border-dracula-line rounded-xl text-gray-500 dark:text-dracula-comment text-sm">
            보유 중인 종목이 없습니다.
          </div>
        ) : (
          <div className="space-y-2">
            {holdings.map(h => {
              const pnl = (h.currentPrice - h.avgPrice) * h.quantity;
              const pnlRate = h.avgPrice > 0 ? ((h.currentPrice - h.avgPrice) / h.avgPrice) * 100 : 0;
              return (
                <Card key={h.symbol} className="p-4 flex items-center justify-between gap-3">
                  <div>
                    <p className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">{h.symbol}</p>
                    <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">{h.quantity}주 · 평단 ₩{fmt(h.avgPrice)}</p>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-semibold font-mono text-gray-900 dark:text-dracula-fg">₩{fmt(h.currentPrice * h.quantity)}</p>
                    <p className={`text-xs font-mono ${pnlColor(pnl)}`}>{pnl >= 0 ? "+" : ""}{fmt(pnl)} ({pnlRate.toFixed(2)}%)</p>
                  </div>
                </Card>
              );
            })}
          </div>
        )
      )}

      {/* 주문내역 */}
      {tab === "orders" && (
        ordersLoading ? (
          <div className="space-y-2">{[1, 2, 3].map(i => <div key={i} className="h-16 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}</div>
        ) : orders.length === 0 ? (
          <div className="text-center py-16 border border-dashed border-gray-300 dark:border-dracula-line rounded-xl text-gray-500 dark:text-dracula-comment text-sm">
            주문 내역이 없습니다.
          </div>
        ) : (
          <>
            <div className="space-y-2">{orders.map(o => <OrderRow key={o.id} o={o} />)}</div>
            {(ordersData?.totalPages ?? 0) > 1 && (
              <div className="flex justify-center gap-3 mt-6">
                {ordersPage > 0 && <button onClick={() => setOrdersPage(p => p - 1)} className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-sm font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment transition-all duration-150">이전</button>}
                {ordersPage < (ordersData?.totalPages ?? 1) - 1 && <button onClick={() => setOrdersPage(p => p + 1)} className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-sm font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment transition-all duration-150">다음</button>}
              </div>
            )}
          </>
        )
      )}

      {/* 정산내역 */}
      {tab === "settlements" && (
        settlementsLoading ? (
          <div className="space-y-2">{[1, 2, 3].map(i => <div key={i} className="h-16 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />)}</div>
        ) : settlements.length === 0 ? (
          <div className="text-center py-16 border border-dashed border-gray-300 dark:border-dracula-line rounded-xl text-gray-500 dark:text-dracula-comment text-sm">
            정산 내역이 없습니다.
          </div>
        ) : (
          <>
            <div className="space-y-2">{settlements.map(s => <SettlementRow key={s.id} s={s} />)}</div>
            {(settlementsData?.totalPages ?? 0) > 1 && (
              <div className="flex justify-center gap-3 mt-6">
                {settlementsPage > 0 && <button onClick={() => setSettlementsPage(p => p - 1)} className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-sm font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment transition-all duration-150">이전</button>}
                {settlementsPage < (settlementsData?.totalPages ?? 1) - 1 && <button onClick={() => setSettlementsPage(p => p + 1)} className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-sm font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment transition-all duration-150">다음</button>}
              </div>
            )}
          </>
        )
      )}

      <p className="text-xs text-gray-500 dark:text-dracula-comment text-center mt-8">
        실제 자금이 이동하는 실전투자 계좌입니다. 투자에 대한 책임은 본인에게 있습니다.
      </p>
    </div>
  );
}
