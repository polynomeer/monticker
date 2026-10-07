"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { Icon, Panel, Pill } from "@/components/terminal";
import { authFetch } from "@/services/api";
import { kstTime, useIsLoggedIn } from "./data";

/** GET /api/quant/signals/feed — 내 룰셋·구독 전략의 포워드 테스트 신호(ADR-035 접근 규칙) */
interface SignalFeedItem {
  id: number;
  ruleSetId: string;
  ruleSetName: string;
  source: "MINE" | "SUBSCRIBED";
  stockId: number;
  symbol: string;
  stockName: string;
  direction: "BUY" | "SELL";
  signalTime: string;
}

/** 시안의 "퀀트 시그널" 피드. 포워드 테스트 신호는 장 마감 후 하루 한 번 평가된다. */
export default function QuantSignals() {
  const isLoggedIn = useIsLoggedIn();
  const { data = [], isLoading } = useQuery<SignalFeedItem[]>({
    queryKey: ["quant", "signals", "feed"],
    queryFn: async () => {
      const r = await authFetch("/api/quant/signals/feed?limit=8");
      return r.ok ? r.json() : [];
    },
    enabled: isLoggedIn,
    refetchInterval: 60_000,
    staleTime: 60_000,
  });

  return (
    <Panel tabs={["퀀트 시그널"]} actions={["expand"]} bodyClassName="px-3.5 pb-3 pt-1.5">
      {!isLoggedIn ? (
        <p className="py-4 text-center text-13 text-tm-muted">
          <Link href="/login" className="text-dracula-purple hover:underline">로그인</Link>하면 내 전략·구독 전략의 시그널이 여기에 모입니다.
        </p>
      ) : isLoading ? (
        <div className="h-24 animate-pulse rounded-lg bg-tm-inner" />
      ) : data.length === 0 ? (
        <div className="flex gap-2.5 py-3">
          <span className="grid pt-0.5 text-tm-muted"><Icon name="zap" size={15} /></span>
          <div className="flex flex-1 flex-col gap-0.5">
            <span className="text-13 font-semibold text-tm-soft">아직 표시할 시그널이 없습니다</span>
            <span className="text-xs text-tm-muted">내 전략에서 포워드 테스트를 시작하거나 마켓 전략을 구독하면 진입·청산 신호가 여기에 모입니다.</span>
          </div>
        </div>
      ) : (
        <ul className="m-0 list-none p-0">
          {data.map((s) => (
            <li key={s.id} className="flex items-center gap-2.5 border-b border-tm-line py-2">
              <Pill tone={s.direction === "BUY" ? "green" : "red"} className="w-11 justify-center">{s.direction === "BUY" ? "매수" : "매도"}</Pill>
              <span className="flex min-w-0 flex-1 flex-col">
                <Link href={`/stocks/${s.symbol}`} className="truncate text-13 font-semibold text-dracula-fg hover:text-dracula-purple">{s.stockName}</Link>
                <span className="truncate text-2xs text-tm-muted">
                  {s.source === "MINE" ? "내 전략" : "구독"} · {s.ruleSetName}
                </span>
              </span>
              <span className="num text-2xs text-tm-muted">{kstTime(s.signalTime)}</span>
            </li>
          ))}
        </ul>
      )}
      <Link href="/quant-lab" className="text-xs text-dracula-purple hover:underline">퀀트랩에서 전략 보기 →</Link>
    </Panel>
  );
}
