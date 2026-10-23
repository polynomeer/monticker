"use client";

// 조건부 주문(ADR-032) 한 건의 상태 표기와 감시 취소 버튼 — 표(DataTable)의 셀로 쓴다.
import { useState } from "react";
import type { ConditionalOrderResponse, ConditionalOrderStatus, ConditionalTriggerType } from "@monticker/types";
import { useCancelConditionalOrder } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { ConfirmDialog, KV, Pill, fmtNum, type Tone } from "@/components/terminal";

export const COND_STATUS: Record<ConditionalOrderStatus, { label: string; tone: Tone }> = {
  ACTIVE:    { label: "감시 중",      tone: "cyan" },
  TRIGGERED: { label: "발동 처리 중", tone: "caution" },
  EXECUTED:  { label: "발동 완료",    tone: "green" },
  CANCELLED: { label: "취소됨",       tone: "muted" },
  EXPIRED:   { label: "만료됨",       tone: "muted" },
  FAILED:    { label: "발동 실패",    tone: "red" },
};

export const TRIGGER_LABEL: Record<ConditionalTriggerType, string> = {
  STOP_LOSS: "손절",
  TAKE_PROFIT: "익절",
  PRICE_ABOVE: "돌파",
  PRICE_BELOW: "하락",
};

export const TRIGGER_TONE: Record<ConditionalTriggerType, Tone> = {
  STOP_LOSS: "red",
  TAKE_PROFIT: "green",
  PRICE_ABOVE: "purple",
  PRICE_BELOW: "pink",
};

/** STOP_LOSS/PRICE_BELOW는 현재가 <= trigger, TAKE_PROFIT/PRICE_ABOVE는 현재가 >= trigger 에서 발동. */
export function isBelowTrigger(t: ConditionalTriggerType) {
  return t === "STOP_LOSS" || t === "PRICE_BELOW";
}

/** 상태 배지 + 실시세 경고(ADR-060) + 만료일(V-L2) + 실패 사유. */
export function ConditionalStatusCell({ o }: { o: ConditionalOrderResponse }) {
  const meta = COND_STATUS[o.status] ?? { label: o.status, tone: "muted" as const };
  return (
    <div className="flex flex-col items-start gap-1">
      <span className="flex items-center gap-1.5">
        <Pill tone={meta.tone}>{meta.label}</Pill>
        {o.ocoGroupId && <Pill tone="purple">OCO</Pill>}
      </span>
      {/* V-L2 — 조건부 주문이 잊혀진 채 무기한 남지 않도록 만료일을 도입했다 — 언제 사라지는지 보여준다. */}
      {o.status === "ACTIVE" && o.expiresAt && (
        <span className="num text-2xs text-tm-muted">
          {new Date(o.expiresAt).toLocaleDateString("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit" })} 만료
        </span>
      )}
      {o.failReason && <span className="max-w-[240px] whitespace-normal text-2xs leading-snug text-[#ff8a8a]">{o.failReason}</span>}
      {(o.priceFeed === "NONE" || o.priceFeed === "STALE") && (
        // ADR-060 — 실시세가 없으면 조건을 만족해도 발동하지 않는다. 사용자가 보호받고 있다고 믿지 않게 분명히 말한다.
        <p role="status" className="m-0 max-w-[260px] whitespace-normal text-2xs leading-snug text-dracula-yellow">
          {o.priceFeed === "NONE"
            ? "실시간 시세가 연결되지 않은 종목이라 지금은 발동하지 않습니다. 시세가 다시 연결되면 자동으로 감시합니다."
            : "이 종목의 실시간 시세가 장중에 끊겼습니다. 끊긴 동안에는 발동하지 않습니다."}
        </p>
      )}
    </div>
  );
}

/**
 * ACTIVE일 때만 — 감시 취소. 취소하면 이 주문의 보호(손절 등)가 사라지므로 한 번 확인받는다.
 * "해지"는 계좌 연동 해지와 헷갈려서 "감시 취소"라고 부른다(디자인 리뷰 P0).
 */
export function ConditionalCancelButton({ o }: { o: ConditionalOrderResponse }) {
  const { toast } = useToast();
  const cancelOrder = useCancelConditionalOrder();
  const [confirming, setConfirming] = useState(false);
  if (o.status !== "ACTIVE") return null;

  const handleCancel = async () => {
    try {
      await cancelOrder.mutateAsync(o.id);
      toast({ type: "success", title: "감시 취소 완료", message: "조건부 주문 감시를 취소했습니다." });
    } catch (e) {
      toast({ type: "error", title: "감시 취소 실패", message: (e as Error).message });
    } finally {
      setConfirming(false);
    }
  };

  const below = isBelowTrigger(o.triggerType);
  return (
    <>
      <button type="button" onClick={() => setConfirming(true)} disabled={cancelOrder.isPending}
        className="inline-flex h-7 items-center whitespace-nowrap rounded-lg border border-[#6b3a44] px-2.5 text-xs font-semibold text-[#ff8a8a] hover:bg-[#3d252b] disabled:opacity-40">
        {cancelOrder.isPending ? "취소 중..." : "감시 취소"}
      </button>
      {confirming && (
        <ConfirmDialog
          title="조건부 주문 감시를 취소할까요?"
          cancelLabel="그대로 두기"
          confirmLabel={cancelOrder.isPending ? "취소 중..." : "감시 취소"}
          confirmKind="danger"
          pending={cancelOrder.isPending}
          onCancel={() => setConfirming(false)}
          onConfirm={handleCancel}
        >
          <div className="flex flex-col gap-2 text-left">
            <KV k="종목" v={o.symbol} />
            <KV k="조건" v={`${TRIGGER_LABEL[o.triggerType]} · ${fmtNum(o.triggerPrice)}원 ${below ? "이하" : "이상"}`} mono={false} />
            <KV k="주문" v={`${o.side === "BUY" ? "매수" : "매도"} ${fmtNum(o.quantity)}주 · ${o.orderType === "LIMIT" ? `지정가 ${fmtNum(o.limitPrice)}원` : "시장가"}`} mono={false} />
          </div>
          <p className="m-0 text-xs leading-relaxed text-tm-muted">
            취소하면 이 가격 조건을 더 이상 감시하지 않습니다{o.ocoGroupId ? " · OCO로 묶인 다른 조건은 따로 취소하기 전까지 계속 감시합니다" : ""}.
          </p>
        </ConfirmDialog>
      )}
    </>
  );
}
