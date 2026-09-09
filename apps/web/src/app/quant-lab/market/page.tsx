"use client";

import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { MarketStrategy, SignalDirection } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { useForwardTestSignalsWs } from "@/hooks/useForwardTestSignalsWs";
import { Card } from "@/components/ui/Card";
import { Storefront, Radio } from "@phosphor-icons/react";
import Link from "next/link";

interface SignalEvent { direction: SignalDirection; stockId: number; price: number; evalDate: string; }

function fmtWon(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

function SignalFeed({ rulesetId }: { rulesetId: string }) {
  const [signals, setSignals] = useState<SignalEvent[]>([]);
  const { connected, denied } = useForwardTestSignalsWs(rulesetId, event => {
    setSignals(prev => [{ direction: event.direction, stockId: event.stockId, price: event.price, evalDate: event.evalDate }, ...prev].slice(0, 10));
  });

  return (
    <div className="mt-3 pt-3 border-t border-gray-100 dark:border-dracula-line/50">
      <div className="flex items-center gap-1.5 mb-2">
        <Radio size={12} weight="bold" className={denied ? "text-dracula-red" : connected ? "text-dracula-green" : "text-gray-400 dark:text-dracula-comment"} aria-hidden />
        <span className={`text-[11px] ${denied ? "text-dracula-red" : "text-gray-500 dark:text-dracula-comment"}`}>
          {denied ? "구독 권한이 없어 신호를 받을 수 없습니다" : connected ? "실시간 신호 연결됨" : "연결 중..."}
        </span>
      </div>
      {denied ? null : signals.length === 0 ? (
        <p className="text-xs text-gray-400 dark:text-dracula-comment">아직 발생한 신호가 없습니다 — 새 신호가 오면 여기 표시됩니다.</p>
      ) : (
        <div className="space-y-1">
          {signals.map((s, i) => (
            <div key={i} className="flex items-center justify-between text-xs">
              <span className={s.direction === "BUY" ? "text-dracula-red font-medium" : "text-dracula-cyan font-medium"}>
                {s.direction === "BUY" ? "매수" : "매도"} 신호
              </span>
              <span className="font-mono text-gray-500 dark:text-dracula-comment">₩{fmtWon(s.price)} · {s.evalDate}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function StrategyCard({ strategy }: { strategy: MarketStrategy }) {
  const qc = useQueryClient();
  const { toast } = useToast();
  const [showSignals, setShowSignals] = useState(false);

  const subscribeMutation = useMutation({
    mutationFn: () => authFetch(`/api/quant/market/${strategy.id}/subscribe`, { method: "POST" }).then(r => r.json()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["quant", "market"] });
      toast({ type: "success", title: "구독 완료", message: `"${strategy.name}" 전략을 구독했습니다.` });
    },
    onError: () => toast({ type: "error", title: "구독 실패", message: "다시 시도해주세요." }),
  });

  const unsubscribeMutation = useMutation({
    mutationFn: () => authFetch(`/api/quant/market/${strategy.id}/subscribe`, { method: "DELETE" }).then(r => r.json()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["quant", "market"] });
      setShowSignals(false);
      toast({ type: "success", title: "구독 해제됨", message: `"${strategy.name}" 구독을 해제했습니다.` });
    },
    onError: () => toast({ type: "error", title: "해제 실패", message: "다시 시도해주세요." }),
  });

  // ADR-035 — 유료 결제(PG 연동)는 아직 준비되지 않았다. 실패하는 결제를 실제로 시도하게
  // 두는 대신, 무료 전략만 구독 가능하게 하고 유료는 명확히 "준비 중"으로 표시한다.
  const isPaid = strategy.price > 0;

  return (
    <Card className="p-5" hover>
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="font-semibold text-gray-900 dark:text-dracula-fg truncate">{strategy.name}</h3>
            <span className={`text-[11px] font-mono shrink-0 ${isPaid ? "text-gray-500 dark:text-dracula-comment" : "text-dracula-green"}`}>
              {isPaid ? `₩${fmtWon(strategy.price)}` : "무료"}
            </span>
          </div>
          {strategy.description && (
            <p className="text-xs text-gray-500 dark:text-dracula-comment mt-1 line-clamp-2">{strategy.description}</p>
          )}
          <p className="text-xs text-gray-400 dark:text-dracula-line mt-2">
            by {strategy.author_email.split("@")[0]} · 구독자 {strategy.subscribe_count.toLocaleString()}명
          </p>
        </div>

        {strategy.isSubscribed ? (
          <div className="flex flex-col items-end gap-1.5 shrink-0">
            <button
              onClick={() => setShowSignals(v => !v)}
              className="px-3 py-1.5 rounded-lg bg-dracula-green/10 text-dracula-green text-xs font-medium hover:bg-dracula-green/20 active:scale-95 transition-all duration-150 border border-dracula-green/30"
            >
              {showSignals ? "신호 닫기" : "신호 보기"}
            </button>
            <button
              onClick={() => unsubscribeMutation.mutate()}
              disabled={unsubscribeMutation.isPending}
              className="text-[11px] text-gray-400 dark:text-dracula-comment hover:text-dracula-red transition-colors disabled:opacity-40"
            >
              구독 해제
            </button>
          </div>
        ) : isPaid ? (
          <button
            disabled
            title="유료 구독 결제 연동 준비 중입니다."
            className="shrink-0 px-3 py-1.5 rounded-lg bg-gray-100 dark:bg-dracula-line/30 text-gray-400 dark:text-dracula-comment text-xs font-medium border border-gray-200 dark:border-dracula-line cursor-not-allowed"
          >
            준비 중
          </button>
        ) : (
          <button
            onClick={() => subscribeMutation.mutate()}
            disabled={subscribeMutation.isPending}
            className="shrink-0 px-3 py-1.5 rounded-lg bg-blue-50 dark:bg-dracula-purple/10 text-blue-600 dark:text-dracula-purple text-xs font-medium hover:bg-blue-100 dark:hover:bg-dracula-purple/20 active:scale-95 transition-all duration-150 disabled:opacity-40 border border-blue-200 dark:border-dracula-purple/30"
          >
            {subscribeMutation.isPending ? "..." : "구독"}
          </button>
        )}
      </div>

      {showSignals && strategy.isSubscribed && <SignalFeed rulesetId={strategy.ruleset_id} />}
    </Card>
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
        <Link href="/quant-lab" className="text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg text-sm transition-colors">← Quant Lab</Link>
        <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">전략 마켓</h1>
        <span className="ml-auto text-xs text-gray-500 dark:text-dracula-comment">커뮤니티 공유 전략</span>
      </div>

      {isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-24 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />
          ))}
        </div>
      ) : (strategies ?? []).length === 0 ? (
        <div className="text-center py-20 space-y-3">
          <div className="flex justify-center text-gray-400 dark:text-dracula-comment"><Storefront size={40} weight="duotone" aria-hidden /></div>
          <p className="font-semibold text-gray-900 dark:text-dracula-fg">아직 공유된 전략이 없습니다</p>
          <p className="text-sm text-gray-500 dark:text-dracula-comment">내 룰셋을 공유해서 커뮤니티와 함께하세요</p>
          <Link
            href="/quant-lab/builder"
            className="inline-block mt-2 px-5 py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-semibold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150"
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
                className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-sm font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment active:scale-[0.98] transition-all duration-150"
              >
                이전
              </button>
            )}
            {(strategies ?? []).length === 20 && (
              <button
                onClick={() => setPage(p => p + 1)}
                className="px-4 py-2 rounded-lg bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg text-sm font-medium hover:bg-gray-200 dark:hover:bg-dracula-comment active:scale-[0.98] transition-all duration-150"
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
