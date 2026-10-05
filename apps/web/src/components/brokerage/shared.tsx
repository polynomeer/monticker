"use client";

// 실전투자(브로커리지) 화면들이 같이 쓰는 조각 — 실계좌 경고, 로그인/미연동 안내, 종목 검색,
// 계좌번호 마스킹, 상태 표기. 실거래 화면이라 숫자를 지어내지 않는다: 모르는 값은 "—".
import { useEffect, useState, type ReactNode } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import type { BrokerageAccountResponse, BrokerageBalanceResponse, BrokerageOrderStatus, RebalanceExecutionResponse } from "@monticker/types";
import { BtnLink, Icon, Notice, Panel, TerminalPage, fmtNum, type Tone, type TopStat } from "@/components/terminal";
import { brokerageProviderLabel } from "@/lib/brokerageProvider";

export interface StockHit { id: number; symbol: string; name: string; }

/** 원화 금액 — 모르면 "—" */
export function won(n: number | null | undefined) {
  return n == null || Number.isNaN(n) ? "—" : `${fmtNum(n)}원`;
}

/** 계좌번호는 끝 두 자리만 보여 준다(어깨너머·화면 공유 노출 방지). */
export function maskAccount(accountNumber: string | null | undefined) {
  if (!accountNumber) return "—";
  return `••••-••${accountNumber.slice(-2)}`;
}

/** 시안의 실계좌 경고 — 실거래 경로 화면 맨 위에 항상 둔다. */
export function LiveNotice() {
  return (
    <Notice tone="warn" icon="alert">
      이 화면의 주문은 <b>실제 증권사 계좌에서 실제 돈으로</b> 체결됩니다. monticker는 자금을 보관하지 않으며, 체결 결과는 증권사 처리 결과를 따릅니다.
    </Notice>
  );
}

/** 로그인 필요 · 계좌 미연동 같은 진입 차단 화면 — 터미널 골격 안에 가운데 패널로 그린다. */
export function GateScreen({ title, crumb, panelTitle, children }: { title: string; crumb?: string; panelTitle: string; children: ReactNode }) {
  return (
    <TerminalPage title={title} crumb={crumb} account={{ kind: "live" }}>
      <div className="mx-auto w-full max-w-[520px] pt-10">
        <Panel tabs={[panelTitle]} actions={[]} closable={false} bodyClassName="items-center gap-4 p-8 text-center">
          {children}
        </Panel>
      </div>
    </TerminalPage>
  );
}

export function LoginRequired({ title, message }: { title: string; message: string }) {
  return (
    <GateScreen title={title} crumb="실전투자" panelTitle="로그인 필요">
      <p className="text-13 text-tm-soft">{message}</p>
      <BtnLink href="/login">로그인</BtnLink>
    </GateScreen>
  );
}

export function NoAccount({ title, message }: { title: string; message: ReactNode }) {
  return (
    <GateScreen title={title} crumb="실전투자" panelTitle="연동된 계좌 없음">
      <p className="text-15 font-semibold text-dracula-fg">연동된 증권사 계좌가 없습니다</p>
      <p className="text-xs leading-relaxed text-tm-muted">{message}</p>
      <BtnLink href="/brokerage/connect" icon="key">계좌 연동하기</BtnLink>
    </GateScreen>
  );
}

/**
 * 종목 검색(200ms 디바운스). V-L6 — 늦게 도착한 이전 검색어 응답이 최신 결과를 덮어쓰지 않도록
 * 다음 검색어가 오거나 언마운트되면 진행 중인 요청을 취소한다.
 */
export function useStockSearch(query: string) {
  const [results, setResults] = useState<StockHit[]>([]);
  useEffect(() => {
    if (query.length < 1) { setResults([]); return; }
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      try {
        const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(query)}`, { signal: controller.signal });
        if (r.ok) setResults((await r.json()).slice(0, 6));
      } catch (e) {
        if ((e as Error).name !== "AbortError") throw e;
      }
    }, 200);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [query]);
  return [results, setResults] as const;
}

/** 종목 검색 입력 + 결과 드롭다운. 선택하면 onSelect, 입력은 비운다. */
export function StockSearchBox({ onSelect, placeholder = "종목명 또는 코드 검색", label = "종목" }: { onSelect: (hit: StockHit) => void; placeholder?: string; label?: string }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useStockSearch(query);
  return (
    <div className="relative">
      <label className="flex min-h-10 items-center gap-2 rounded-lg border border-tm-line bg-tm-inner px-3 py-1.5 focus-within:border-dracula-purple">
        <span className="sr-only">{label}</span>
        <Icon name="search" size={16} className="flex-none text-tm-muted" />
        <input
          type="text"
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder={placeholder}
          className="min-w-0 flex-1 bg-transparent text-sm text-dracula-fg outline-none placeholder:text-[#8b92b8]"
        />
      </label>
      {results.length > 0 && (
        <div className="absolute z-20 mt-1 w-full overflow-hidden rounded-lg border border-tm-line2 bg-tm-panel shadow-lg">
          {results.map(r => (
            <button
              key={r.id}
              type="button"
              onClick={() => { onSelect(r); setQuery(""); setResults([]); }}
              className="flex w-full items-baseline gap-2 px-3 py-2.5 text-left text-13 hover:bg-tm-raised"
            >
              <span className="font-medium text-dracula-fg">{r.name}</span>
              <span className="num text-2xs text-tm-muted">{r.symbol}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** 선택된 종목 표시 + "변경" — 검색 상자 자리에 그린다. */
export function SelectedStock({ stock, price, onClear }: { stock: StockHit; price?: number; onClear: () => void }) {
  return (
    <div className="flex min-h-10 items-center justify-between gap-3 rounded-lg border border-tm-line bg-tm-inner px-3 py-1.5">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-2xs text-tm-muted">종목</span>
        <span className="truncate text-sm">
          <span className="font-semibold text-dracula-fg">{stock.name}</span>
          <span className="num ml-2 text-2xs text-tm-muted">{stock.symbol}</span>
          {price != null && price > 0 && <span className="num ml-2 text-xs text-tm-soft">· 현재가 {fmtNum(price)}</span>}
        </span>
      </div>
      <button type="button" onClick={onClear} className="text-xs text-tm-muted hover:text-dracula-fg">변경</button>
    </div>
  );
}

/** 종목 코드 → 이름·id·현재가. 목록 행마다 현재가가 필요할 때(조건부 주문의 "트리거까지") 쓴다. */
export interface SymbolQuote { id: number; name: string; price: number | null; }

export function useSymbolQuotes(symbols: string[], withPrice: boolean) {
  const unique = Array.from(new Set(symbols)).sort();
  const results = useQueries({
    queries: unique.map(symbol => ({
      queryKey: ["brokerage", "symbol-quote", symbol, withPrice],
      queryFn: async (): Promise<SymbolQuote | null> => {
        const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(symbol)}`);
        const hits: StockHit[] = r.ok ? await r.json() : [];
        const hit = hits.find(h => h.symbol === symbol);
        if (!hit) return null;
        if (!withPrice) return { id: hit.id, name: hit.name, price: null };
        const p = await fetch(`/api/stocks/${hit.id}/price`);
        const data = p.ok ? await p.json() : null;
        const price = data?.price != null ? Number(data.price) : null;
        return { id: hit.id, name: hit.name, price: price && price > 0 ? price : null };
      },
      staleTime: withPrice ? 10_000 : 5 * 60_000,
      refetchInterval: withPrice ? 10_000 : (false as const),
    })),
  });
  const map = new Map<string, SymbolQuote>();
  unique.forEach((s, i) => { const d = results[i]?.data; if (d) map.set(s, d); });
  return map;
}

// ── 주문 상태 ──────────────────────────────────────────────────────────
export const ORDER_STATUS: Record<BrokerageOrderStatus, { label: string; tone: Tone }> = {
  PENDING_SUBMIT:   { label: "제출 중",      tone: "orange" },
  UNKNOWN:          { label: "결과 확인 중", tone: "orange" },
  SUBMITTED:        { label: "접수됨",       tone: "yellow" },
  FILLED:           { label: "체결",         tone: "green" },
  PARTIALLY_FILLED: { label: "부분 체결",    tone: "cyan" },
  CANCELLED:        { label: "취소됨",       tone: "muted" },
  REJECTED:         { label: "거부됨",       tone: "red" },
};

export function sideLabel(side: "BUY" | "SELL") {
  return side === "BUY" ? "매수" : "매도";
}

export function sideClass(side: "BUY" | "SELL") {
  return side === "BUY" ? "text-up" : "text-down";
}

export function fmtDateTime(iso: string) {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 상단 바 스트립 — 계좌/잔고에서 온 값만. 지연시간(ms) 같은 측정값은 없으므로 상태만 표시한다. */
export function brokerageStats(account: BrokerageAccountResponse | null | undefined, balance: BrokerageBalanceResponse | null | undefined, balanceError: boolean): TopStat[] {
  return [
    { label: "연동 증권사", value: account ? brokerageProviderLabel(account.provider) : "—" },
    { label: "계좌", value: maskAccount(account?.accountNumber) },
    { label: "가용 현금", value: balanceError ? "조회 불가" : won(balance?.cash), tone: balanceError ? "text-dracula-orange" : undefined },
    { label: "총 평가금액", value: balanceError ? "조회 불가" : won(balance?.totalEvaluated), tone: balanceError ? "text-dracula-orange" : undefined },
    apiStat(account),
  ];
}

export function apiStat(account: BrokerageAccountResponse | null | undefined): TopStat {
  if (!account) return { label: "API 상태", value: "—" };
  return account.tokenValid
    ? { label: "API 상태", value: "정상", tone: "text-dracula-green" }
    : { label: "API 상태", value: "재인증 필요", tone: "text-dracula-orange" };
}

export function PagerButtons({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  if (totalPages <= 1) return null;
  const cls = "inline-flex h-[30px] items-center rounded-lg border border-tm-line2 px-3 text-xs font-semibold text-dracula-fg hover:bg-tm-raised disabled:opacity-40";
  return (
    <div className="flex items-center justify-end gap-1.5 p-1.5">
      <span className="num mr-1 text-2xs text-tm-muted">{page + 1} / {totalPages}</span>
      <button type="button" className={cls} disabled={page <= 0} onClick={() => onChange(page - 1)}>이전</button>
      <button type="button" className={cls} disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>다음</button>
    </div>
  );
}

export function SkeletonRows({ n = 3 }: { n?: number }) {
  return (
    <div className="flex flex-col gap-2 p-1.5" aria-busy="true">
      {Array.from({ length: n }, (_, i) => <div key={i} className="h-9 animate-pulse rounded-md bg-tm-inner" />)}
    </div>
  );
}


/** 가장 최근 리밸런싱 실행(GET /api/rebalance/executions, 최신순) — 읽기 전용. 실행 직후 다시 읽는다. */
export function useLastRebalanceExecution(enabled: boolean) {
  return useQuery({
    queryKey: ["brokerage", "rebalance-executions", "latest"],
    queryFn: async (): Promise<RebalanceExecutionResponse | null> => {
      const res = await authFetch("/api/rebalance/executions?page=0&size=1");
      if (!res.ok) return null;
      const page: { content?: RebalanceExecutionResponse[] } = await res.json();
      return page.content?.[0] ?? null;
    },
    enabled,
  });
}
