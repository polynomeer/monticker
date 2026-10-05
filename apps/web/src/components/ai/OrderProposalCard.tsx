"use client";

import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Sparkle } from "@phosphor-icons/react";
import { Btn, Pill } from "@/components/terminal";
import { authFetch } from "@/services/api";
import type { OrderProposal } from "@monticker/types";

const DEFAULT_DISCLAIMER = "이 제안은 투자자문이 아니며, 모의투자 참고용 시뮬레이션 정보입니다.";

interface Props {
  stockId: number;
  /** 승인 시 방향(BUY/SELL)만 전달 — 실제 주문 제출은 호출자의 주문 폼이 사용자 확인을 거쳐 별도로 담당한다(ADR-036). */
  onApprove: (side: "BUY" | "SELL") => void;
  /** 실브로커리지 등 문맥에 따라 다른 면책 문구를 쓸 수 있게 오버라이드 가능. */
  disclaimer?: string;
}

/** ADR-036 — AI 주문 제안 카드. 승인은 제안 상태만 바꿀 뿐 주문을 제출하지 않는다. */
export default function OrderProposalCard({ stockId, onApprove, disclaimer = DEFAULT_DISCLAIMER }: Props) {
  const [proposal, setProposal] = useState<OrderProposal | null>(null);

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
      return res.json() as Promise<OrderProposal>;
    },
    onSuccess: (data) => setProposal(data),
  });

  const approveMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch(`/api/ai/order-proposals/${proposal!.id}/approve`, { method: "POST" });
      if (!res.ok) { const e = await res.json(); throw new Error(e.message ?? "승인 실패"); }
      return res.json() as Promise<OrderProposal>;
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
      return res.json() as Promise<OrderProposal>;
    },
    onSuccess: (data) => setProposal(data),
  });

  const isExpired = proposal ? new Date(proposal.expiresAt).getTime() < Date.now() : false;
  const sideStyle = proposal?.side === "BUY" ? "border-up/40 bg-up/5"
    : proposal?.side === "SELL" ? "border-down/40 bg-down/5"
    : "border-tm-line2 bg-tm-inner";
  const sideLabel = proposal?.side === "BUY" ? "매수 제안" : proposal?.side === "SELL" ? "매도 제안" : "보류 제안";
  const sideColor = proposal?.side === "BUY" ? "text-up" : proposal?.side === "SELL" ? "text-down" : "text-tm-muted";

  return (
    <section className="flex flex-col gap-3 rounded-[10px] bg-tm-panel p-3.5">
      <div className="flex items-center justify-between gap-2">
        <h2 className="m-0 inline-flex items-center gap-1.5 text-15 font-bold text-dracula-fg">
          <Sparkle size={14} weight="bold" className="text-dracula-purple" aria-hidden /> AI 주문 제안
        </h2>
        <Btn kind="soft" size="sm" onClick={() => createMutation.mutate()} disabled={createMutation.isPending}>
          {createMutation.isPending ? "생성 중..." : "제안 받기"}
        </Btn>
      </div>
      <p className="m-0 text-2xs text-tm-muted">{disclaimer}</p>

      {createMutation.isError && (
        <p role="alert" className="m-0 text-xs text-[#ff8a8a]">{(createMutation.error as Error).message}</p>
      )}

      {proposal && (
        <div className={`flex flex-col gap-2 rounded-[10px] border p-3 text-xs ${sideStyle}`}>
          <div className="flex items-center justify-between gap-2">
            <span className={`font-bold ${sideColor}`}>{sideLabel}</span>
            <Pill tone={proposal.status === "APPROVED" ? "green" : proposal.status === "REJECTED" || isExpired ? "muted" : "purple"}>
              {proposal.status === "APPROVED" ? "승인됨"
                : proposal.status === "REJECTED" ? "거부됨"
                : isExpired ? "만료됨"
                : `~${new Date(proposal.expiresAt).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" })}까지 유효`}
            </Pill>
          </div>
          <p className="m-0 leading-relaxed text-tm-soft">{proposal.reasoning}</p>

          {proposal.status === "PENDING" && !isExpired && proposal.side !== "HOLD" && (
            <div className="grid grid-cols-2 gap-2 pt-1">
              <Btn kind="ghost" size="sm" onClick={() => rejectMutation.mutate()} disabled={rejectMutation.isPending}>
                거부
              </Btn>
              <Btn kind="primary" size="sm" onClick={() => approveMutation.mutate()} disabled={approveMutation.isPending}>
                승인 — 주문폼에 반영
              </Btn>
            </div>
          )}
          {(approveMutation.isError || rejectMutation.isError) && (
            <p role="alert" className="m-0 text-[#ff8a8a]">{((approveMutation.error ?? rejectMutation.error) as Error).message}</p>
          )}
        </div>
      )}
    </section>
  );
}
