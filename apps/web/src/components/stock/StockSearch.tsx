"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { Chip, EventBadge, Icon, Panel, PanelCol, PanelRow, Pill, Seg, StockCell, TerminalPage, TitleBlock, fmtNum } from "@/components/terminal";
import { useRecentlyViewedStocks } from "@/hooks/useRecentlyViewedStocks";
import { cn } from "@/lib/utils";
import { eventMeta, fmtWhen, useQuotes, type Quote } from "./parts";

interface StockResult { id: number; symbol: string; name: string; market: string; sector: string | null; currency: string; }
interface EventResult { id: number; stockId: number; eventType: string; title: string; eventTime: string; }
interface NewsResult { id: number; stockId: number | null; title: string; url: string; source: string | null; publishedAt: string; }
interface StrategyRow { id: number; name: string; description: string | null; price: number | null; subscribe_count: number | null; author_nickname?: string | null; }

type Scope = "all" | "stock" | "event" | "strategy" | "news";
const SCOPES: { value: Scope; label: string }[] = [
  { value: "all", label: "전체" },
  { value: "stock", label: "종목" },
  { value: "event", label: "이벤트" },
  { value: "strategy", label: "전략" },
  { value: "news", label: "뉴스" },
];

/** 서버 전략 검색어 최대 길이(StrategyMarketController.MAX_SEARCH_QUERY_LENGTH) */
const STRATEGY_QUERY_MAX = 50;

const RECENT_KEY = "monticker:recentSearches";
function readRecent(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    return raw ? (JSON.parse(raw) as string[]) : [];
  } catch {
    return [];
  }
}
function writeRecent(v: string[]) {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(v)); } catch { /* 저장 불가 환경 — 무시 */ }
}

/** 입력을 200ms 늦춰 쓴다 — 키 입력마다 검색 API를 부르지 않도록. */
function useDebounced<T>(v: T, ms = 200) {
  const [d, setD] = useState(v);
  useEffect(() => {
    const t = setTimeout(() => setD(v), ms);
    return () => clearTimeout(t);
  }, [v, ms]);
  return d;
}

/** 검색어별 GET — react-query가 늦게 도착한 이전 검색어 응답을 키로 분리해 최신 결과를 덮어쓰지 않게 한다. */
function useSearch<T>(key: string, url: string | null) {
  return useQuery<T[]>({
    queryKey: ["search", key, url],
    queryFn: async ({ signal }) => {
      const r = await fetch(url!, { signal });
      if (!r.ok) return [];
      const j = await r.json();
      return Array.isArray(j) ? j : (j?.items ?? j?.content ?? []);
    },
    enabled: !!url,
    staleTime: 30_000,
  });
}

/** 종목·이벤트 검색 — 시안 StockSearch.dc.html */
export default function StockSearch() {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [scope, setScope] = useState<Scope>("all");
  const [sel, setSel] = useState(0);
  const [recent, setRecent] = useState<string[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  const { entries: recentlyViewed } = useRecentlyViewedStocks();
  const q = useDebounced(query.trim());
  const enc = encodeURIComponent(q);

  useEffect(() => setRecent(readRecent()), []);
  useEffect(() => setSel(0), [q, scope]);

  // "/" 키로 검색창 포커스 (입력 중이 아닐 때)
  useEffect(() => {
    const h = (e: globalThis.KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (e.key !== "/" || t?.closest("input,textarea,select,[contenteditable=true]")) return;
      e.preventDefault();
      inputRef.current?.focus();
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);

  const stocksQ = useSearch<StockResult>("stocks", q ? `/api/stocks/search?query=${enc}` : null);
  const eventsQ = useSearch<EventResult>("events", q && (scope === "all" || scope === "event") ? `/api/events/search?query=${enc}&limit=12` : null);
  const newsQ = useSearch<NewsResult>("news", q && (scope === "all" || scope === "news") ? `/api/news/search?query=${enc}&limit=10` : null);
  // 서버 측 전략 검색 — 이름·설명·작성자 닉네임(목록과 같은 공개 범위). 서버 상한(50자)에 맞춰 자른다
  const strategyTerm = q.slice(0, STRATEGY_QUERY_MAX);
  const strategyQ = useSearch<StrategyRow>("strategies", strategyTerm && (scope === "all" || scope === "strategy") ? `/api/quant/market/search?q=${encodeURIComponent(strategyTerm)}&page=0&size=8` : null);

  const stocks = (stocksQ.data ?? []).slice(0, 20);
  const events = eventsQ.data ?? [];
  const news = newsQ.data ?? [];
  const strategies = strategyQ.data ?? [];
  const quotes = useQuotes([...stocks.map((s) => s.id), ...events.map((e) => e.stockId)], 30_000);

  const showStocks = scope === "all" || scope === "stock";
  const showEvents = scope === "all" || scope === "event";
  const showNews = scope === "all" || scope === "news";
  const showStrategies = scope === "all" || scope === "strategy";
  const total = (showStocks ? stocks.length : 0) + (showEvents ? events.length : 0) + (showNews ? news.length : 0) + (showStrategies ? strategies.length : 0);
  const loading = stocksQ.isFetching || eventsQ.isFetching || newsQ.isFetching;

  const remember = (term: string) => {
    const t = term.trim();
    if (!t) return;
    const next = [t, ...readRecent().filter((x) => x !== t)].slice(0, 10);
    writeRecent(next);
    setRecent(next);
  };
  const go = (s: StockResult) => {
    remember(query);
    router.push(`/stocks/${s.symbol}`);
  };

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Escape") {
      setQuery("");
      return;
    }
    if (!showStocks || stocks.length === 0) {
      if (e.key === "Enter") remember(query);
      return;
    }
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setSel((i) => Math.min(stocks.length - 1, i + 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setSel((i) => Math.max(0, i - 1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      go(stocks[sel] ?? stocks[0]);
    }
  };

  return (
    <TerminalPage
      left={<TitleBlock title="종목·이벤트 검색" crumb="마켓" />}
      stats={[
        { label: "검색 결과", value: q ? `${total}건` : "—" },
        { label: "단축키", value: "⌘K · /" },
      ]}
    >
      <PanelRow>
        <Panel tabs={["검색"]} actions={[]} closable={false} className="flex-[999_1_600px]" bodyClassName="p-4">
          <label className="flex h-14 items-center gap-3 rounded-xl border border-dracula-purple bg-tm-inner px-[18px]">
            <Icon name="search" size={22} className="text-dracula-purple" />
            <span className="sr-only">종목명 또는 티커 검색</span>
            <input
              ref={inputRef}
              type="search"
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={onKey}
              placeholder="삼성전자, AAPL, HBM ..."
              aria-controls="search-results"
              className="min-w-0 flex-1 bg-transparent text-lg text-dracula-fg outline-none placeholder:text-[#8b92b8] [&::-webkit-search-cancel-button]:hidden"
            />
            {loading && <span className="text-xs text-tm-muted">검색 중…</span>}
            <button type="button" onClick={() => setQuery("")} className="num rounded-md bg-tm-raised px-2 py-[3px] text-xs text-tm-muted hover:text-dracula-fg" aria-label="검색어 지우기 (ESC)">
              ESC
            </button>
          </label>
          <div className="flex flex-wrap gap-1.5">
            <Seg options={SCOPES} value={scope} onChange={setScope} size="lg" />
          </div>

          <div id="search-results" className="flex flex-col gap-3" aria-live="polite">
            {!q ? (
              <Section title={`최근 본 종목 ${recentlyViewed.length}`}>
                {recentlyViewed.length === 0 ? (
                  <p className="m-0 px-2.5 py-4 text-13 text-tm-muted">종목명 또는 티커로 검색하세요. 최근에 연 종목이 여기에 표시됩니다.</p>
                ) : (
                  <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
                    {recentlyViewed.map((e) => (
                      <li key={e.stockId}>
                        <Link href={`/stocks/${e.symbol}`} className="flex items-center gap-3 rounded-lg p-2.5 text-dracula-fg hover:bg-tm-raised hover:text-dracula-fg">
                          <StockCell name={e.name} code={e.symbol} />
                          <span className="text-xs text-tm-muted">{e.market}</span>
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </Section>
            ) : (
              <>
                {showStocks && (
                  <Section title={`종목 ${stocks.length}`}>
                    {stocksQ.isLoading ? (
                      <Loading />
                    ) : stocks.length === 0 ? (
                      <div className="px-2.5 py-4">
                        <p className="m-0 text-13 font-semibold">검색 결과가 없습니다</p>
                        <p className="m-0 mt-1 text-xs text-tm-muted">&ldquo;{q}&rdquo;에 해당하는 종목을 찾지 못했습니다.</p>
                      </div>
                    ) : (
                      <ul className="m-0 flex list-none flex-col gap-0.5 p-0" aria-label="종목 검색 결과">
                        {stocks.map((s, i) => (
                          <li key={s.id}>
                            <StockRow s={s} quote={quotes[s.id]} active={i === sel} onPick={() => go(s)} />
                          </li>
                        ))}
                      </ul>
                    )}
                  </Section>
                )}

                {(showEvents || showNews) && (
                  <Section title={`${showEvents && showNews ? "이벤트 · 뉴스" : showEvents ? "이벤트" : "뉴스"} ${(showEvents ? events.length : 0) + (showNews ? news.length : 0)}`}>
                    {(showEvents && eventsQ.isLoading) || (showNews && newsQ.isLoading) ? (
                      <Loading />
                    ) : (showEvents ? events.length : 0) + (showNews ? news.length : 0) === 0 ? (
                      <p className="m-0 px-2.5 py-4 text-13 text-tm-muted">일치하는 이벤트·뉴스가 없습니다.</p>
                    ) : (
                      <ul className="m-0 list-none p-0">
                        {showEvents && events.map((e) => {
                          const qt = quotes[e.stockId];
                          const body = (
                            <>
                              <EventBadge type={eventMeta(e.eventType).label} />
                              <span className="flex min-w-0 flex-col gap-0.5">
                                <span className="text-13">{qt && <b className="mr-1">{qt.name}</b>}{e.title}</span>
                                <span className="num text-2xs text-tm-muted">{fmtWhen(e.eventTime)}</span>
                              </span>
                            </>
                          );
                          const cls = "flex items-start gap-2.5 p-2.5 text-dracula-fg";
                          return (
                            <li key={`e${e.id}`} className="border-b border-tm-line">
                              {qt ? (
                                <Link href={`/stocks/${qt.symbol}`} onClick={() => remember(query)} className={cn(cls, "hover:bg-tm-raised/50 hover:text-dracula-fg")}>{body}</Link>
                              ) : (
                                <div className={cls}>{body}</div>
                              )}
                            </li>
                          );
                        })}
                        {showNews && news.map((n) => (
                          <li key={`n${n.id}`} className="border-b border-tm-line">
                            <a href={n.url} target="_blank" rel="noopener noreferrer" onClick={() => remember(query)} className="flex items-start gap-2.5 p-2.5 text-dracula-fg hover:bg-tm-raised/50 hover:text-dracula-fg">
                              <EventBadge type="뉴스" />
                              <span className="flex min-w-0 flex-col gap-0.5">
                                <span className="text-13">{n.title}</span>
                                <span className="text-2xs text-tm-muted">{[n.source, fmtWhen(n.publishedAt)].filter(Boolean).join(" · ")}</span>
                              </span>
                            </a>
                          </li>
                        ))}
                      </ul>
                    )}
                  </Section>
                )}

                {showStrategies && (
                  <Section title={`전략 ${strategies.length}`}>
                    {strategyQ.isLoading ? (
                      <Loading />
                    ) : strategies.length === 0 ? (
                      <p className="m-0 px-2.5 py-4 text-13 text-tm-muted">이름·설명·작성자가 일치하는 마켓 전략이 없습니다.</p>
                    ) : (
                      <ul className="m-0 list-none p-0">
                        {strategies.map((s) => (
                          <li key={s.id} className="border-b border-tm-line">
                            <Link href="/quant-lab/market" className="flex justify-between gap-2.5 p-2.5 text-dracula-fg hover:bg-tm-raised/50 hover:text-dracula-fg">
                              <span className="flex min-w-0 flex-col gap-0.5">
                                <span className="font-semibold">{s.name}</span>
                                <span className="text-xs text-tm-muted">전략 마켓{s.author_nickname ? ` · ${s.author_nickname}` : ""} · 구독 {fmtNum(s.subscribe_count ?? 0)}</span>
                              </span>
                              <Pill tone={s.price ? "muted" : "green"}>{s.price ? `${fmtNum(s.price)}원` : "무료"}</Pill>
                            </Link>
                          </li>
                        ))}
                      </ul>
                    )}
                  </Section>
                )}
              </>
            )}
          </div>
        </Panel>

        <PanelCol className="flex-[1_1_300px]">
          <Panel tabs={["최근 검색"]} actions={[]} closable={false} right={recent.length > 0 ? (
            <button type="button" onClick={() => { writeRecent([]); setRecent([]); }} className="text-xs text-tm-muted hover:text-dracula-fg">지우기</button>
          ) : undefined}>
            {recent.length === 0 ? (
              <p className="m-0 text-13 text-tm-muted">최근 검색어가 없습니다.</p>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {recent.map((t) => (
                  <Chip key={t} onClick={() => { setQuery(t); inputRef.current?.focus(); }} className="h-[30px]">{t}</Chip>
                ))}
              </div>
            )}
          </Panel>
          <PopularPanel />
        </PanelCol>
      </PanelRow>
    </TerminalPage>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="px-2.5 py-1 text-2xs text-tm-muted">{title}</span>
      {children}
    </div>
  );
}

function Loading() {
  return <div className="mx-2.5 h-12 animate-pulse rounded-lg bg-tm-inner" aria-busy="true" aria-label="검색 중" />;
}

function StockRow({ s, quote, active, onPick }: { s: StockResult; quote?: Quote; active: boolean; onPick: () => void }) {
  return (
    <Link
      href={`/stocks/${s.symbol}`}
      onClick={(e) => { e.preventDefault(); onPick(); }}
      aria-current={active ? "true" : undefined}
      className={cn("flex items-center gap-3 rounded-lg p-2.5 text-dracula-fg hover:bg-tm-raised hover:text-dracula-fg", active && "bg-tm-raised")}
    >
      <StockCell name={s.name} code={s.symbol} />
      <span className="text-xs text-tm-muted">{[s.sector, s.market].filter(Boolean).join(" · ")}</span>
      <span className="ml-auto flex flex-col items-end">
        <span className="num">{quote ? fmtNum(quote.price, quote.price < 100 ? 2 : 0) : "—"}</span>
        <span className={cn("num text-2xs", quote ? (quote.changeRate >= 0 ? "text-up" : "text-down") : "text-tm-muted")}>
          {quote ? `${quote.changeRate >= 0 ? "+" : ""}${quote.changeRate.toFixed(2)}%` : ""}
        </span>
      </span>
    </Link>
  );
}

/** 많이 찾는 종목 — 검색 통계가 아직 없어 거래대금 상위 종목으로 대신한다(라벨로 밝힌다). */
function PopularPanel() {
  const { data = [], isLoading } = useQuery<Quote[]>({
    queryKey: ["screener", "search-popular"],
    queryFn: async () => {
      const r = await fetch("/api/screener?tab=realtime&market=all&sort=amount&limit=6");
      if (!r.ok) return [];
      return (await r.json())?.items ?? [];
    },
    staleTime: 60_000,
  });
  return (
    <Panel tabs={["많이 찾는 종목"]} actions={[]} closable={false} preview>
      <span className="text-2xs text-tm-muted">검색 통계 집계 전이라 지금은 거래대금 상위 종목을 보여 줍니다.</span>
      {isLoading ? (
        <div className="h-32 animate-pulse rounded-lg bg-tm-inner" />
      ) : data.length === 0 ? (
        <p className="m-0 text-13 text-tm-muted">시세 데이터가 없습니다.</p>
      ) : (
        <ol className="m-0 list-none p-0">
          {data.map((s, i) => (
            <li key={s.stockId}>
              <Link href={`/stocks/${s.symbol}`} className="flex items-center gap-3 py-[7px] text-dracula-fg hover:text-dracula-fg">
                <span className={cn("num w-[18px] font-semibold", i < 3 ? "text-dracula-purple" : "text-tm-muted")}>{i + 1}</span>
                <span className="flex-1">{s.name}</span>
                <span className={cn("num text-xs", s.changeRate >= 0 ? "text-up" : "text-down")}>
                  {s.changeRate >= 0 ? "+" : ""}{s.changeRate.toFixed(2)}%
                </span>
              </Link>
            </li>
          ))}
        </ol>
      )}
    </Panel>
  );
}
