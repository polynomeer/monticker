"use client";

import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import {
  Btn, DataTable, IconBtn, Legend, LineChart, Panel, PanelRow, Seg, TerminalPage, TitleBlock, type Column,
} from "@/components/terminal";
import StockChart from "@/components/stock/chart/StockChart";
import type { CandleData } from "@/components/stock/chart/types";
import { useQuotes } from "@/components/stock/parts";
import { cn } from "@/lib/utils";
import { beta, kstDayKey } from "./beta";

const COLORS = ["#bd93f9", "#8be9fd", "#ffb86c", "#ff79c6"];
const MAX = 4;

const PERIODS = [
  { value: "1M", label: "1개월", days: 30 },
  { value: "3M", label: "3개월", days: 90 },
  { value: "6M", label: "6개월", days: 180 },
  { value: "1Y", label: "1년", days: 365 },
  { value: "3Y", label: "3년", days: 365 * 3 },
] as const;
type Period = (typeof PERIODS)[number]["value"];

interface StockInfo { id: number; symbol: string; name: string; market: string; }
interface Ev { id: number; eventTime: string; eventType: string; title: string; }

const dayKey = (t: number) => new Date(t * 1000).toISOString().slice(0, 10);

function returns(closes: number[]) {
  return closes.slice(1).map((v, i) => (closes[i] ? v / closes[i] - 1 : 0));
}
function stdev(xs: number[]) {
  if (xs.length < 2) return null;
  const m = xs.reduce((s, v) => s + v, 0) / xs.length;
  return Math.sqrt(xs.reduce((s, v) => s + (v - m) ** 2, 0) / (xs.length - 1));
}
function corr(a: number[], b: number[]) {
  const n = a.length;
  if (n < 3) return null;
  const ma = a.reduce((s, v) => s + v, 0) / n;
  const mb = b.reduce((s, v) => s + v, 0) / n;
  let num = 0, da = 0, db = 0;
  for (let i = 0; i < n; i++) {
    num += (a[i] - ma) * (b[i] - mb);
    da += (a[i] - ma) ** 2;
    db += (b[i] - mb) ** 2;
  }
  return da && db ? num / Math.sqrt(da * db) : null;
}
const pct = (v: number | null, d = 1) => (v == null || !Number.isFinite(v) ? "—" : `${v > 0 ? "+" : ""}${(v * 100).toFixed(d)}%`);

async function resolve(symbol: string): Promise<StockInfo | null> {
  const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(symbol)}`);
  if (!r.ok) return null;
  const list: StockInfo[] = await r.json();
  return list.find((s) => s.symbol === symbol) ?? null;
}

async function candlesFrom(id: number, fromIso: string): Promise<CandleData[]> {
  const r = await fetch(`/api/stocks/${id}/candles?interval=1d&from=${encodeURIComponent(fromIso)}`);
  if (!r.ok) return [];
  const data: { time: number; open: string; high: string; low: string; close: string; volume?: number }[] = await r.json();
  return data
    .map((c) => ({ time: c.time, open: +c.open, high: +c.high, low: +c.low, close: +c.close, volume: c.volume ?? 0 }))
    .sort((a, b) => a.time - b.time);
}

/** KOSPI 일별 종가(ADR-071). isMocked면 개발용 모의 지수다 */
async function kospiFrom(fromIso: string): Promise<{ date: string; close: number; isMocked: boolean }[]> {
  const r = await fetch(`/api/market/indices/KOSPI/daily?from=${fromIso.slice(0, 10)}`);
  if (!r.ok) return [];
  const data: { date: string; close: string | number; isMocked: boolean }[] = await r.json();
  return data.map((d) => ({ date: d.date, close: +d.close, isMocked: d.isMocked }));
}

async function eventsFrom(id: number, fromIso: string): Promise<Ev[]> {
  const r = await fetch(`/api/stocks/${id}/events?from=${encodeURIComponent(fromIso)}&limit=100`);
  return r.ok ? r.json() : [];
}

/** 종목 비교 — 시안 Compare.dc.html. 정규화 수익률(시작일=100) · 지표 비교 · 일간 수익률 상관관계. */
export default function ComparePage() {
  const [symbols, setSymbols] = useState(["005930", "000660"]);
  const [period, setPeriod] = useState<Period>("6M");
  const [chartTab, setChartTab] = useState("chart");
  const [adding, setAdding] = useState(false);
  const [input, setInput] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);

  const days = PERIODS.find((p) => p.value === period)!.days;
  // 기간 시작 시각은 날짜 단위로 고정해 쿼리 키가 렌더마다 바뀌지 않게 한다.
  const fromIso = useMemo(() => {
    const d = new Date();
    d.setUTCHours(0, 0, 0, 0);
    d.setUTCDate(d.getUTCDate() - days);
    return d.toISOString();
  }, [days]);

  const infos = useQueries({
    queries: symbols.map((s) => ({ queryKey: ["compare", "resolve", s], queryFn: () => resolve(s), staleTime: 10 * 60_000 })),
  });
  const stocks = symbols.map((s, i) => ({ symbol: s, info: infos[i]?.data ?? null, resolving: infos[i]?.isLoading ?? true, color: COLORS[i % COLORS.length] }));
  const ids = stocks.map((s) => s.info?.id ?? null);

  const candleQs = useQueries({
    queries: ids.map((id) => ({ queryKey: ["compare", "candles", id, fromIso], queryFn: () => candlesFrom(id!, fromIso), enabled: id != null, staleTime: 60_000 })),
  });
  const eventQs = useQueries({
    queries: ids.map((id) => ({ queryKey: ["compare", "events", id, fromIso], queryFn: () => eventsFrom(id!, fromIso), enabled: id != null, staleTime: 60_000 })),
  });
  const quotes = useQuotes(ids.filter((x): x is number => x != null), 60_000);
  const { data: kospi = [] } = useQuery({ queryKey: ["compare", "kospi", fromIso], queryFn: () => kospiFrom(fromIso), staleTime: 10 * 60_000 });
  const kospiByDay = useMemo(() => new Map(kospi.map((d) => [d.date, d.close])), [kospi]);
  const kospiMocked = kospi.some((d) => d.isMocked);
  const loadingCandles = candleQs.some((q) => q.isLoading && q.fetchStatus !== "idle");

  // 날짜 합집합 위에 각 종목 종가를 앞 값으로 채워 정렬한다(국내·해외 휴장일이 달라도 같은 x축).
  const aligned = useMemo(() => {
    const series = candleQs.map((q) => q.data ?? []);
    const keys = Array.from(new Set(series.flatMap((cs) => cs.map((c) => dayKey(c.time))))).sort();
    const maps = series.map((cs) => new Map(cs.map((c) => [dayKey(c.time), c.close])));
    const filled = maps.map((m) => {
      let last: number | null = null;
      const first = keys.map((k) => m.get(k)).find((v) => v != null) ?? null;
      return keys.map((k) => {
        const v = m.get(k);
        if (v != null) last = v;
        return last ?? first;
      });
    });
    return { keys, filled, maps };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [candleQs.map((q) => q.dataUpdatedAt).join(",")]);

  const lineSeries = stocks
    .map((s, i) => {
      const vals = aligned.filled[i] ?? [];
      const base = vals.find((v) => v != null);
      if (!base || vals.length < 2) return null;
      return { values: vals.map((v) => ((v ?? base) / base) * 100), color: s.color };
    })
    .filter((x): x is { values: number[]; color: string } => x != null);

  const xLabels: [number, string][] = aligned.keys.length > 1
    ? [0, 0.25, 0.5, 0.75, 0.98].map((f) => {
        const k = aligned.keys[Math.min(aligned.keys.length - 1, Math.round(f * (aligned.keys.length - 1)))];
        return [f, f === 0 ? k.slice(0, 7).replace("-", ".") : k.slice(5).replace("-", ".")] as [number, string];
      })
    : [];

  // ── 지표 ─────────────────────────────────────────────────────────────
  const metrics = stocks.map((s, i) => {
    const cs = candleQs[i]?.data ?? [];
    const closes = cs.map((c) => c.close);
    const rs = returns(closes);
    const sd = stdev(rs);
    let peak = -Infinity, mdd = 0;
    for (const c of closes) { peak = Math.max(peak, c); mdd = Math.min(mdd, c / peak - 1); }
    const evs = eventQs[i]?.data ?? [];
    const idxByDay = new Map(cs.map((c, k) => [dayKey(c.time), k]));
    const after = evs
      .map((e) => {
        const k = idxByDay.get(new Date(e.eventTime).toISOString().slice(0, 10));
        return k != null && k + 1 < cs.length ? cs[k + 1].close / cs[k].close - 1 : null;
      })
      .filter((v): v is number => v != null);
    const q = s.info ? quotes[s.info.id] : undefined;
    // 국내 종목만 KOSPI 베타를 낸다 — 해외 종목을 KOSPI에 회귀하면 숫자는 나오지만 뜻이 없다
    const domestic = s.info ? ["KOSPI", "KOSDAQ"].includes(s.info.market) : false;
    const b = domestic && kospiByDay.size > 0 ? beta(new Map(cs.map((c) => [kstDayKey(c.time), c.close])), kospiByDay) : null;
    return {
      beta: b,
      betaNote: !domestic && s.info ? "해외" : null,
      ret: closes.length > 1 ? closes[closes.length - 1] / closes[0] - 1 : null,
      vol: sd != null ? sd * Math.sqrt(252) : null,
      mdd: closes.length > 1 ? mdd : null,
      events: eventQs[i]?.data ? evs.length : null,
      afterAvg: after.length ? after.reduce((a, b) => a + b, 0) / after.length : null,
      per: q?.per != null && !q.isFundamentalsMocked ? q.per : null,
    };
  });

  const periodLabel = PERIODS.find((p) => p.value === period)!.label;
  type Row = { key: string; label: string; cells: { text: string; cls?: string }[] };
  const dirCls = (v: number | null) => (v == null ? "" : v > 0 ? "text-up" : v < 0 ? "text-down" : "");
  const rows: Row[] = [
    { key: "ret", label: `${periodLabel} 수익률`, cells: metrics.map((m) => ({ text: pct(m.ret), cls: dirCls(m.ret) })) },
    { key: "vol", label: "변동성 (연)", cells: metrics.map((m) => ({ text: m.vol == null ? "—" : `${(m.vol * 100).toFixed(1)}%` })) },
    { key: "mdd", label: "최대 낙폭", cells: metrics.map((m) => ({ text: pct(m.mdd), cls: m.mdd ? "text-down" : "" })) },
    {
      key: "beta",
      label: kospiMocked ? "베타 (KOSPI · 모의 지수)" : "베타 (KOSPI)",
      cells: metrics.map((m) => ({ text: m.beta == null ? (m.betaNote ? `— (${m.betaNote})` : "—") : m.beta.toFixed(2), cls: m.beta == null ? "text-tm-muted" : kospiMocked ? "text-tm-soft" : "" })),
    },
    { key: "ev", label: `이벤트 수 (${periodLabel})`, cells: metrics.map((m) => ({ text: m.events == null ? "—" : m.events >= 100 ? "100+" : String(m.events) })) },
    { key: "after", label: "이벤트 후 1일 평균", cells: metrics.map((m) => ({ text: pct(m.afterAvg, 2), cls: dirCls(m.afterAvg) })) },
    { key: "per", label: "PER", cells: metrics.map((m) => ({ text: m.per == null ? "—" : m.per.toFixed(1) })) },
    { key: "div", label: "배당수익률", cells: metrics.map(() => ({ text: "—", cls: "text-tm-muted" })) },
  ];
  const nameOf = (s: (typeof stocks)[number]) => s.info?.name ?? s.symbol;
  const columns: Column<Row>[] = [
    { key: "m", header: "지표", cell: (r) => <span className="text-tm-soft">{r.label}</span> },
    ...stocks.map((s, i) => ({
      key: s.symbol,
      header: <><span style={{ color: s.color }}>●</span> {nameOf(s)}</>,
      align: "right" as const,
      cell: (r: Row) => <span className={cn("num", r.cells[i]?.cls)}>{r.cells[i]?.text ?? "—"}</span>,
    })),
  ];

  // ── 상관관계 ─────────────────────────────────────────────────────────
  const corrMatrix = stocks.map((_, a) =>
    stocks.map((__, b) => {
      if (a === b) return aligned.maps[a]?.size ? 1 : null;
      const ma = aligned.maps[a], mb = aligned.maps[b];
      if (!ma || !mb) return null;
      const common = aligned.keys.filter((k) => ma.has(k) && mb.has(k));
      const ra = returns(common.map((k) => ma.get(k)!));
      const rb = returns(common.map((k) => mb.get(k)!));
      return corr(ra, rb);
    }),
  );

  const addSymbol = (raw: string) => {
    const s = raw.trim().toUpperCase();
    if (s && !symbols.includes(s) && symbols.length < MAX) {
      setSymbols((prev) => [...prev, s]);
      setInput("");
      setAdding(false);
    }
  };
  useEffect(() => { if (adding) inputRef.current?.focus(); }, [adding]);

  const { data: suggestions = [] } = useQuery<StockInfo[]>({
    queryKey: ["compare", "suggest", input.trim()],
    queryFn: async ({ signal }) => {
      const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(input.trim())}`, { signal });
      return r.ok ? r.json() : [];
    },
    enabled: adding && input.trim().length > 0,
    staleTime: 30_000,
  });

  return (
    <TerminalPage
      left={<TitleBlock title="종목 비교" crumb="마켓" />}
      stats={[
        { label: "비교 종목", value: `${symbols.length} / ${MAX}` },
        { label: "기간", value: periodLabel },
        { label: "기준", value: "시작일 = 100" },
      ]}
    >
      <Panel
        tabs={[{ key: "chart", label: "비교 차트" }, { key: "events", label: "이벤트 겹침" }, { key: "candles", label: "개별 차트" }]}
        active={chartTab}
        onTabChange={setChartTab}
        actions={["sliders", "download", "expand"]}
      >
        <div className="flex flex-wrap items-center gap-2">
          {stocks.map((s) => (
            <span key={s.symbol} className="inline-flex h-[34px] items-center gap-2 rounded-lg border border-tm-line2 bg-tm-inner pl-3 pr-1.5">
              <span className="h-2.5 w-2.5 rounded-[3px]" style={{ background: s.color }} />
              <span className="font-semibold">{nameOf(s)}</span>
              {!s.resolving && !s.info && <span className="text-2xs text-[#ff8a8a]">찾을 수 없음</span>}
              <IconBtn name="x" label={`${nameOf(s)} 비교에서 제거`} size={24} iconSize={12} onClick={() => setSymbols((p) => p.filter((x) => x !== s.symbol))} />
            </span>
          ))}
          {adding ? (
            <div className="relative">
              <input
                ref={inputRef}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") addSymbol(input);
                  if (e.key === "Escape") { setAdding(false); setInput(""); }
                }}
                onBlur={() => setTimeout(() => setAdding(false), 150)}
                aria-label="비교할 종목 코드"
                placeholder="종목명 또는 코드"
                className="h-[34px] w-52 rounded-lg border border-dracula-purple bg-tm-inner px-3 text-13 text-dracula-fg outline-none placeholder:text-[#8b92b8]"
              />
              {suggestions.length > 0 && (
                <ul className="absolute left-0 top-full z-20 mt-1 max-h-64 w-64 list-none overflow-y-auto rounded-[10px] border border-tm-line2 bg-tm-panel p-1 shadow-glow-line">
                  {suggestions.slice(0, 8).map((s) => (
                    <li key={s.id}>
                      <button type="button" onMouseDown={(e) => { e.preventDefault(); addSymbol(s.symbol); }} disabled={symbols.includes(s.symbol)} className="flex w-full items-center justify-between gap-2 rounded-md px-2.5 py-2 text-left text-13 hover:bg-tm-raised disabled:opacity-40">
                        <span className="font-semibold">{s.name}</span>
                        <span className="num text-2xs text-tm-muted">{s.symbol} · {s.market}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ) : (
            <Btn kind="ghost" icon="plus" className="h-[34px]" disabled={symbols.length >= MAX} onClick={() => setAdding(true)} title={symbols.length >= MAX ? "최대 4종목까지 비교할 수 있습니다" : undefined}>
              종목 추가
            </Btn>
          )}
          <div className="ml-auto">
            <Seg options={PERIODS.map((p) => ({ value: p.value, label: p.label }))} value={period} onChange={setPeriod} size="lg" />
          </div>
        </div>

        {chartTab === "chart" && (
          loadingCandles ? (
            <div className="h-[320px] animate-pulse rounded-lg bg-tm-inner" aria-busy="true" aria-label="차트 불러오는 중" />
          ) : lineSeries.length === 0 ? (
            <p className="m-0 py-16 text-center text-13 text-tm-muted">비교할 시세 데이터가 없습니다. 종목을 추가해 보세요.</p>
          ) : (
            <>
              <LineChart series={lineSeries} width={900} height={320} xLabels={xLabels} baseline={100} label={`${stocks.map(nameOf).join(", ")} ${periodLabel} 정규화 수익률 비교`} />
              <Legend items={stocks.map((s) => ({ label: nameOf(s), color: s.color }))} />
            </>
          )
        )}

        {chartTab === "events" && (
          <EventOverlap
            stocks={stocks.map((s, i) => ({ name: nameOf(s), color: s.color, events: eventQs[i]?.data ?? [] }))}
            fromIso={fromIso}
          />
        )}

        {chartTab === "candles" && (
          <div className={cn("grid gap-3", symbols.length > 1 && "md:grid-cols-2")}>
            {stocks.map((s, i) => (
              <div key={s.symbol} className="flex min-w-0 flex-col gap-1">
                <span className="text-13 font-semibold" style={{ color: s.color }}>{nameOf(s)}</span>
                {candleQs[i]?.isLoading ? <div className="h-[220px] animate-pulse rounded-lg bg-tm-inner" /> : <StockChart candles={candleQs[i]?.data ?? []} height={220} />}
              </div>
            ))}
          </div>
        )}
      </Panel>

      <PanelRow>
        <Panel tabs={["지표 비교"]} actions={["download"]} className="flex-[999_1_600px]" bodyClassName="px-1.5 pb-1.5 pt-1">
          <DataTable columns={columns} rows={rows} rowKey={(r) => r.key} minWidth={640} />
          <span className="px-2.5 pb-1 text-2xs text-tm-muted">기간 내 일봉 기준 계산. 베타는 KOSPI와 공통 거래일 일간 수익률로 계산(20일 미만이면 —){kospiMocked ? ", 지금 지수는 개발용 모의 값이라 참고용이 아닙니다" : ""}. 배당수익률은 배당 데이터가 없어 표시하지 않습니다.</span>
        </Panel>
        <Panel tabs={["상관관계"]} actions={[]} className="flex-[1_1_320px]">
          {stocks.length < 2 ? (
            <p className="m-0 text-13 text-tm-muted">두 종목 이상 선택하면 상관계수를 계산합니다.</p>
          ) : (
            <div className="grid gap-1" style={{ gridTemplateColumns: `70px repeat(${stocks.length},minmax(0,1fr))` }}>
              <span />
              {stocks.map((s) => <span key={s.symbol} className="truncate text-center text-2xs text-tm-muted">{nameOf(s).slice(0, 4)}</span>)}
              {corrMatrix.map((row, a) => (
                <Fragment key={a}>
                  <span className="self-center truncate text-2xs text-tm-muted">{nameOf(stocks[a]).slice(0, 4)}</span>
                  {row.map((v, b) => (
                    <span
                      key={b}
                      className="num grid h-10 place-items-center rounded-md text-xs text-dracula-fg"
                      style={{ background: v == null ? "#21222c" : `rgba(189,147,249,${(Math.max(0, v) * 0.7).toFixed(2)})` }}
                      title={`${nameOf(stocks[a])} × ${nameOf(stocks[b])}`}
                    >
                      {v == null ? "—" : v.toFixed(2)}
                    </span>
                  ))}
                </Fragment>
              ))}
            </div>
          )}
          <span className="text-2xs text-tm-muted">일간 수익률 상관계수 · {periodLabel}</span>
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}


/** 이벤트 겹침 — 종목별 이벤트 발생 시점을 같은 시간축에 점으로 */
function EventOverlap({ stocks, fromIso }: { stocks: { name: string; color: string; events: Ev[] }[]; fromIso: string }) {
  const start = new Date(fromIso).getTime();
  const end = Date.now();
  const x = (iso: string) => Math.max(0, Math.min(100, ((new Date(iso).getTime() - start) / (end - start)) * 100));
  const anyEvents = stocks.some((s) => s.events.length > 0);
  return (
    <div className="flex flex-col gap-3">
      {stocks.map((s) => (
        <div key={s.name} className="flex items-center gap-3">
          <span className="w-24 flex-none truncate text-xs font-semibold" style={{ color: s.color }}>{s.name}</span>
          <div className="relative h-6 flex-1 rounded-md bg-tm-inner">
            {s.events.map((e) => (
              <span
                key={e.id}
                title={`${new Date(e.eventTime).toLocaleDateString("ko-KR")} · ${e.title}`}
                className="absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full"
                style={{ left: `${x(e.eventTime)}%`, background: s.color }}
              />
            ))}
          </div>
          <span className="num w-10 text-right text-2xs text-tm-muted">{s.events.length >= 100 ? "100+" : s.events.length}</span>
        </div>
      ))}
      <span className="text-2xs text-tm-muted">
        {anyEvents ? "점은 각 종목의 이벤트 발생 시점입니다. 같은 세로 위치에 겹치면 동시에 일어난 이벤트입니다." : "기간 내 이벤트가 없습니다."}
      </span>
    </div>
  );
}
