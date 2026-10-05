"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { MarketSignalFeed, MarketStrategy, SignalDirection } from "@monticker/types";
import { useRuleSetSignalsWs } from "@/hooks/useRuleSetSignalsWs";
import { getScreenerQuotes } from "@/services/screener";
import { getSubscribedSignals } from "@/services/strategyMarket";
import { Icon, Panel } from "@/components/terminal";

interface FeedItem { key: string; strategy: string; direction: SignalDirection; stockId: number; evalDate: string; live?: boolean }

const FEED_KEY = ["quant", "market", "signals"] as const;

/** 구독 전략 신호 이력 + 이번 달 신호 수. 시장 화면 상단 스탯과 패널이 같은 쿼리를 나눠 쓴다. */
export function useSubscribedSignalFeed(enabled: boolean) {
  return useQuery<MarketSignalFeed>({
    queryKey: FEED_KEY,
    queryFn: () => getSubscribedSignals(30),
    enabled,
    staleTime: 60_000,
  });
}

const keyOf = (rulesetId: string, evalDate: string, direction: string, stockId: number) => `${rulesetId}-${evalDate}-${direction}-${stockId}`;

/**
 * 시안의 "구독 전략 신호" 패널. 서버의 신호 이력(ADR-035 — 구독 중인 전략만)으로 시작하고,
 * 이후 신호는 구독 전략 전체를 **한 WS 연결**로 받아 앞에 붙인다.
 */
export function SubscribedSignalsPanel({ subscribed }: { subscribed: MarketStrategy[] }) {
  const qc = useQueryClient();
  const { data: feed, isError } = useSubscribedSignalFeed(subscribed.length > 0);
  const [live, setLive] = useState<FeedItem[]>([]);
  const nameById = new Map(subscribed.map((s) => [s.ruleset_id, s.name]));
  const refetchTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(refetchTimer.current), []);

  // useRuleSetSignalsWs는 콜백을 ref로 들고 있어 매 렌더 새 함수여도 다시 연결하지 않는다.
  const onSignal = (rulesetId: string, e: { direction: SignalDirection; stockId: number; evalDate: string }) => {
    setLive((p) => [{ key: keyOf(rulesetId, e.evalDate, e.direction, e.stockId), strategy: nameById.get(rulesetId) ?? "구독 전략", direction: e.direction, stockId: e.stockId, evalDate: e.evalDate, live: true }, ...p].slice(0, 30));
    // 서버는 신호를 저장하는 트랜잭션 안에서 보낸다 — 커밋 뒤에 이력·월간 수·전략별 이력을 다시 읽는다.
    clearTimeout(refetchTimer.current);
    refetchTimer.current = setTimeout(() => qc.invalidateQueries({ queryKey: FEED_KEY }), 3000);
  };
  const { connected, denied } = useRuleSetSignalsWs(subscribed.map((s) => s.ruleset_id), onSignal);

  const history: FeedItem[] = (feed?.items ?? []).map((s) => ({
    key: keyOf(s.rulesetId, s.evalDate ?? s.signalTime.slice(0, 10), s.direction, s.stockId),
    strategy: s.strategyName, direction: s.direction, stockId: s.stockId, evalDate: s.evalDate ?? s.signalTime.slice(0, 10),
  }));
  const seen = new Set(history.map((h) => h.key));
  const items = [...live.filter((l) => !seen.has(l.key)), ...history].slice(0, 30);

  const ids = [...new Set(items.map((i) => i.stockId))];
  const { data: quotes } = useQuery({
    queryKey: ["screener", "quotes", ids],
    queryFn: () => getScreenerQuotes(ids),
    enabled: ids.length > 0,
    staleTime: 5 * 60_000,
  });
  const nameOf = (id: number) => quotes?.find((q) => q.stockId === id)?.name ?? `종목 #${id}`;

  return (
    <Panel
      tabs={["구독 전략 신호"]}
      actions={[]}
      closable={false}
      right={subscribed.length > 0 ? (
        <span className="flex items-center gap-1.5 text-2xs text-tm-muted">
          <span className={`h-[7px] w-[7px] rounded-full ${denied ? "bg-down" : connected ? "bg-dracula-green" : "bg-tm-muted"}`} aria-hidden />
          {denied ? "실시간 끊김" : connected ? "실시간" : "연결 중"}
        </span>
      ) : undefined}
    >
      {subscribed.length === 0 ? (
        <p className="m-0 py-4 text-center text-13 text-tm-muted">구독 중인 전략이 없습니다. 무료 전략을 구독하면 신호가 여기 모입니다.</p>
      ) : isError ? (
        <p className="m-0 py-4 text-center text-13 text-tm-muted">신호 이력을 불러오지 못했습니다.</p>
      ) : items.length === 0 ? (
        <p className="m-0 py-4 text-center text-13 text-tm-muted">구독 중 {subscribed.length}개 · 아직 발생한 신호가 없습니다.</p>
      ) : (
        <ul className="m-0 list-none p-0">
          {items.map((i) => {
            const c = i.direction === "BUY" ? "text-up" : "text-down";
            return (
              <li key={i.key} className="flex gap-2.5 border-b border-tm-line py-2.5">
                <span className={`grid pt-0.5 ${c}`}><Icon name="zap" size={15} /></span>
                <div className="flex flex-1 flex-col gap-0.5">
                  <span className="text-13"><b>{i.strategy}</b> → {nameOf(i.stockId)} <span className={c}>{i.direction === "BUY" ? "매수" : "매도"}</span></span>
                  <span className="text-2xs text-tm-muted">{i.live ? "방금 · " : ""}구독 중 · 룰셋 비공개</span>
                </div>
                <span className="num text-2xs text-tm-muted">{i.evalDate}</span>
              </li>
            );
          })}
        </ul>
      )}
      {denied && <p className="m-0 text-2xs text-[#ff8a8a]">구독이 바뀌어 실시간 연결이 거부됐습니다. 화면을 새로 고치면 다시 연결합니다.</p>}
    </Panel>
  );
}
