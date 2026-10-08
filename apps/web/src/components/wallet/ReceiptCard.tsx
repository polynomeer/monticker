"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { Btn, Chip, Icon, KV, fmtNum } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { EMOTIONS } from "./emotions";
import { emotionKey, fetchEmotion } from "./useTradeEmotions";
import { fmtTime } from "@/components/portfolio/format";
import { buildReceiptSteps, type PaperSettlement, type ReceiptOrder, type StepState } from "./receiptSteps";

export interface Receipt {
  tradeId: number;
  stockSymbol: string;
  stockName: string;
  side: string;
  quantity: number;
  filledPrice: number;
  orderedAmount: number;
  filledAmount: number;
  fee: number;
  settledAmount: number;
  tradedAt: string;
  status: string;
  balanceBefore: number | null;
  balanceAfter: number | null;
  /** ADR-096 — 이 거래를 만든 주문의 진행 기록(지정가는 예약 잠금 단계가 생긴다). 옛 거래는 null */
  order?: ReceiptOrder | null;
}

/** 거래의 T+2 정산 상태 — 정산 레코드가 아직 없으면 null */
function useTradeSettlement(tradeId: number) {
  return useQuery<PaperSettlement | null>({
    queryKey: ["settlement", "paper", "trade", tradeId],
    queryFn: async () => {
      const r = await authFetch(`/api/settlement/paper/trade/${tradeId}`);
      return r.ok ? r.json() : null;
    },
    staleTime: 60_000,
  });
}

const STEP_STYLE: Record<StepState, { icon: "check" | "clock" | "x"; cls: string; sr: string }> = {
  done: { icon: "check", cls: "bg-dracula-green text-tm-page", sr: "완료" },
  partial: { icon: "clock", cls: "border-2 border-dracula-purple text-dracula-purple", sr: "진행 중" },
  pending: { icon: "clock", cls: "border-2 border-dashed border-dracula-cyan text-dracula-cyan", sr: "대기" },
  cancelled: { icon: "x", cls: "border-2 border-tm-line2 text-tm-muted", sr: "잔량 취소" },
};

function Steps({ receipt, settlement }: { receipt: Receipt; settlement: PaperSettlement | null | undefined }) {
  const steps = buildReceiptSteps(receipt, settlement);
  return (
    <ol className="m-0 flex list-none items-start p-0" aria-label="주문 진행 단계">
      {steps.map((s, i) => {
        const st = STEP_STYLE[s.state];
        return (
          <li key={s.key} className="contents">
            <div className="flex min-w-16 flex-[1_1_0] flex-col items-center gap-[5px] text-center">
              <span className={cn("grid h-[26px] w-[26px] place-items-center rounded-full", st.cls)}>
                <Icon name={st.icon} size={13} strokeWidth={s.state === "done" ? 3 : 2} />
              </span>
              <span className="text-xs font-semibold">{s.name}</span>
              <span className="num text-[0.65625rem] text-tm-muted">{s.t}</span>
              {s.detail && <span className="num text-[0.65625rem] text-tm-muted">{s.detail}</span>}
              <span className="sr-only">{st.sr}</span>
            </div>
            {i < steps.length - 1 && <div aria-hidden className={cn("mt-3 h-0.5 flex-[1_1_12px]", steps[i + 1].state === "done" ? "bg-dracula-green" : "bg-tm-line2")} />}
          </li>
        );
      })}
    </ol>
  );
}

/** 여러 번 나눠 체결된 주문이면 체결 내역을 모두 보여 준다(이 영수증의 체결은 강조). */
function FillList({ order }: { order: ReceiptOrder }) {
  if (order.fills.length < 2 && !order.cancelReason) return null;
  return (
    <div className="flex flex-col gap-1 text-2xs text-tm-muted">
      {order.fills.length >= 2 && (
        <ul className="m-0 list-none p-0" aria-label="주문 체결 내역">
          {order.fills.map((f) => (
            <li key={f.fillId} className={cn("flex justify-between gap-2", f.thisTrade && "font-semibold text-dracula-fg")}>
              <span className="num">{fmtTime(f.filledAt)}</span>
              <span className="num">{fmtNum(f.quantity)}주 × {fmtNum(f.price)}{f.thisTrade ? " · 이 영수증" : ""}</span>
            </li>
          ))}
        </ul>
      )}
      {order.cancelReason && <span>잔량 취소 사유: {order.cancelReason}</span>}
    </div>
  );
}

/**
 * 시안 Wallet "투자 영수증" — 단계 진행 + 체결 내역 + 감정 태그.
 * 체결 직후 모달(TradeReceipt)과 지갑 화면 패널이 같이 쓴다.
 */
export function ReceiptCard({ receipt, onSaved, onSkip }: { receipt: Receipt; onSaved?: () => void; onSkip?: () => void }) {
  const qc = useQueryClient();
  const { data: settlement } = useTradeSettlement(receipt.tradeId);
  const { data: saved } = useQuery({ queryKey: emotionKey(receipt.tradeId), queryFn: () => fetchEmotion(receipt.tradeId), staleTime: 5 * 60_000 });
  const [emotion, setEmotion] = useState<string | null>(null);
  const [memo, setMemo] = useState("");
  useEffect(() => {
    if (saved) { setEmotion(saved.emotion); setMemo(saved.memo ?? ""); }
  }, [saved]);

  const save = useMutation({
    mutationFn: async () => {
      if (!emotion) return;
      const r = await authFetch(`/api/paper/trades/${receipt.tradeId}/emotion`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ emotion, memo: memo || null }),
      });
      if (!r.ok) throw new Error("감정 태그 저장 실패");
    },
    // 거래 내역(["paper","history"])도 감정 태그를 함께 싣는다 — 같이 갱신한다
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["wallet"] }); qc.invalidateQueries({ queryKey: ["paper", "history"] }); onSaved?.(); },
    // 기존 동작 유지 — 저장 실패여도 영수증 흐름은 닫는다
    onError: () => onSaved?.(),
  });

  const isBuy = receipt.side === "BUY";
  const slip = receipt.orderedAmount > 0 ? ((receipt.filledAmount - receipt.orderedAmount) / receipt.orderedAmount) * 100 : null;
  const dirty = emotion !== (saved?.emotion ?? null) || memo !== (saved?.memo ?? "");

  return (
    <>
      <div className="flex justify-between gap-3">
        <span className="font-bold">{receipt.stockName} · {isBuy ? "매수" : "매도"}</span>
        <span className="num text-2xs text-tm-muted">#{receipt.tradeId}</span>
      </div>
      <Steps receipt={receipt} settlement={settlement} />
      {receipt.order && <FillList order={receipt.order} />}
      <div className="flex flex-col gap-[9px] rounded-[10px] bg-tm-inner p-3.5">
        <KV k="체결" v={`${fmtNum(receipt.quantity)}주 × ${fmtNum(receipt.filledPrice)}`} />
        <KV k="체결금" v={fmtNum(receipt.filledAmount)} />
        <KV k="수수료" v={fmtNum(receipt.fee)} />
        <KV k="슬리피지" v={slip == null ? "—" : `${slip > 0 ? "+" : ""}${slip.toFixed(2)}%`} valueClassName={slip ? "text-dracula-yellow" : undefined} />
        <div className="h-px bg-tm-line" />
        <KV k={isBuy ? "정산 예정 금액 (차감)" : "정산 예정 금액 (수령)"} v={`${isBuy ? "-" : "+"}${fmtNum(receipt.settledAmount)}`} />
        {receipt.balanceAfter != null && <KV k="체결 후 잔고" v={fmtNum(receipt.balanceAfter)} valueClassName="text-tm-muted" />}
      </div>
      <div className="flex flex-col gap-2">
        <span className="text-13 font-semibold">이 주문을 낼 때 기분은?</span>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="감정 태그">
          {EMOTIONS.map((e) => (
            <Chip key={e.value} active={emotion === e.value} onClick={() => setEmotion(e.value)} className={cn("h-8", emotion !== e.value && "border border-tm-line2 bg-transparent")}>
              {e.label}
            </Chip>
          ))}
        </div>
        <textarea
          aria-label="거래 메모"
          placeholder="메모 (선택)"
          value={memo}
          onChange={(e) => setMemo(e.target.value)}
          rows={2}
          maxLength={500}
          className="w-full resize-none rounded-lg border border-tm-line bg-tm-inner px-3 py-2 text-13 text-dracula-fg outline-none placeholder:text-[#8b92b8] focus:border-dracula-purple"
        />
        <span className="text-2xs text-tm-muted">감정 태그는 리플레이와 투자 행동 점수에서 체결 결과와 함께 복기됩니다.</span>
        <div className="flex gap-2">
          <Btn kind="primary" size="md" className="flex-1" onClick={() => save.mutate()} disabled={!emotion || !dirty || save.isPending}>
            {save.isPending ? "저장 중..." : saved ? "태그 수정" : "저장하기"}
          </Btn>
          {onSkip && <Btn kind="ghost" size="md" onClick={onSkip}>건너뛰기</Btn>}
        </div>
      </div>
    </>
  );
}
