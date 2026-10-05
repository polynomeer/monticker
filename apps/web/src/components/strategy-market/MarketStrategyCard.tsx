"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { MarketStrategy, SignalDirection } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { useForwardTestSignalsWs } from "@/hooks/useForwardTestSignalsWs";
import { Btn, Pill, Sparkline, Stat, fmtPct } from "@/components/terminal";
import { fmtMatch, fmtMdd, matchTone } from "@/components/quant/parts";

interface SignalEvent { direction: SignalDirection; stockId: number; price: number; evalDate: string; }

export function fmtWon(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

function SignalFeed({ rulesetId }: { rulesetId: string }) {
  const [signals, setSignals] = useState<SignalEvent[]>([]);
  const { connected, denied } = useForwardTestSignalsWs(rulesetId, event => {
    setSignals(prev => [{ direction: event.direction, stockId: event.stockId, price: event.price, evalDate: event.evalDate }, ...prev].slice(0, 10));
  });

  return (
    <div className="flex flex-col gap-1.5 border-t border-tm-line pt-2.5">
      <div className="flex items-center gap-1.5">
        <span className={`h-[7px] w-[7px] rounded-full ${denied ? "bg-down" : connected ? "bg-dracula-green" : "bg-tm-muted"}`} aria-hidden />
        <span className={`text-2xs ${denied ? "text-[#ff8a8a]" : "text-tm-muted"}`}>
          {denied ? "구독 권한이 없어 신호를 받을 수 없습니다" : connected ? "실시간 신호 연결됨" : "연결 중..."}
        </span>
      </div>
      {denied ? null : signals.length === 0 ? (
        <p className="m-0 text-xs text-tm-muted">아직 발생한 신호가 없습니다 — 새 신호가 오면 여기 표시됩니다.</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {signals.map((s, i) => (
            <li key={i} className="flex items-center justify-between text-xs">
              <span className={s.direction === "BUY" ? "font-medium text-up" : "font-medium text-down"}>
                {s.direction === "BUY" ? "매수" : "매도"} 신호
              </span>
              <span className="num text-tm-muted">₩{fmtWon(s.price)} · {s.evalDate}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function forwardWeeks(startedAt: string, stoppedAt?: string | null) {
  const end = stoppedAt ? new Date(stoppedAt).getTime() : Date.now();
  return Math.max(0, Math.floor((end - new Date(startedAt).getTime()) / (7 * 86400_000)));
}

/** 시안 market()의 mcard — 성과는 마켓 목록 API가 같이 준 요약(ADR-078)을 쓴다. */
export function MarketStrategyCard({ strategy }: { strategy: MarketStrategy }) {
  const bt = strategy.performance?.backtest ?? null;
  const fw = strategy.performance?.forward ?? null;
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
    <article className="flex min-w-0 flex-col gap-3 rounded-xl bg-tm-panel p-4">
      <div className="flex justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-[3px]">
          <h3 className="m-0 truncate text-15 font-bold">{strategy.name}</h3>
          <span className="text-xs text-tm-muted">
            by {strategy.author_email.split("@")[0]} · 구독 <span className="num">{strategy.subscribe_count.toLocaleString()}</span>
          </span>
        </div>
        {/* 검증 배지(포워드 12주·일치율) 심사는 아직 없다 */}
        <Pill tone="muted">검증 전</Pill>
      </div>

      {strategy.description && (
        <p className="m-0 line-clamp-2 min-h-[2.6em] text-xs leading-relaxed text-tm-soft">{strategy.description}</p>
      )}
      {bt && bt.curve.length > 1 ? (
        <Sparkline values={bt.curve} color="#bd93f9" width={290} height={56} stretch />
      ) : (
        <div className="grid h-14 place-items-center rounded-lg bg-tm-inner text-2xs text-tm-muted">백테스트 곡선 없음</div>
      )}

      <div className="grid grid-cols-3 gap-2">
        <Stat
          label="CAGR"
          value={fmtPct(bt?.annualReturn ?? null, 1)}
          valueClassName={bt?.annualReturn == null ? "text-tm-muted" : bt.annualReturn >= 0 ? "text-up" : "text-down"}
          sub={bt ? `${bt.startDate.slice(0, 7)}~${bt.endDate.slice(0, 7)}` : undefined}
        />
        <Stat label="MDD" value={fmtMdd(bt?.mdd)} valueClassName={bt?.mdd == null ? "text-tm-muted" : bt.mdd > 0.05 ? "text-down" : undefined} sub={bt?.sharpe != null ? `샤프 ${bt.sharpe.toFixed(2)}` : undefined} />
        <Stat
          label="포워드"
          value={fw ? `${forwardWeeks(fw.startedAt, fw.stoppedAt)}주` : "—"}
          valueClassName={fw ? undefined : "text-tm-muted"}
          sub={fw ? <span className={matchTone(fw.matchRate)}>일치 {fmtMatch(fw.matchRate, fw.comparedSignals)}</span> : "포워드 기록 없음"}
        />
      </div>

      <div className="flex items-center justify-between gap-2 border-t border-tm-line pt-2.5">
        <span className="text-xs text-tm-muted">
          {strategy.isSubscribed ? "구독 중" : `등록 ${new Date(strategy.created_at).toLocaleDateString("ko-KR")}`}
        </span>
        <span className="flex items-center gap-2">
          <span className={`num font-semibold ${isPaid ? "" : "text-dracula-green"}`}>{isPaid ? `₩${fmtWon(strategy.price)}/월` : "무료"}</span>
          {strategy.isSubscribed ? (
            <>
              <Btn size="sm" kind="soft" className="h-8 px-3 text-13" onClick={() => setShowSignals(v => !v)} aria-expanded={showSignals}>
                {showSignals ? "신호 닫기" : "신호 보기"}
              </Btn>
              <Btn size="sm" kind="ghost" className="h-8 px-3 text-13" onClick={() => unsubscribeMutation.mutate()} disabled={unsubscribeMutation.isPending}>
                구독 해제
              </Btn>
            </>
          ) : isPaid ? (
            <Btn size="sm" className="h-8 px-3 text-13" disabled title="유료 구독 결제 연동 준비 중입니다.">준비 중</Btn>
          ) : (
            <Btn size="sm" className="h-8 px-3 text-13" onClick={() => subscribeMutation.mutate()} disabled={subscribeMutation.isPending}>
              {subscribeMutation.isPending ? "..." : "구독"}
            </Btn>
          )}
        </span>
      </div>

      {showSignals && strategy.isSubscribed && <SignalFeed rulesetId={strategy.ruleset_id} />}
    </article>
  );
}
