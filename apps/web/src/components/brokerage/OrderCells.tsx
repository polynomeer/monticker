"use client";

// 실주문(일반) 한 건의 상태 표기와 행 액션 — 표(DataTable)의 셀로 쓴다.
import type { BrokerageOrderResponse } from "@monticker/types";
import { isUnresolvedOrderStatus, useCancelBrokerageOrder, useSyncBrokerageOrder } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { Pill } from "@/components/terminal";
import { ORDER_STATUS } from "./shared";

/** 상태 배지 + (결과 불명/거부일 때) 한 줄 설명. */
export function OrderStatusCell({ o }: { o: BrokerageOrderResponse }) {
  const meta = ORDER_STATUS[o.status] ?? { label: o.status, tone: "muted" as const };
  const unresolved = isUnresolvedOrderStatus(o.status);
  return (
    <div className="flex flex-col items-start gap-1">
      <Pill tone={meta.tone}>{meta.label}</Pill>
      {unresolved ? (
        // ADR-056 — 응답을 못 받은 주문은 "거부"가 아니다. 체결됐을 수 있으니 다시 내지 말라고 분명히 말한다.
        <span className="max-w-[260px] whitespace-normal text-2xs leading-snug text-dracula-yellow">
          {o.needsReview
            ? "증권사 주문 내역과 자동으로 맞출 수 없어 확인이 필요합니다. 증권사 앱에서 체결 여부를 확인해주세요."
            : "증권사 응답을 받지 못해 체결 여부를 확인하고 있습니다. 확인이 끝날 때까지 같은 주문을 다시 내지 마세요."}
        </span>
      ) : (
        o.rejectReason && <span className="max-w-[260px] whitespace-normal text-2xs leading-snug text-[#ff8a8a]">{o.rejectReason}</span>
      )}
    </div>
  );
}

/** 결과 불명이면 "증권사에서 확인"(서버가 증권사와 즉시 대조), 접수됨이면 "주문 취소". */
export function OrderActions({ o }: { o: BrokerageOrderResponse }) {
  const { toast } = useToast();
  const cancelOrder = useCancelBrokerageOrder();
  const syncOrder = useSyncBrokerageOrder();
  const cls = "inline-flex h-7 items-center rounded-lg px-2.5 text-xs font-semibold disabled:opacity-40";

  const handleCancel = async () => {
    try {
      await cancelOrder.mutateAsync(o.id);
      toast({ type: "success", title: "취소 완료", message: "주문이 취소되었습니다." });
    } catch (e) {
      toast({ type: "error", title: "취소 실패", message: (e as Error).message });
    }
  };

  if (isUnresolvedOrderStatus(o.status)) {
    return (
      <button type="button" onClick={() => syncOrder.mutate(o.id)} disabled={syncOrder.isPending}
        className={`${cls} border border-tm-line2 text-dracula-yellow hover:bg-tm-raised`}>
        {syncOrder.isPending ? "확인 중..." : "증권사에서 확인"}
      </button>
    );
  }
  if (o.status === "SUBMITTED") {
    return (
      <button type="button" onClick={handleCancel} disabled={cancelOrder.isPending}
        className={`${cls} border border-[#6b3a44] text-[#ff8a8a] hover:bg-[#3d252b]`}>
        {cancelOrder.isPending ? "취소 중..." : "주문 취소"}
      </button>
    );
  }
  return null;
}

/** 표시 가격 — 체결가가 있으면 체결가, 지정가면 지정가, 시장가면 "시장가". */
export function orderPriceText(o: BrokerageOrderResponse, fmt: (n: number) => string) {
  if (o.avgFillPrice) return fmt(o.avgFillPrice);
  if (o.orderType === "LIMIT") return o.limitPrice != null ? fmt(o.limitPrice) : "—";
  return "시장가";
}
