"use client";

import Link from "next/link";
import { useState } from "react";
import { Bar, DataTable, EventBadge, Panel, StockCell, dirClass, fmtPct, type Column } from "@/components/terminal";
import { eventLabel, fmtMult, kstTime, useIsLoggedIn, useQuotes, useRecentEvents, type RecentEvent } from "./data";
import { useInterestOrdering } from "@/hooks/useUserPreferences";
import { interestFirst, isInterestSector } from "@/lib/interestSectors";
import { InterestMark, InterestOrderingToggle } from "@/components/interest/InterestOrderingToggle";

const TABS = [
  { key: "moving", label: "지금 움직이는 종목" },
  { key: "disclosure", label: "오늘의 공시" },
  { key: "news", label: "뉴스" },
];

const FILTER: Record<string, (e: RecentEvent) => boolean> = {
  moving: () => true,
  disclosure: (e) => e.eventType === "DISCLOSURE_PUBLISHED",
  news: (e) => e.eventType === "NEWS_PUBLISHED",
};

/**
 * 최근 이벤트 피드 — /api/events/recent 기반.
 * 이벤트 구간 변동 = 이벤트 직전 1분봉 종가 → 이벤트 후 30분(또는 지금), 거래량 배수 = 직후 5분 ÷ 직전 60분 평균(서버 계산).
 * ADR-099 — "관심 분야 순"이 켜져 있으면 보여 줄 12건(스위치와 상관없이 같은 12건) 안에서 관심 분야 종목(시세 응답의 업종)을
 * 앞에 두고 "관심" 표시를 붙인다. 각 그룹 안은 원래(최신순) 순서 그대로다.
 */
export default function MoversFeed() {
  const [tab, setTab] = useState("moving");
  const { data: events = [], isLoading } = useRecentEvents();
  const latest = events.filter(FILTER[tab]).slice(0, 12);
  const quotes = useQuotes(Array.from(new Set(latest.map((e) => e.stockId))), "home-feed");
  const isLoggedIn = useIsLoggedIn();
  const interest = useInterestOrdering(isLoggedIn);
  const sectorOf = (e: RecentEvent) => quotes.get(e.stockId)?.sector;
  const rows = interestFirst(latest, sectorOf, interest.interests, interest.active);

  const columns: Column<RecentEvent>[] = [
    { key: "t", header: "시각", cell: (e) => <span className="num text-tm-muted">{kstTime(e.eventTime)}</span> },
    {
      key: "s",
      header: "종목",
      cell: (e) => {
        const q = quotes.get(e.stockId);
        const mine = interest.active && isInterestSector(q?.sector, interest.interests);
        const cell = q ? <StockCell name={q.name} code={q.symbol} href={`/stocks/${q.symbol}`} /> : <span className="text-tm-muted">#{e.stockId}</span>;
        return mine ? <span className="flex items-center gap-1.5" data-interest>{cell}<InterestMark /></span> : cell;
      },
    },
    { key: "e", header: "이벤트", cell: (e) => <span title={e.title}><EventBadge type={eventLabel(e.eventType)} /></span> },
    {
      key: "c",
      header: <span title="이벤트 직전 1분봉 종가 대비 이벤트 후 30분(진행 중이면 지금)까지">이벤트 구간 변동</span>,
      align: "right",
      cell: (e) => <span className={`num ${e.windowChangePct == null ? "text-tm-muted" : dirClass(e.windowChangePct)}`}>{fmtPct(e.windowChangePct)}</span>,
    },
    {
      key: "v",
      header: <span title="이벤트 직후 5분 평균 거래량 ÷ 직전 60분 평균">거래량 배수</span>,
      align: "right",
      cell: (e) => <span className={`num ${e.volumeMultiple == null ? "text-tm-muted" : ""}`}>{fmtMult(e.volumeMultiple)}</span>,
    },
    {
      key: "i",
      header: "중요도",
      cell: (e) => (
        <div className="flex min-w-[120px] items-center gap-2">
          <div className="flex-1">
            <Bar pct={e.importanceScore} h={5} color={e.importanceScore >= 80 ? "bg-dracula-purple" : "bg-tm-soft"} />
          </div>
          <span className="num text-xs">{e.importanceScore}</span>
        </div>
      ),
    },
    {
      key: "a",
      header: <span className="sr-only">바로가기</span>,
      align: "right",
      cell: (e) => {
        const q = quotes.get(e.stockId);
        return q ? <Link href={`/stocks/${q.symbol}`} className="text-xs text-dracula-purple hover:underline">차트 보기</Link> : null;
      },
    },
  ];

  return (
    <Panel
      tabs={TABS}
      active={tab}
      onTabChange={setTab}
      bodyClassName="gap-0 px-1.5 pb-1.5 pt-1"
      right={interest.available ? <InterestOrderingToggle active={interest.active} onChange={interest.setEnabled} /> : undefined}
    >
      {isLoading ? (
        <div className="flex flex-col gap-1 p-2" aria-busy="true">
          {[0, 1, 2, 3].map((i) => <div key={i} className="h-10 animate-pulse rounded-lg bg-tm-inner" />)}
        </div>
      ) : (
        <DataTable columns={columns} rows={rows} rowKey={(e) => e.id} minWidth={760} empty="Worker 실행 후 이벤트가 표시됩니다." />
      )}
    </Panel>
  );
}
