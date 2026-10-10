"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useQuery, useMutation } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import {
  AutoGrid, Bar, Btn, Chip, Field, IconBtn, Notice, Panel, PanelRow, Pill, Stat, TerminalPage,
} from "@/components/terminal";
import { FrontierChart } from "@/components/analytics/FrontierChart";
import {
  PERIOD_PRESETS, appendPeriod, customPeriodError, minusDays, periodLabel, type AnalysisPeriodSel,
} from "@/components/analytics/analysisPeriod";
import { kstToday } from "@/components/wallet/insights";
import { usePaperPortfolio } from "@/hooks/usePaperTrade";
import { getScreenerQuotes } from "@/services/screener";
import { saveRebalanceDraft, toDraftWeights } from "@/lib/rebalanceDraft";
import { useFeaturedStocks } from "@/hooks/useFeaturedStocks";

// ── Types ──────────────────────────────────────────────────────────────────

interface FrontierPoint {
  targetReturn: number; expectedReturn: number; expectedRisk: number;
  weights: Record<string, number>;
}
/** 실제로 계산에 쓴 기간 — firstDate~lastDate는 모든 종목이 함께 거래된 첫·마지막 날 */
interface PeriodInfo {
  period: string; from: string; to: string; firstDate: string; lastDate: string; observations: number;
}
interface SamplePoint { expectedReturn: number; expectedRisk: number; sharpe: number | null }
interface MaxSharpePoint { weights: Record<string, number>; expectedReturn: number; expectedRisk: number; sharpe: number }
/** GET /api/analytics/portfolio/frontier (ADR-097) */
interface FrontierResponse {
  stockIds: number[]; period: PeriodInfo; frontier: FrontierPoint[]; samples: SamplePoint[];
  maxSharpe: MaxSharpePoint | null; riskFreeRate: number; seed: number; method: string;
}
/** 모의투자 보유를 평가금액 비중으로 바꾼 비교점(분석 종목 안에서 합 1). */
interface CurrentPortfolioPoint {
  weights: Record<string, number>;
  expectedReturn: number; expectedRisk: number;
  /** 보유 주식 평가금액 중 분석 종목이 차지하는 비율 */
  coveredValueRatio: number;
  notHeld: number[];
}
interface OptimizationResult {
  stockIds: number[]; weights: Record<string, number>;
  expectedReturn: number; expectedRisk: number;
  currentEqualWeightRisk: number; currentEqualWeightReturn: number;
  suggestion: string;
  current: CurrentPortfolioPoint | null;
  period: PeriodInfo | null;
}
interface HarvestingCandidate {
  stockId: number; symbol: string; name: string; quantity: number;
  avgPrice: number; currentPrice: number; unrealizedLoss: number; estimatedTaxSaving: number;
}
interface TaxHarvestingResponse {
  realizedGainYtd: number; candidates: HarvestingCandidate[];
  totalEstimatedTaxSaving: number; taxRateAssumed: number; disclaimer: string;
}
interface KellyResult {
  winRate: number; avgWin: number; avgLoss: number;
  fullKelly: number; halfKelly: number; recommendation: string;
}
interface SwingPoint { index: number; date: string; price: number; type: string; }
interface PatternMatch {
  patternType: string; confidenceScore: number; swingPoints: SwingPoint[];
  candleFrom: string; candleTo: string; description: string;
}
interface RegimeResult {
  regime: string; adx: number; volatility: number; trendSlope: number;
  explanation: string; error: string | null;
}


const PATTERN_LABEL: Record<string, string> = {
  HEAD_AND_SHOULDERS: "헤드앤숄더", DOUBLE_BOTTOM: "이중 바닥", DOUBLE_TOP: "이중 천장",
  ASCENDING_TRIANGLE: "상승 삼각수렴", DESCENDING_TRIANGLE: "하락 삼각수렴",
};
const REGIME_META: Record<string, { label: string; tone: "green" | "red" | "muted" | "orange" }> = {
  BULL: { label: "상승장", tone: "green" },
  BEAR: { label: "하락장", tone: "red" },
  SIDEWAYS: { label: "횡보장", tone: "muted" },
  HIGH_VOL: { label: "고변동성", tone: "orange" },
};

function won(n: number) { return Math.round(n).toLocaleString("ko-KR"); }
function pct(n: number) { return (n * 100).toFixed(2) + "%"; }
/** 샤프 = (연 수익 − 연 무위험 수익률) / 연 변동성. 무위험 수익률은 서버 설정값(응답의 riskFreeRate) */
function sharpe(ret: number, risk: number, rf = 0) { return risk > 0 ? (ret - rf) / risk : null; }

function StockTabs({ value, onChange }: { value: number | null; onChange: (id: number) => void }) {
  const { stocks } = useFeaturedStocks();
  return (
    <div className="flex flex-wrap gap-1.5">
      {stocks.map(s => (
        <Chip key={s.id} active={value === s.id} onClick={() => onChange(s.id)}>{s.label}</Chip>
      ))}
    </div>
  );
}

// ── 1. Portfolio Optimizer — 효율적 프론티어 + 분석 결과 비중 ─────────────────────

function usePortfolioOptimizer(selected: number[], period: AnalysisPeriodSel) {
  const opt = useQuery<OptimizationResult>({
    queryKey: ["analytics", "optimize", selected, period],
    queryFn: async () => {
      const params = new URLSearchParams();
      selected.forEach(id => params.append("stockIds", String(id)));
      appendPeriod(params, period);
      // 동일가중과 함께 사용자의 현재(모의투자) 보유 비중도 같은 축에서 비교한다.
      params.set("compareHoldings", "true");
      const res = await authFetch(`/api/analytics/portfolio/optimize?${params}`);
      // V-L1 — 백엔드가 종목 수·데이터 부족 같은 입력 오류를 이제 200+error 필드가 아니라
      // 400으로 던진다(docs/validation-hardening-plan.md).
      if (!res.ok) { const e = await res.json(); throw new Error(e.message ?? "최적화 계산에 실패했습니다."); }
      return res.json();
    },
    enabled: false,
  });

  const frontier = useQuery<FrontierResponse>({
    queryKey: ["analytics", "frontier", selected, period],
    queryFn: async () => {
      const params = new URLSearchParams();
      selected.forEach(id => params.append("stockIds", String(id)));
      appendPeriod(params, period);
      const res = await authFetch(`/api/analytics/portfolio/frontier?${params}`);
      // 겹치는 거래일 부족·기간 오류는 400 + message — 빈 차트 대신 이유를 보여준다.
      if (!res.ok) { const e = await res.json().catch(() => ({})); throw new Error(e.message ?? "위험-수익 분포를 계산하지 못했습니다."); }
      return res.json();
    },
    enabled: false,
  });
  return { opt, frontier };
}

// ── 2. Tax ────────────────────────────────────────────────────────────────────

function TaxPanel() {
  const { data, isLoading } = useQuery<TaxHarvestingResponse>({
    queryKey: ["analytics", "tax"],
    queryFn: async () => {
      const res = await authFetch("/api/analytics/tax/harvesting-candidates");
      return res.json();
    },
  });
  const top = data?.candidates?.length ? [...data.candidates].sort((a, b) => a.unrealizedLoss - b.unrealizedLoss)[0] : undefined;

  return (
    <Panel tabs={["절세 시뮬레이션"]} actions={[]} closable={false} className="flex-[1_1_420px]">
      {isLoading ? (
        <p className="m-0 py-8 text-center text-13 text-tm-muted">로딩 중...</p>
      ) : !data ? (
        <p className="m-0 py-8 text-center text-13 text-tm-muted">절세 데이터를 불러오지 못했습니다.</p>
      ) : (
        <>
          <AutoGrid min={160}>
            <Stat big label="올해 실현 이익" value={`${data.realizedGainYtd > 0 ? "+" : ""}${won(data.realizedGainYtd)}`} valueClassName={data.realizedGainYtd >= 0 ? "text-up" : "text-down"} />
            <Stat big label="손실 실현 후보" value={top ? `${top.name} ${won(top.unrealizedLoss)}` : "없음"} valueClassName={top ? "text-down" : "text-tm-muted"} />
            <Stat big label="예상 절세 효과" value={`${won(data.totalEstimatedTaxSaving)}원`} valueClassName="text-dracula-purple" sub={`세율 ${pct(data.taxRateAssumed)} 가정`} />
          </AutoGrid>
          {data.candidates.length === 0 ? (
            <p className="m-0 text-13 text-tm-muted">현재 손실 종목이 없습니다.</p>
          ) : (
            <ul className="m-0 flex list-none flex-col p-0">
              {data.candidates.map((c: HarvestingCandidate) => (
                <li key={c.stockId} className="flex flex-wrap items-center justify-between gap-2 border-b border-tm-line py-2 text-13">
                  <span className="font-semibold">{c.name}</span>
                  <span className="num text-xs text-tm-muted">{c.quantity}주 · 평단 {won(c.avgPrice)} → {won(c.currentPrice)}</span>
                  <span className="num text-down">{won(c.unrealizedLoss)}원</span>
                  <span className="num text-xs text-dracula-purple">절세 {won(c.estimatedTaxSaving)}원</span>
                </li>
              ))}
            </ul>
          )}
          <Notice tone="warn" icon="alert">{data.disclaimer || "해외주식 양도세 등 세금 계산은 참고용이며 세무 자문이 아닙니다."}</Notice>
        </>
      )}
    </Panel>
  );
}

// ── 3. Kelly ─────────────────────────────────────────────────────────────────

function KellyPanel() {
  const [winRate, setWinRate] = useState(55);
  const [avgWin, setAvgWin] = useState(8);
  const [avgLoss, setAvgLoss] = useState(4);

  const mutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch("/api/analytics/position-size/kelly", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ winRate: winRate / 100, avgWinPct: avgWin, avgLossPct: avgLoss }),
      });
      return res.json() as Promise<KellyResult>;
    },
  });
  const { mutate } = mutation;

  // 시안에는 계산 버튼이 없다 — 값을 바꾸면 잠시 뒤 자동으로 다시 계산한다.
  useEffect(() => {
    if (!(winRate > 0 && winRate < 100 && avgWin > 0 && avgLoss > 0)) return;
    const t = setTimeout(() => mutate(), 400);
    return () => clearTimeout(t);
  }, [winRate, avgWin, avgLoss, mutate]);

  const r = mutation.data;
  return (
    <Panel tabs={["켈리 비중 계산"]} actions={[]} closable={false} className="flex-[1_1_420px]">
      <div className="flex flex-wrap gap-2">
        <Field label="승률" unit="%" type="number" value={winRate} onChange={e => setWinRate(+e.target.value)} />
        <Field label="평균 이익" unit="%" type="number" value={avgWin} onChange={e => setAvgWin(+e.target.value)} />
        <Field label="평균 손실" unit="%" type="number" value={avgLoss} onChange={e => setAvgLoss(+e.target.value)} />
      </div>
      <div className="flex flex-wrap items-end gap-4">
        <Stat big label="풀 켈리" value={r ? pct(r.fullKelly) : "—"} valueClassName={r ? undefined : "text-tm-muted"} />
        <Stat big label="하프 켈리 (권장)" value={r ? pct(r.halfKelly) : "—"} valueClassName={r ? "text-dracula-purple" : "text-tm-muted"} />
        <span className="min-w-[180px] flex-1 text-xs text-tm-muted">
          {mutation.isPending ? "계산 중..." : r?.recommendation ?? "승률·평균 이익·평균 손실을 입력하면 계산합니다."}
        </span>
      </div>
    </Panel>
  );
}

// ── 4. Pattern / 5. Regime — 시안에 없는 기존 도구, 같은 화면 아래에 둔다 ──────

function PatternPanel() {
  const { defaultId } = useFeaturedStocks();
  const [picked, setStockId] = useState<number | null>(null);
  const stockId = picked ?? defaultId;
  const { data, isLoading } = useQuery<PatternMatch[]>({
    queryKey: ["analytics", "patterns", stockId],
    queryFn: async () => {
      const res = await authFetch(`/api/stocks/${stockId}/patterns`);
      return res.json();
    },
    enabled: stockId != null,
  });

  return (
    <Panel tabs={["차트 패턴"]} actions={[]} closable={false} className="flex-[1_1_420px]">
      <StockTabs value={stockId} onChange={setStockId} />
      {isLoading ? (
        <p className="m-0 py-6 text-center text-13 text-tm-muted">패턴 분석 중...</p>
      ) : !data || data.length === 0 ? (
        <p className="m-0 py-6 text-center text-13 text-tm-muted">감지된 패턴이 없습니다.</p>
      ) : (
        <ul className="m-0 flex list-none flex-col p-0">
          {data.map((p: PatternMatch, i: number) => (
            <li key={i} className="flex flex-col gap-1 border-b border-tm-line py-2.5">
              <div className="flex items-center justify-between gap-2">
                <span className="font-semibold">{PATTERN_LABEL[p.patternType] ?? p.patternType}</span>
                <Pill tone="purple">완성도 {p.confidenceScore}%</Pill>
              </div>
              <span className="num text-2xs text-tm-muted">{p.candleFrom} ~ {p.candleTo}</span>
              <span className="text-xs text-tm-soft">{p.description}</span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function RegimePanel() {
  const { defaultId } = useFeaturedStocks();
  const [picked, setStockId] = useState<number | null>(null);
  const stockId = picked ?? defaultId;
  const { data, isLoading } = useQuery<RegimeResult>({
    queryKey: ["analytics", "regime", stockId],
    queryFn: async () => {
      const res = await authFetch(`/api/stocks/${stockId}/regime`);
      return res.json();
    },
    enabled: stockId != null,
  });
  const meta = data ? REGIME_META[data.regime] : undefined;

  return (
    <Panel tabs={["시장 국면"]} actions={[]} closable={false} className="flex-[1_1_420px]">
      <StockTabs value={stockId} onChange={setStockId} />
      {isLoading ? (
        <p className="m-0 py-6 text-center text-13 text-tm-muted">분석 중...</p>
      ) : data?.error ? (
        <Notice tone="danger">{data.error}</Notice>
      ) : data ? (
        <>
          <Pill tone={meta?.tone ?? "muted"} className="h-8 self-start px-3 text-sm">{meta?.label ?? data.regime}</Pill>
          <AutoGrid min={120}>
            <Stat label="ADX (추세강도)" value={data.adx.toFixed(1)} />
            <Stat label="변동성 (연환산)" value={pct(data.volatility)} />
            <Stat label="추세 기울기" value={`${data.trendSlope >= 0 ? "+" : ""}${(data.trendSlope * 100).toFixed(3)}%`} valueClassName={data.trendSlope >= 0 ? "text-up" : "text-down"} />
          </AutoGrid>
          <p className="m-0 rounded-lg bg-tm-inner p-3 text-xs leading-relaxed text-tm-soft">{data.explanation}</p>
        </>
      ) : null}
    </Panel>
  );
}

// ── Page ────────────────────────────────────────────────────────────────────

const MAX_STOCKS = 20;
/** 처음 열었을 때 분석 대상 — 국내 2 + 미국 2 */
const DEFAULT_ANALYSIS_SYMBOLS = ["005930", "000660", "AAPL", "NVDA"];

export default function AnalyticsPage() {
  // 처음 분석 대상은 종목 코드로 정한다(useFeaturedStocks) — 사용자가 바꾸기 전까지는 찾은 id를 그대로 쓴다
  const { stocks: featured } = useFeaturedStocks();
  const [picked, setPicked] = useState<number[] | null>(null);
  const selected = picked ?? featured.filter(s => DEFAULT_ANALYSIS_SYMBOLS.includes(s.symbol)).map(s => s.id);
  const setSelected = (next: number[] | ((prev: number[]) => number[])) =>
    setPicked(typeof next === "function" ? next(selected) : next);
  // 보유 종목에서 불러온 종목은 빠른 선택 목록(featured)에 없을 수 있다 — 이름을 따로 기억한다.
  const [names, setNames] = useState<Record<number, string>>({});
  const [period, setPeriod] = useState<AnalysisPeriodSel>({ kind: "1Y" });
  const today = kstToday();
  const [customFrom, setCustomFrom] = useState(() => minusDays(today, 365));
  const [customTo, setCustomTo] = useState(today);
  const [customOpen, setCustomOpen] = useState(false);
  const customError = customOpen ? customPeriodError(customFrom, customTo, today) : null;
  const { opt, frontier } = usePortfolioOptimizer(selected, period);
  const fr = frontier.data;
  const rf = fr?.riskFreeRate ?? 0;
  const { data: paper } = usePaperPortfolio();
  const holdings = (paper?.holdings ?? []).filter(h => h.value > 0);
  const data = opt.data;
  const held = data?.current ?? null;
  const unselected = featured.filter(s => !selected.includes(s.id));
  const labelOf = (id: number) => featured.find(x => x.id === id)?.label ?? names[id] ?? `#${id}`;

  const loadHoldings = () => {
    const top = [...holdings].sort((a, b) => b.value - a.value).slice(0, MAX_STOCKS);
    setNames(n => ({ ...n, ...Object.fromEntries(top.map(h => [h.stockId, h.name])) }));
    setSelected(top.map(h => h.stockId));
  };

  const run = () => { opt.refetch(); frontier.refetch(); };

  // 분석 결과 비중을 리밸런싱 화면에 "편집 중 초안"으로 넘긴다. 서버에는 아무것도 저장하지 않는다 —
  // 목표 저장·미리보기·실행 확인은 리밸런싱 화면에서 사용자가 직접 한다.
  const router = useRouter();
  const [sendError, setSendError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const sendToRebalance = async () => {
    if (!data) return;
    setSendError(null);
    setSending(true);
    try {
      const weights = toDraftWeights(Object.entries(data.weights).map(([id, w]) => ({ stockId: Number(id), weight: w })));
      const quotes = await getScreenerQuotes(weights.map(w => w.stockId));
      const bySymbol = new Map(quotes.map(q => [q.stockId, q]));
      const missing = weights.filter(w => !bySymbol.has(w.stockId));
      if (missing.length > 0) throw new Error(`종목 코드를 찾지 못했습니다: ${missing.map(w => labelOf(w.stockId)).join(", ")}`);
      const ok = saveRebalanceDraft({
        createdAt: Date.now(),
        rows: weights.map(w => ({ stockId: w.stockId, symbol: bySymbol.get(w.stockId)!.symbol, name: bySymbol.get(w.stockId)!.name, weightPct: w.weightPct })),
        expectedReturn: data.expectedReturn,
        expectedRisk: data.expectedRisk,
        suggestion: data.suggestion,
      });
      if (!ok) throw new Error("초안을 넘기지 못했습니다. 브라우저 저장소 설정을 확인하세요.");
      router.push("/brokerage/rebalance");
    } catch (e) {
      setSendError((e as Error).message);
    } finally {
      setSending(false);
    }
  };
  const optSharpe = data ? sharpe(data.expectedReturn, data.expectedRisk, rf) : null;
  const eqSharpe = data ? sharpe(data.currentEqualWeightReturn, data.currentEqualWeightRisk, rf) : null;
  const heldSharpe = held ? sharpe(held.expectedReturn, held.expectedRisk, rf) : null;
  const best = fr?.maxSharpe ?? null;
  const usedPeriod = fr?.period ?? data?.period ?? null;

  return (
    <TerminalPage
      title="포트폴리오 분석"
      crumb="퀀트랩 · 최적화 도구"
      stats={[
        { label: "선택 종목", value: `${selected.length}개` },
        { label: "분석 기간", value: usedPeriod ? `${usedPeriod.firstDate} ~ ${usedPeriod.lastDate} · ${usedPeriod.observations}일` : periodLabel(period), tone: "text-tm-soft" },
        held
          ? { label: "현재 보유 샤프", value: heldSharpe == null ? "—" : heldSharpe.toFixed(2), tone: heldSharpe == null ? "text-tm-muted" : "text-dracula-cyan" }
          : { label: "동일가중 샤프", value: eqSharpe == null ? "—" : eqSharpe.toFixed(2), tone: eqSharpe == null ? "text-tm-muted" : "text-dracula-orange" },
        { label: "샤프 최대 (과거 기준)", value: best == null ? "—" : best.sharpe.toFixed(2), tone: best == null ? "text-tm-muted" : "text-dracula-green" },
      ]}
    >
      <PanelRow>
        <Panel tabs={["효율적 프론티어"]} actions={[]} closable={false} className="flex-[999_1_560px]">
          <div className="flex flex-wrap items-center gap-1.5">
            {selected.map(id => (
              <span key={id} className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-tm-line2 bg-tm-inner pl-3 pr-1.5 text-13">
                {labelOf(id)}
                <IconBtn name="x" label={`${labelOf(id)} 제거`} size={22} iconSize={11} onClick={() => setSelected(p => p.filter(x => x !== id))} />
              </span>
            ))}
            {unselected.length > 0 && (
              <label className="relative inline-flex h-8 items-center gap-1.5 rounded-lg border border-tm-line2 px-3 text-13 font-semibold text-dracula-fg hover:bg-tm-raised">
                + 종목 추가
                <select
                  aria-label="종목 추가"
                  value=""
                  onChange={e => { const v = Number(e.target.value); if (v) setSelected(p => [...p, v]); }}
                  className="absolute inset-0 cursor-pointer opacity-0"
                >
                  <option value="">종목 선택</option>
                  {unselected.map(s => <option key={s.id} value={s.id}>{s.label}</option>)}
                </select>
              </label>
            )}
            <Btn
              size="sm"
              kind="ghost"
              className="ml-auto h-8"
              onClick={loadHoldings}
              disabled={holdings.length === 0}
              title={holdings.length === 0 ? "모의투자 보유 종목이 없습니다" : `보유 ${holdings.length}종목을 분석 대상으로 (평가금액 상위 ${MAX_STOCKS}개까지)`}
            >
              보유 종목으로
            </Btn>
            <Btn size="sm" className="h-8" onClick={run} disabled={selected.length < 2 || opt.isFetching || frontier.isFetching}>
              {opt.isFetching || frontier.isFetching ? "계산 중..." : "분석 실행"}
            </Btn>
          </div>
          <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="분석 기간">
            <span className="mr-1 text-xs text-tm-muted">분석 기간</span>
            {PERIOD_PRESETS.map(p => (
              <Chip key={p.key} active={!customOpen && period.kind === p.key} onClick={() => { setCustomOpen(false); setPeriod({ kind: p.key }); }}>{p.label}</Chip>
            ))}
            <Chip active={customOpen} onClick={() => setCustomOpen(true)}>직접 지정</Chip>
            {customOpen && (
              <>
                <input type="date" aria-label="분석 시작일" value={customFrom} max={today} onChange={e => setCustomFrom(e.target.value)}
                  className="num h-8 rounded-lg border border-tm-line2 bg-tm-inner px-2 text-13 text-dracula-fg" />
                <span className="text-tm-muted">~</span>
                <input type="date" aria-label="분석 종료일" value={customTo} max={today} onChange={e => setCustomTo(e.target.value)}
                  className="num h-8 rounded-lg border border-tm-line2 bg-tm-inner px-2 text-13 text-dracula-fg" />
                <Btn size="sm" kind="ghost" className="h-8" disabled={customError != null}
                  onClick={() => setPeriod({ kind: "CUSTOM", from: customFrom, to: customTo })}>적용</Btn>
              </>
            )}
            <span className="text-2xs text-tm-muted">
              {period.kind === "CUSTOM" ? `적용: ${periodLabel(period)}` : "한국 날짜 기준, 오늘까지"} · 직접 지정은 60일~3년
            </span>
          </div>
          {customError && <span className="text-xs text-[#ff8a8a]">{customError}</span>}
          {selected.length < 2 && <span className="text-xs text-[#ff8a8a]">2개 이상 종목을 선택하세요</span>}
          {opt.error && <Notice tone="danger">{(opt.error as Error).message}</Notice>}
          {frontier.error && !opt.error && <Notice tone="danger">{(frontier.error as Error).message}</Notice>}
          <FrontierChart
            data={{
              samples: (fr?.samples ?? []).map(s => ({ risk: s.expectedRisk * 100, ret: s.expectedReturn * 100, sharpe: s.sharpe })),
              frontier: (fr?.frontier ?? []).map(f => ({ risk: f.expectedRisk * 100, ret: f.expectedReturn * 100 })),
              maxSharpe: best ? { risk: best.expectedRisk * 100, ret: best.expectedReturn * 100, sharpe: best.sharpe } : undefined,
              optimal: data ? { risk: data.expectedRisk * 100, ret: data.expectedReturn * 100 } : undefined,
              equalWeight: data ? { risk: data.currentEqualWeightRisk * 100, ret: data.currentEqualWeightReturn * 100 } : undefined,
              held: held ? { risk: held.expectedRisk * 100, ret: held.expectedReturn * 100 } : undefined,
            }}
          />
          {best && (
            <div className="flex flex-col gap-1.5 rounded-lg bg-tm-inner p-3 text-xs">
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <span className="font-semibold text-dracula-green">샤프 비율 최대 지점 (과거 데이터 기준)</span>
                <span className="num text-tm-soft">샤프 {best.sharpe.toFixed(2)} · 수익 {pct(best.expectedReturn)} · 위험 {pct(best.expectedRisk)}</span>
              </div>
              <div className="num flex flex-wrap gap-x-3 gap-y-1 text-tm-muted">
                {Object.entries(best.weights).filter(([, w]) => w >= 0.005).sort((a, b) => b[1] - a[1]).map(([id, w]) => (
                  <span key={id}>{labelOf(Number(id))} {(w * 100).toFixed(0)}%</span>
                ))}
              </div>
              <span className="text-2xs text-tm-muted">
                회색 점은 무작위 롱 온리 포트폴리오 {fr?.samples.length ?? 0}개(고정 시드라 같은 입력이면 같은 결과)입니다. 과거 수익률로 계산한 분석 결과이며 투자 권유가 아닙니다.
              </span>
            </div>
          )}
          {data && !held && (
            <span className="text-2xs text-tm-muted">고른 종목 중 모의투자로 보유한 종목이 없어 현재 비중 비교는 생략했습니다.</span>
          )}
          {held && held.coveredValueRatio < 0.999 && (
            <span className="text-2xs text-tm-muted">
              현재 보유 비중은 고른 종목만 기준입니다 — 보유 주식 평가금액의 {(held.coveredValueRatio * 100).toFixed(0)}%만 포함됐습니다. 현금은 제외합니다.
            </span>
          )}
        </Panel>

        <Panel tabs={["분석 결과 비중"]} actions={[]} closable={false} className="flex-[1_1_360px]">
          <AutoGrid min={100}>
            <Stat big label="기대 수익률 (연)" value={data ? pct(data.expectedReturn) : "—"} valueClassName={!data ? "text-tm-muted" : data.expectedReturn >= 0 ? "text-up" : "text-down"} />
            <Stat big label="예상 위험" value={data ? pct(data.expectedRisk) : "—"} valueClassName={data ? undefined : "text-tm-muted"} />
            <Stat big label="샤프" value={optSharpe == null ? "—" : optSharpe.toFixed(2)} valueClassName={optSharpe == null ? "text-tm-muted" : undefined} />
          </AutoGrid>
          {data ? (
            <div className="flex flex-col gap-2.5">
              {(Object.entries(data.weights) as [string, number][]).map(([stockId, w]) => {
                const eq = 100 / Object.keys(data.weights).length;
                const cur = held ? (held.weights[stockId] ?? 0) * 100 : null;
                return (
                  <div key={stockId} className="grid items-center gap-2.5 text-xs" style={{ gridTemplateColumns: "84px 1fr 52px 76px" }}>
                    <span className="truncate">{labelOf(Number(stockId))}</span>
                    <Bar pct={w * 100} h={8} />
                    <span className="num text-right">{(w * 100).toFixed(0)}%</span>
                    <span className={`num text-right ${cur == null ? "text-tm-muted" : "text-dracula-cyan"}`}>{cur == null ? `동일 ${eq.toFixed(0)}%` : `현재 ${cur.toFixed(0)}%`}</span>
                  </div>
                );
              })}
            </div>
          ) : (
            <p className="m-0 py-4 text-center text-13 text-tm-muted">분석을 실행하면 종목별 분석 결과 비중이 표시됩니다.</p>
          )}
          {data?.suggestion && <Notice tone="info">{data.suggestion}</Notice>}
          <Btn full onClick={sendToRebalance} disabled={!data || sending} title={data ? "분석 결과 비중을 리밸런싱 화면에 초안으로 채웁니다 — 저장·실행은 그 화면에서 직접" : "먼저 분석을 실행하세요"}>
            {sending ? "넘기는 중..." : "분석 비중을 리밸런싱 초안으로"}
          </Btn>
          {sendError && <Notice tone="danger">{sendError}</Notice>}
          <span className="text-2xs text-tm-muted">실전 계좌 리밸런싱 화면에 초안으로만 채웁니다. 목표 저장과 주문 실행은 그 화면에서 직접 확인해야 하며, 자동으로 주문하지 않습니다.</span>
          <span className="text-2xs text-tm-muted">선택한 분석 기간에 모든 종목이 함께 거래된 날의 일봉 수익률 기반 평균-분산 최적화(목표 수익 대비 최소 분산). 샤프는 연 무위험 수익률 {(rf * 100).toFixed(2)}% 가정. 추정치이며 보장되지 않습니다.</span>
        </Panel>
      </PanelRow>

      <PanelRow>
        <TaxPanel />
        <KellyPanel />
      </PanelRow>

      <PanelRow>
        <PatternPanel />
        <RegimePanel />
      </PanelRow>
    </TerminalPage>
  );
}
