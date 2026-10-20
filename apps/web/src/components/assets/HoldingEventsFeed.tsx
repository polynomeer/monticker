"use client";

import Link from "next/link";
import { Btn, EventBadge } from "@/components/terminal";
import { eventLabel } from "@/components/home/data";
import { fmtDateTime } from "@/components/portfolio/format";
import { EmptyNote, Skeleton } from "@/components/portfolio/PaperStates";
import { useHoldingEvents } from "@/hooks/usePortfolio";

/** 보유 종목 소식(ADR-108) — 알림과 같은 "보유" 기준이라, 알림으로 받은 소식은 여기에도 있다 */
export function HoldingEventsFeed({ enabled }: { enabled: boolean }) {
  const { data, isPending, isError, fetchNextPage, hasNextPage, isFetchingNextPage } = useHoldingEvents(enabled);
  // isPending — 켜진 직후 첫 요청 전에도 "소식 없음"을 잠깐 보이지 않게(isLoading은 그 순간 false다)
  if (isError) return <EmptyNote>소식을 불러오지 못했습니다.</EmptyNote>;
  if (isPending) return <Skeleton className="h-40" />;
  const events = data?.pages.flatMap((p) => p.events) ?? [];
  const held = data?.pages[0]?.heldStockCount ?? 0;
  if (held === 0) return <EmptyNote>보유 종목이 생기면 그 종목의 급등·급락·뉴스·공시가 여기에 모입니다.</EmptyNote>;
  if (events.length === 0) return <EmptyNote>보유 종목 {held}개에 아직 소식이 없습니다.</EmptyNote>;
  return (
    <div className="flex flex-col">
      <ul className="m-0 flex list-none flex-col p-0">
        {events.map((e) => (
          <li key={e.id} className="flex flex-col gap-1 border-b border-tm-line px-2.5 py-2 last:border-b-0">
            <div className="flex items-center gap-2 text-2xs text-tm-muted">
              <EventBadge type={eventLabel(e.eventType)} />
              {e.symbol
                ? <Link href={`/stocks/${e.symbol}`} className="font-semibold text-tm-soft hover:text-dracula-fg">{e.name ?? e.symbol}</Link>
                : <span className="font-semibold text-tm-soft">{e.name ?? `#${e.stockId}`}</span>}
              <span className="num ml-auto">{fmtDateTime(e.eventTime)}</span>
            </div>
            <p className="m-0 text-13 leading-snug">{e.title}</p>
          </li>
        ))}
      </ul>
      {hasNextPage && (
        <Btn kind="soft" size="sm" className="m-2 self-center" onClick={() => fetchNextPage()} disabled={isFetchingNextPage}>
          {isFetchingNextPage ? "불러오는 중…" : "더 보기"}
        </Btn>
      )}
    </div>
  );
}
