"use client";

import { CheckCircle, XCircle, HourglassMedium } from "@phosphor-icons/react";
import { type Icon } from "@phosphor-icons/react";
import { useCancelConditionalOrder } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";
import type { ConditionalOrderResponse, ConditionalTriggerType } from "@monticker/types";

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

export function ConditionalOrderRow({ o, showTypeBadge }: { o: ConditionalOrderResponse; showTypeBadge?: boolean }) {
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
          {showTypeBadge && (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-dracula-orange/15 text-dracula-orange font-semibold">조건부</span>
          )}
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

