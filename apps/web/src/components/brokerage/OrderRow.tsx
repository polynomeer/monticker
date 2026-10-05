"use client";

import { HourglassMedium, CheckCircle, XCircle, Question } from "@phosphor-icons/react";
import { type Icon } from "@phosphor-icons/react";
import { isUnresolvedOrderStatus, useCancelBrokerageOrder, useSyncBrokerageOrder } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";
import type { BrokerageOrderResponse } from "@monticker/types";

function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

const ORDER_STATUS_META: Record<string, { label: string; icon: Icon; color: string }> = {
  PENDING_SUBMIT:   { label: "제출 중",  icon: HourglassMedium, color: "text-dracula-orange" },
  UNKNOWN:          { label: "확인 중",  icon: Question,        color: "text-amber-700 dark:text-dracula-yellow" },
  SUBMITTED:        { label: "접수됨",   icon: HourglassMedium, color: "text-dracula-orange" },
  FILLED:           { label: "체결 완료", icon: CheckCircle,     color: "text-dracula-green" },
  PARTIALLY_FILLED: { label: "부분 체결", icon: HourglassMedium, color: "text-dracula-cyan" },
  CANCELLED:        { label: "취소됨",   icon: XCircle,          color: "text-gray-500 dark:text-dracula-comment" },
  REJECTED:         { label: "거부됨",   icon: XCircle,          color: "text-dracula-red" },
};

export function OrderRow({ o, showTypeBadge }: { o: BrokerageOrderResponse; showTypeBadge?: boolean }) {
  const meta = ORDER_STATUS_META[o.status] ?? { label: o.status, icon: HourglassMedium, color: "text-gray-500" };
  const { toast } = useToast();
  const cancelOrder = useCancelBrokerageOrder();
  const syncOrder = useSyncBrokerageOrder();
  const unresolved = isUnresolvedOrderStatus(o.status);

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
          {showTypeBadge && (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 dark:bg-dracula-line text-gray-500 dark:text-dracula-comment font-semibold">일반</span>
          )}
          <span className={`text-xs font-medium ${o.side === "BUY" ? "text-dracula-red" : "text-dracula-cyan"}`}>{o.side === "BUY" ? "매수" : "매도"}</span>
          <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">{o.symbol}</span>
          <span className="text-xs text-gray-500 dark:text-dracula-comment">{o.quantity}주</span>
          <span className={`text-xs ${meta.color}`}>{meta.label}</span>
        </div>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
          {o.avgFillPrice ? `체결가 ₩${fmt(o.avgFillPrice)}` : o.orderType === "LIMIT" ? `지정가 ₩${fmt(o.limitPrice ?? 0)}` : "시장가"}
          {" · "}{new Date(o.submittedAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
        </p>
        {unresolved ? (
          // ADR-056 — 응답을 못 받은 주문은 "거부"가 아니다. 체결됐을 수 있으니 다시 내지 말라고 분명히 말한다.
          <p className="text-xs text-amber-700 dark:text-dracula-yellow mt-0.5">
            {o.needsReview
              ? "증권사 주문 내역과 자동으로 맞출 수 없어 확인이 필요합니다. 증권사 앱에서 체결 여부를 확인해주세요."
              : "증권사 응답을 받지 못해 체결 여부를 확인하고 있습니다. 확인이 끝날 때까지 같은 주문을 다시 내지 마세요."}
          </p>
        ) : (
          o.rejectReason && <p className="text-xs text-dracula-red mt-0.5">{o.rejectReason}</p>
        )}
      </div>
      {unresolved && (
        <button
          onClick={() => syncOrder.mutate(o.id)}
          disabled={syncOrder.isPending}
          className="shrink-0 px-3 py-1.5 rounded-lg border border-amber-500/40 dark:border-dracula-yellow/40 text-amber-700 dark:text-dracula-yellow text-xs font-medium hover:bg-amber-500/10 dark:hover:bg-dracula-yellow/10 transition-colors disabled:opacity-40"
        >
          {syncOrder.isPending ? "확인 중..." : "다시 확인"}
        </button>
      )}
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
