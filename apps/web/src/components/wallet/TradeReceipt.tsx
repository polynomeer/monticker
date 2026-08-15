"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  type Icon, ArrowLineDown, ArrowLineUp, CheckCircle,
  HandFist, Plant, Newspaper, Scales, SmileyNervous, Rocket, Users, Drop, Sparkle, Question,
} from "@phosphor-icons/react";
import { authFetch } from "@/services/api";

interface Receipt {
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
}

const EMOTIONS: Array<{ value: string; label: string; icon: Icon }> = [
  { value: "CONFIDENT",      label: "확신",           icon: HandFist },
  { value: "LONG_TERM",      label: "장기 투자",      icon: Plant },
  { value: "NEWS_BASED",     label: "뉴스 보고",      icon: Newspaper },
  { value: "REBALANCING",    label: "비중 조절",      icon: Scales },
  { value: "ANXIOUS",        label: "불안",           icon: SmileyNervous },
  { value: "FOMO",           label: "급등 놓칠까봐",  icon: Rocket },
  { value: "FOLLOWING",      label: "따라삼",         icon: Users },
  { value: "AVERAGING_DOWN", label: "물타기",         icon: Drop },
  { value: "INTUITION",      label: "직감",           icon: Sparkle },
  { value: "OTHER",          label: "기타",           icon: Question },
];

interface Props {
  receipt: Receipt;
  onClose: () => void;
}

function won(n: number) { return n.toLocaleString("ko-KR") + "원"; }

export default function TradeReceipt({ receipt, onClose }: Props) {
  const [step, setStep] = useState<"receipt" | "emotion">("receipt");
  const [selectedEmotion, setSelectedEmotion] = useState<string | null>(null);
  const [memo, setMemo] = useState("");
  const qc = useQueryClient();

  const emotionMutation = useMutation({
    mutationFn: async () => {
      if (!selectedEmotion) return;
      await authFetch(`/api/paper/trades/${receipt.tradeId}/emotion`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ emotion: selectedEmotion, memo: memo || null }),
      });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["wallet"] });
      onClose();
    },
    onError: () => onClose(),
  });

  const isBuy = receipt.side === "BUY";

  return (
    <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
      <div className="w-full max-w-sm bg-white dark:bg-dracula-surface rounded-2xl border border-gray-200 dark:border-dracula-line overflow-hidden shadow-2xl animate-fade-up">
        {step === "receipt" ? (
          <>
            {/* 영수증 헤더 */}
            <div className={`px-6 py-5 text-center ${isBuy ? "bg-dracula-green/10" : "bg-dracula-red/10"}`}>
              <div className={`flex justify-center mb-2 ${isBuy ? "text-dracula-green" : "text-dracula-red"}`}>
                {isBuy ? <ArrowLineDown size={32} weight="bold" aria-hidden /> : <ArrowLineUp size={32} weight="bold" aria-hidden />}
              </div>
              <p className="text-sm text-gray-500 dark:text-dracula-comment">{receipt.stockName}</p>
              <p className="text-lg font-bold text-gray-900 dark:text-dracula-fg">
                {receipt.quantity}주 {isBuy ? "매수" : "매도"} 완료
              </p>
            </div>

            {/* 영수증 항목 */}
            <div className="px-6 py-4 space-y-3 border-t border-dashed border-gray-200 dark:border-dracula-line">
              {[
                { label: "체결 가격",  value: `${receipt.filledPrice.toLocaleString()}원` },
                { label: "주문 금액",  value: won(receipt.orderedAmount) },
                { label: "체결 금액",  value: won(receipt.filledAmount) },
                { label: "수수료",     value: won(receipt.fee), sub: true },
              ].map(r => (
                <div key={r.label} className="flex justify-between items-center">
                  <span className={`text-sm ${r.sub ? "text-gray-500 dark:text-dracula-comment" : "text-gray-900 dark:text-dracula-fg"}`}>{r.label}</span>
                  <span className={`text-sm ${r.sub ? "text-gray-500 dark:text-dracula-comment" : "font-medium text-gray-900 dark:text-dracula-fg"}`}>{r.value}</span>
                </div>
              ))}

              <div className="border-t border-gray-200 dark:border-dracula-line pt-3 flex justify-between items-center">
                <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">
                  {isBuy ? "차감 금액" : "수령 금액"}
                </span>
                <span className={`text-base font-bold ${isBuy ? "text-dracula-red" : "text-dracula-green"}`}>
                  {isBuy ? "-" : "+"}{won(receipt.settledAmount)}
                </span>
              </div>

              {receipt.balanceAfter != null && (
                <div className="flex justify-between items-center">
                  <span className="text-xs text-gray-500 dark:text-dracula-comment">체결 후 잔고</span>
                  <span className="text-xs text-gray-500 dark:text-dracula-comment">{won(receipt.balanceAfter)}</span>
                </div>
              )}

              <div className="flex justify-between items-center">
                <span className="text-xs text-gray-500 dark:text-dracula-comment">체결 시간</span>
                <span className="text-xs text-gray-500 dark:text-dracula-comment">
                  {new Date(receipt.tradedAt).toLocaleTimeString("ko-KR")}
                </span>
              </div>

              <div className="flex justify-between items-center">
                <span className="text-xs text-gray-500 dark:text-dracula-comment">결제 상태</span>
                <span className="text-xs text-dracula-green font-medium inline-flex items-center gap-1">
                  <CheckCircle size={12} weight="bold" aria-hidden /> {receipt.status}
                </span>
              </div>
            </div>

            <div className="px-6 pb-5 space-y-2">
              <button onClick={() => setStep("emotion")}
                className="w-full py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150">
                매수 이유 기록하기 →
              </button>
              <button onClick={onClose}
                className="w-full py-2 text-xs text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg transition-colors">
                건너뛰기
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="px-6 py-5 border-b border-gray-200 dark:border-dracula-line">
              <p className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">이 거래의 이유가 무엇이었나요?</p>
              <p className="text-xs text-gray-500 dark:text-dracula-comment mt-1">나중에 수익률과 연결해서 투자 습관을 분석해드립니다</p>
            </div>

            <div className="px-6 py-4 grid grid-cols-2 gap-2">
              {EMOTIONS.map(em => (
                <button key={em.value}
                  onClick={() => setSelectedEmotion(em.value)}
                  className={`flex items-center gap-2 px-3 py-2.5 rounded-xl text-sm font-medium transition-all border
                    ${selectedEmotion === em.value
                      ? "bg-dracula-purple/20 border-dracula-purple text-dracula-purple"
                      : "border-gray-300 dark:border-dracula-line text-gray-500 dark:text-dracula-comment hover:border-gray-400 dark:hover:border-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg"}`}>
                  <em.icon size={16} weight="bold" aria-hidden />
                  <span>{em.label}</span>
                </button>
              ))}
            </div>

            <div className="px-6 pb-5 space-y-3">
              <textarea
                aria-label="거래 메모"
                placeholder="메모 (선택)"
                value={memo}
                onChange={e => setMemo(e.target.value)}
                rows={2}
                className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm px-3 py-2 resize-none transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 placeholder-gray-400 dark:placeholder-dracula-comment"
              />
              <button
                onClick={() => emotionMutation.mutate()}
                disabled={!selectedEmotion || emotionMutation.isPending}
                className="w-full py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40">
                {emotionMutation.isPending ? "저장 중..." : "저장하기"}
              </button>
              <button onClick={onClose} className="w-full py-2 text-xs text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg transition-colors">
                건너뛰기
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
