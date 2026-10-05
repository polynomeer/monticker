"use client";

import Link from "next/link";
import { useState } from "react";
import { Bar, DataTable, EventBadge, Panel, StockCell, type Column } from "@/components/terminal";
import { eventLabel, kstTime, useQuotes, useRecentEvents, type RecentEvent } from "./data";

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

/** 최근 이벤트 피드 — /api/events/recent 기반. 이벤트 구간 변동·거래량 배수는 응답에 없어 "—". */
export default function MoversFeed() {
  const [tab, setTab] = useState("moving");
  const { data: events = [], isLoading } = useRecentEvents();
  const rows = events.filter(FILTER[tab]).slice(0, 12);
  const quotes = useQuotes(Array.from(new Set(rows.map((e) => e.stockId))), "home-feed");

  const columns: Column<RecentEvent>[] = [
    { key: "t", header: "시각", cell: (e) => <span className="num text-tm-muted">{kstTime(e.eventTime)}</span> },
    {
      key: "s",
      header: "종목",
      cell: (e) => {
        const q = quotes.get(e.stockId);
        return q ? <StockCell name={q.name} code={q.symbol} href={`/stocks/${q.symbol}`} /> : <span className="text-tm-muted">#{e.stockId}</span>;
      },
    },
    { key: "e", header: "이벤트", cell: (e) => <span title={e.title}><EventBadge type={eventLabel(e.eventType)} /></span> },
    { key: "c", header: "이벤트 구간 변동", align: "right", cell: () => <span className="text-tm-muted">—</span> },
    { key: "v", header: "거래량 배수", align: "right", cell: () => <span className="text-tm-muted">—</span> },
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
    <Panel tabs={TABS} active={tab} onTabChange={setTab} bodyClassName="gap-0 px-1.5 pb-1.5 pt-1">
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
