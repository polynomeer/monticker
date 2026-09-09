"use client";

import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Sparkle } from "@phosphor-icons/react";
import { Card } from "@/components/ui/Card";
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
      <p className="text-[11px] text-gray-400 dark:text-dracula-comment">{disclaimer}</p>

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
