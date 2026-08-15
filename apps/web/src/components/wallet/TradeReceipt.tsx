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
      <div className="w-full max-w-sm bg-white dark:bg-[#21222c] rounded-2xl border border-gray-200 dark:border-[#44475a] overflow-hidden shadow-2xl animate-fade-up">
        {step === "receipt" ? (
          <>
            {/* 영수증 헤더 */}
            <div className={`px-6 py-5 text-center ${isBuy ? "bg-[#50fa7b]/10" : "bg-[#ff5555]/10"}`}>
              <div className={`flex justify-center mb-2 ${isBuy ? "text-[#50fa7b]" : "text-[#ff5555]"}`}>
                {isBuy ? <ArrowLineDown size={32} weight="bold" aria-hidden /> : <ArrowLineUp size={32} weight="bold" aria-hidden />}
              </div>
              <p className="text-sm text-gray-500 dark:text-[#6272a4]">{receipt.stockName}</p>
              <p className="text-lg font-bold text-gray-900 dark:text-[#f8f8f2]">
                {receipt.quantity}주 {isBuy ? "매수" : "매도"} 완료
              </p>
            </div>

            {/* 영수증 항목 */}
            <div className="px-6 py-4 space-y-3 border-t border-dashed border-gray-200 dark:border-[#44475a]">
              {[
                { label: "체결 가격",  value: `${receipt.filledPrice.toLocaleString()}원` },
                { label: "주문 금액",  value: won(receipt.orderedAmount) },
                { label: "체결 금액",  value: won(receipt.filledAmount) },
                { label: "수수료",     value: won(receipt.fee), sub: true },
              ].map(r => (
                <div key={r.label} className="flex justify-between items-center">
                  <span className={`text-sm ${r.sub ? "text-gray-500 dark:text-[#6272a4]" : "text-gray-900 dark:text-[#f8f8f2]"}`}>{r.label}</span>
                  <span className={`text-sm ${r.sub ? "text-gray-500 dark:text-[#6272a4]" : "font-medium text-gray-900 dark:text-[#f8f8f2]"}`}>{r.value}</span>
                </div>
              ))}

              <div className="border-t border-gray-200 dark:border-[#44475a] pt-3 flex justify-between items-center">
                <span className="text-sm font-semibold text-gray-900 dark:text-[#f8f8f2]">
                  {isBuy ? "차감 금액" : "수령 금액"}
                </span>
                <span className={`text-base font-bold ${isBuy ? "text-[#ff5555]" : "text-[#50fa7b]"}`}>
                  {isBuy ? "-" : "+"}{won(receipt.settledAmount)}
                </span>
              </div>

              {receipt.balanceAfter != null && (
                <div className="flex justify-between items-center">
                  <span className="text-xs text-gray-500 dark:text-[#6272a4]">체결 후 잔고</span>
                  <span className="text-xs text-gray-500 dark:text-[#6272a4]">{won(receipt.balanceAfter)}</span>
                </div>
              )}

              <div className="flex justify-between items-center">
                <span className="text-xs text-gray-500 dark:text-[#6272a4]">체결 시간</span>
                <span className="text-xs text-gray-500 dark:text-[#6272a4]">
                  {new Date(receipt.tradedAt).toLocaleTimeString("ko-KR")}
                </span>
              </div>

              <div className="flex justify-between items-center">
                <span className="text-xs text-gray-500 dark:text-[#6272a4]">결제 상태</span>
                <span className="text-xs text-[#50fa7b] font-medium inline-flex items-center gap-1">
                  <CheckCircle size={12} weight="bold" aria-hidden /> {receipt.status}
                </span>
              </div>
            </div>

            <div className="px-6 pb-5 space-y-2">
              <button onClick={() => setStep("emotion")}
                className="w-full py-2.5 rounded-xl bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150">
                매수 이유 기록하기 →
              </button>
              <button onClick={onClose}
                className="w-full py-2 text-xs text-gray-500 dark:text-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2] transition-colors">
                건너뛰기
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="px-6 py-5 border-b border-gray-200 dark:border-[#44475a]">
              <p className="text-sm font-semibold text-gray-900 dark:text-[#f8f8f2]">이 거래의 이유가 무엇이었나요?</p>
              <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-1">나중에 수익률과 연결해서 투자 습관을 분석해드립니다</p>
            </div>

            <div className="px-6 py-4 grid grid-cols-2 gap-2">
              {EMOTIONS.map(em => (
                <button key={em.value}
                  onClick={() => setSelectedEmotion(em.value)}
                  className={`flex items-center gap-2 px-3 py-2.5 rounded-xl text-sm font-medium transition-all border
                    ${selectedEmotion === em.value
                      ? "bg-[#bd93f9]/20 border-[#bd93f9] text-[#bd93f9]"
                      : "border-gray-300 dark:border-[#44475a] text-gray-500 dark:text-[#6272a4] hover:border-gray-400 dark:hover:border-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2]"}`}>
                  <em.icon size={16} weight="bold" aria-hidden />
                  <span>{em.label}</span>
                </button>
              ))}
            </div>

            <div className="px-6 pb-5 space-y-3">
              <textarea
                placeholder="메모 (선택)"
                value={memo}
                onChange={e => setMemo(e.target.value)}
                rows={2}
                className="w-full rounded-lg bg-white dark:bg-[#282a36] border border-gray-300 dark:border-[#44475a] text-gray-900 dark:text-[#f8f8f2] text-sm px-3 py-2 resize-none transition-colors hover:border-gray-400 dark:hover:border-[#6272a4] focus:outline-none focus:ring-2 focus:ring-[#bd93f9]/50 placeholder-gray-400 dark:placeholder-[#6272a4]"
              />
              <button
                onClick={() => emotionMutation.mutate()}
                disabled={!selectedEmotion || emotionMutation.isPending}
                className="w-full py-2.5 rounded-xl bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40">
                {emotionMutation.isPending ? "저장 중..." : "저장하기"}
              </button>
              <button onClick={onClose} className="w-full py-2 text-xs text-gray-500 dark:text-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2] transition-colors">
                건너뛰기
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
