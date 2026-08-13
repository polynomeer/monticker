"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import Link from "next/link";

interface MarketStrategy {
  id: number;
  name: string;
  description: string | null;
  subscribe_count: number;
  author_email: string;
  created_at: string;
}

function StrategyCard({ strategy }: { strategy: MarketStrategy }) {
  const qc = useQueryClient();
  const { toast } = useToast();

  const subscribeMutation = useMutation({
    mutationFn: () => authFetch(`/api/quant/market/${strategy.id}/subscribe`, { method: "POST" }).then(r => r.json()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["quant", "market"] });
      toast({ type: "success", title: "구독 완료", message: `"${strategy.name}" 전략을 구독했습니다.` });
    },
    onError: () => toast({ type: "error", title: "구독 실패", message: "다시 시도해주세요." }),
  });

  return (
    <div className="p-5 rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line hover:border-blue-300 dark:hover:border-[#bd93f9]/50 transition-colors">
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <h3 className="font-semibold text-gray-900 dark:text-[#f8f8f2] truncate">{strategy.name}</h3>
          {strategy.description && (
            <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-1 line-clamp-2">{strategy.description}</p>
          )}
          <p className="text-xs text-gray-400 dark:text-[#44475a] mt-2">
            by {strategy.author_email.split("@")[0]} · 구독자 {strategy.subscribe_count.toLocaleString()}명
          </p>
        </div>
        <button
          onClick={() => subscribeMutation.mutate()}
          disabled={subscribeMutation.isPending}
          className="shrink-0 px-3 py-1.5 rounded-lg bg-blue-50 dark:bg-[#bd93f9]/10 text-blue-600 dark:text-[#bd93f9] text-xs font-medium hover:bg-blue-100 dark:hover:bg-[#bd93f9]/20 active:scale-95 transition-all duration-150 disabled:opacity-40 border border-blue-200 dark:border-[#bd93f9]/30"
        >
          {subscribeMutation.isPending ? "..." : "구독"}
        </button>
      </div>
    </div>
  );
}

export default function StrategyMarketPage() {
  const [page, setPage] = useState(0);

  const { data: strategies, isLoading } = useQuery<MarketStrategy[]>({
    queryKey: ["quant", "market", page],
    queryFn: () => authFetch(`/api/quant/market?page=${page}&size=20`).then(r => r.json()),
  });

  return (
    <div className="max-w-3xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="flex items-center gap-3 mb-8">
        <Link href="/quant-lab" className="text-gray-500 dark:text-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2] text-sm transition-colors">← Quant Lab</Link>
        <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-[#f8f8f2]">전략 마켓</h1>
        <span className="ml-auto text-xs text-gray-500 dark:text-[#6272a4]">커뮤니티 공유 전략</span>
      </div>

      {isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-24 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />
          ))}
        </div>
      ) : (strategies ?? []).length === 0 ? (
        <div className="text-center py-20 space-y-3">
          <p className="text-4xl">🏪</p>
          <p className="font-semibold text-gray-900 dark:text-[#f8f8f2]">아직 공유된 전략이 없습니다</p>
          <p className="text-sm text-gray-500 dark:text-[#6272a4]">내 룰셋을 공유해서 커뮤니티와 함께하세요</p>
          <Link
            href="/quant-lab/builder"
            className="inline-block mt-2 px-5 py-2 rounded-lg bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] font-semibold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150"
          >
            룰셋 만들기
          </Link>
        </div>
      ) : (
        <>
          <div className="space-y-3">
            {(strategies ?? []).map((s: MarketStrategy) => <StrategyCard key={s.id} strategy={s} />)}
          </div>
          <div className="flex justify-center gap-3 mt-8">
            {page > 0 && (
              <button
                onClick={() => setPage(p => p - 1)}
                className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-[#44475a] text-gray-700 dark:text-[#f8f8f2] text-sm font-medium hover:bg-gray-200 dark:hover:bg-[#6272a4] active:scale-[0.98] transition-all duration-150"
              >
                이전
              </button>
            )}
            {(strategies ?? []).length === 20 && (
              <button
                onClick={() => setPage(p => p + 1)}
                className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-[#44475a] text-gray-700 dark:text-[#f8f8f2] text-sm font-medium hover:bg-gray-200 dark:hover:bg-[#6272a4] active:scale-[0.98] transition-all duration-150"
              >
                다음
              </button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
