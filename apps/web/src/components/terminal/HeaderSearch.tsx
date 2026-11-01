"use client";

import { forwardRef, useEffect, useId, useImperativeHandle, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";
import { useGlobalSearch } from "@/hooks/useGlobalSearch";
import { Icon } from "./Icon";

/** 이벤트 유형 → 미리보기 라벨(검색 화면·종목 화면과 같은 이름) */
const EVENT_LABEL: Record<string, string> = {
  PRICE_SPIKE: "급등", PRICE_DROP: "급락", VOLUME_SURGE: "거래량", DISCLOSURE_PUBLISHED: "공시", NEWS_PUBLISHED: "뉴스",
};

interface Option {
  id: string;
  group: "종목" | "이벤트" | "전략";
  label: string;
  sub?: string;
  href: string;
}

export interface HeaderSearchHandle { focus: () => void }

/**
 * ADR-112 — 모든 화면 상단에 고정된 검색창(combobox). 입력하면 그 자리에서 종목·이벤트·전략 미리보기가 열리고,
 * 항목을 고르면 바로 그 화면으로 간다. "전체 결과"만 검색 화면(/stocks/search?q=)으로 간다. ⌘K는 셸이 이 입력창에 포커스를 준다.
 */
export const HeaderSearch = forwardRef<HeaderSearchHandle>(function HeaderSearch(_props, ref) {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const listId = useId();
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const { q, stocks, events, strategies, loading } = useGlobalSearch(query);

  useImperativeHandle(ref, () => ({ focus: () => { inputRef.current?.focus(); inputRef.current?.select(); } }), []);

  // 이벤트 결과에는 종목 id만 있다 — 심볼을 시세 목록에서 붙인다
  const eventIds = [...new Set(events.map((e) => e.stockId))].sort((a, b) => a - b);
  const { data: quotes = [] } = useQuery<{ stockId: number; symbol: string; name: string }[]>({
    queryKey: ["header-search", "quotes", eventIds],
    queryFn: async () => {
      const r = await fetch(`/api/screener/quotes?ids=${eventIds.join(",")}`);
      if (!r.ok) return [];
      return (await r.json()).items ?? [];
    },
    enabled: eventIds.length > 0,
    staleTime: 30_000,
  });

  const options = useMemo<Option[]>(() => {
    const bySymbol = new Map(quotes.map((x) => [x.stockId, x]));
    return [
      ...stocks.map((s) => ({ id: `s${s.id}`, group: "종목" as const, label: s.name, sub: `${s.symbol} · ${s.market}`, href: `/stocks/${s.symbol}` })),
      ...events.flatMap((e) => {
        const st = bySymbol.get(e.stockId);
        return st ? [{ id: `e${e.id}`, group: "이벤트" as const, label: e.title, sub: `${EVENT_LABEL[e.eventType] ?? "이벤트"} · ${st.name}`, href: `/stocks/${st.symbol}` }] : [];
      }),
      ...strategies.map((s) => ({ id: `q${s.id}`, group: "전략" as const, label: s.name, sub: `전략 마켓 · 구독 ${s.subscribe_count ?? 0}명`, href: "/quant-lab/market" })),
    ];
  }, [stocks, events, strategies, quotes]);

  useEffect(() => setActive(0), [q]);
  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => boxRef.current && !boxRef.current.contains(e.target as Node) && setOpen(false);
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, [open]);

  const allHref = `/stocks/search?q=${encodeURIComponent(query.trim())}`;
  const go = (href: string) => {
    setOpen(false);
    setQuery("");
    inputRef.current?.blur();
    router.push(href);
  };

  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Escape") {
      if (open && query) { setOpen(false); return; }
      setQuery("");
      inputRef.current?.blur();
      return;
    }
    if (!query.trim()) return;
    // 마지막 칸(options.length)은 "전체 결과"
    const last = options.length;
    if (e.key === "ArrowDown") { e.preventDefault(); setOpen(true); setActive((i) => Math.min(last, i + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((i) => Math.max(0, i - 1)); }
    else if (e.key === "Enter") { e.preventDefault(); go(active < options.length ? options[active].href : allHref); }
  };

  const showList = open && !!query.trim();
  const activeId = showList ? (active < options.length ? `${listId}-${options[active].id}` : `${listId}-all`) : undefined;
  let lastGroup = "";

  return (
    <div ref={boxRef} className="relative flex-[0_1_280px] min-w-[180px]">
      <label className={cn(
        "flex h-9 items-center gap-2 rounded-lg border bg-tm-panel px-2.5 text-13",
        showList ? "border-dracula-purple" : "border-transparent focus-within:border-dracula-purple",
      )}>
        <Icon name="search" size={16} className="flex-none text-tm-muted" />
        <span className="sr-only">종목·이벤트·전략 검색</span>
        <input
          ref={inputRef}
          type="search"
          role="combobox"
          aria-expanded={showList}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={activeId}
          value={query}
          onChange={(e) => { setQuery(e.target.value); setOpen(true); }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKey}
          placeholder="종목·이벤트·전략 검색"
          className="min-w-0 flex-1 bg-transparent text-dracula-fg outline-hidden placeholder:text-tm-muted [&::-webkit-search-cancel-button]:hidden"
        />
        {!query && <kbd className="num flex-none rounded-sm bg-tm-raised px-1.5 text-2xs text-tm-muted">⌘K</kbd>}
      </label>

      {showList && (
        <div className="absolute left-0 top-full z-50 mt-1.5 w-[min(420px,90vw)] overflow-hidden rounded-[10px] border border-tm-line2 bg-tm-panel py-1 shadow-glow-line">
          <ul id={listId} role="listbox" aria-label="검색 미리보기" className="m-0 max-h-[60vh] list-none overflow-y-auto p-0">
            {options.map((o, i) => {
              const head = o.group !== lastGroup ? o.group : null;
              lastGroup = o.group;
              return (
                <li key={o.id} role="presentation">
                  {head && <div className="px-3 pb-1 pt-2 text-2xs font-semibold text-tm-muted" aria-hidden>{head}</div>}
                  <div
                    id={`${listId}-${o.id}`}
                    role="option"
                    aria-selected={i === active}
                    onMouseEnter={() => setActive(i)}
                    onMouseDown={(e) => { e.preventDefault(); go(o.href); }}
                    className={cn("flex cursor-pointer items-baseline gap-2 px-3 py-1.5", i === active && "bg-tm-raised")}
                  >
                    <span className="truncate text-13 text-dracula-fg">{o.label}</span>
                    {o.sub && <span className="flex-none truncate text-2xs text-tm-muted">{o.sub}</span>}
                  </div>
                </li>
              );
            })}
            {options.length === 0 && (
              <li role="presentation" className="px-3 py-2.5 text-xs text-tm-muted">{loading || q !== query.trim() ? "찾는 중…" : `“${query.trim()}”에 맞는 미리보기가 없어요`}</li>
            )}
            <li role="presentation" className="mt-1 border-t border-tm-line">
              <div
                id={`${listId}-all`}
                role="option"
                aria-selected={active === options.length}
                onMouseEnter={() => setActive(options.length)}
                onMouseDown={(e) => { e.preventDefault(); go(allHref); }}
                className={cn("flex cursor-pointer items-center justify-between px-3 py-2 text-xs text-dracula-purple", active === options.length && "bg-tm-raised")}
              >
                “{query.trim()}” 전체 결과 보기 <Icon name="chevr" size={14} className="text-tm-muted" />
              </div>
            </li>
          </ul>
        </div>
      )}
    </div>
  );
});
