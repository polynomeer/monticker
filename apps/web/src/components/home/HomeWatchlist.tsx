"use client";

import Link from "next/link";
import { Panel, Sparkline, dirClass, fmtPct } from "@/components/terminal";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import { useDailyCloses, useIsLoggedIn, useQuotes, useWatchlistGroups } from "./data";

/** 관심종목 상위 5개 — 시세는 /api/screener/quotes, 스파크라인은 최근 30일 일봉 종가. */
export default function HomeWatchlist() {
  const isLoggedIn = useIsLoggedIn();
  const theme = useThemeStore((s) => CHART_THEMES[s.chartTheme]);
  const { data: groups = [], isLoading } = useWatchlistGroups(isLoggedIn);
  const items = Array.from(new Map(groups.flatMap((g) => g.items).map((i) => [i.stockId, i])).values()).slice(0, 5);
  const ids = items.map((i) => i.stockId);
  const quotes = useQuotes(ids, "home-watchlist", 15_000);
  const closes = useDailyCloses(ids);

  return (
    <Panel tabs={["관심종목"]} actions={["expand"]} bodyClassName="px-3.5 pb-3 pt-1.5">
      {!isLoggedIn ? (
        <p className="py-4 text-center text-13 text-tm-muted">
          <Link href="/login" className="text-dracula-purple hover:underline">로그인</Link>하면 관심종목이 여기에 표시됩니다.
        </p>
      ) : isLoading ? (
        <div className="h-24 animate-pulse rounded-lg bg-tm-inner" />
      ) : items.length === 0 ? (
        <p className="py-4 text-center text-13 text-tm-muted">관심종목이 없습니다. 스크리너에서 ☆를 눌러 추가하세요.</p>
      ) : (
        <ul className="m-0 list-none p-0">
          {items.map((it) => {
            const q = quotes.get(it.stockId);
            const rate = q?.changeRate ?? null;
            const color = rate == null ? "#a4abcf" : rate >= 0 ? theme.upColor : theme.downColor;
            return (
              <li key={it.stockId} className="border-b border-tm-line">
                <Link href={`/stocks/${it.symbol}`} className="flex items-center gap-2.5 py-2 text-dracula-fg hover:text-dracula-fg">
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate text-13 font-semibold">{it.name}</span>
                    <span className="num text-2xs text-tm-muted">{it.symbol}</span>
                  </span>
                  <Sparkline values={closes.get(it.stockId) ?? []} color={color} width={70} height={24} />
                  <span className="flex min-w-[76px] flex-col items-end">
                    <span className="num text-13">{q ? q.price.toLocaleString("ko-KR") : "—"}</span>
                    <span className={`num text-2xs ${dirClass(rate)}`}>{fmtPct(rate)}</span>
                  </span>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
      <Link href="/watchlist" className="text-xs text-dracula-purple hover:underline">관심종목 전체 보기 →</Link>
    </Panel>
  );
}
