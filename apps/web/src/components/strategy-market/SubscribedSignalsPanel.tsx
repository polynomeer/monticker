"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { MarketStrategy, SignalDirection } from "@monticker/types";
import { useForwardTestSignalsWs } from "@/hooks/useForwardTestSignalsWs";
import { getScreenerQuotes } from "@/services/screener";
import { Icon, Panel } from "@/components/terminal";

interface FeedItem { key: string; strategy: string; direction: SignalDirection; stockId: number; evalDate: string; }

/** 구독 전략 하나의 STOMP 토픽을 듣기만 하는 보이지 않는 컴포넌트 — 훅을 반복문에서 부를 수 없어 나눴다. */
function Listener({ s, onSignal }: { s: MarketStrategy; onSignal: (i: FeedItem) => void }) {
  useForwardTestSignalsWs(s.ruleset_id, (e) => {
    onSignal({ key: `${s.id}-${e.evalDate}-${e.stockId}-${e.direction}-${Date.now()}`, strategy: s.name, direction: e.direction, stockId: e.stockId, evalDate: e.evalDate });
  });
  return null;
}

/**
 * 시안의 "구독 전략 신호" 패널. 과거 신호 조회 API가 없어 이 화면을 연 뒤 실시간으로
 * 들어온 신호만 쌓는다(ADR-035 — 서버는 구독자에게만 토픽을 허용).
 */
export function SubscribedSignalsPanel({ subscribed }: { subscribed: MarketStrategy[] }) {
  const [items, setItems] = useState<FeedItem[]>([]);
  const push = useCallback((i: FeedItem) => setItems((p) => [i, ...p].slice(0, 30)), []);
  const ids = [...new Set(items.map((i) => i.stockId))];
  const { data: quotes } = useQuery({
    queryKey: ["screener", "quotes", ids],
    queryFn: () => getScreenerQuotes(ids),
    enabled: ids.length > 0,
    staleTime: 5 * 60_000,
  });
  const nameOf = (id: number) => quotes?.find((q) => q.stockId === id)?.name ?? `종목 #${id}`;

  return (
    <Panel tabs={["구독 전략 신호"]} actions={[]} closable={false}>
      {subscribed.map((s) => <Listener key={s.id} s={s} onSignal={push} />)}
      {subscribed.length === 0 ? (
        <p className="m-0 py-4 text-center text-13 text-tm-muted">구독 중인 전략이 없습니다. 무료 전략을 구독하면 신호가 여기 모입니다.</p>
      ) : items.length === 0 ? (
        <p className="m-0 py-4 text-center text-13 text-tm-muted">구독 중 {subscribed.length}개 · 새 신호가 오면 여기 표시됩니다.</p>
      ) : (
        <ul className="m-0 list-none p-0">
          {items.map((i) => {
            const c = i.direction === "BUY" ? "text-up" : "text-down";
            return (
              <li key={i.key} className="flex gap-2.5 border-b border-tm-line py-2.5">
                <span className={`grid pt-0.5 ${c}`}><Icon name="zap" size={15} /></span>
                <div className="flex flex-1 flex-col gap-0.5">
                  <span className="text-13"><b>{i.strategy}</b> → {nameOf(i.stockId)} <span className={c}>{i.direction === "BUY" ? "매수" : "매도"}</span></span>
                  <span className="text-2xs text-tm-muted">구독 중 · 룰셋 비공개</span>
                </div>
                <span className="num text-2xs text-tm-muted">{i.evalDate}</span>
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}
