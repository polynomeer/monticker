"use client";

import { useEffect, useState } from "react";
import { useQuery, useMutation } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import {
  AutoGrid, Bar, Btn, Chip, Field, IconBtn, Notice, Panel, PanelRow, Pill, PreviewTag, Stat, TerminalPage,
} from "@/components/terminal";
import { FrontierChart } from "@/components/analytics/FrontierChart";

// ── Types ──────────────────────────────────────────────────────────────────

interface FrontierPoint {
  targetReturn: number; expectedReturn: number; expectedRisk: number;
  weights: Record<string, number>;
}
interface OptimizationResult {
  stockIds: number[]; weights: Record<string, number>;
  expectedReturn: number; expectedRisk: number;
  currentEqualWeightRisk: number; currentEqualWeightReturn: number;
  suggestion: string;
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

const STOCKS = [
  { id: 2,  label: "삼성전자" }, { id: 3,  label: "SK하이닉스" },
  { id: 9,  label: "현대차" },   { id: 10, label: "NAVER" },
  { id: 5,  label: "AAPL" },    { id: 6,  label: "NVDA" },
];

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
/** 무위험 수익률 0 가정의 단순 샤프(연 수익/연 변동성) */
function sharpe(ret: number, risk: number) { return risk > 0 ? ret / risk : null; }

function StockTabs({ value, onChange }: { value: number; onChange: (id: number) => void }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {STOCKS.map(s => (
        <Chip key={s.id} active={value === s.id} onClick={() => onChange(s.id)}>{s.label}</Chip>
      ))}
    </div>
  );
}

// ── 1. Portfolio Optimizer — 효율적 프론티어 + 추천 비중 ─────────────────────

function usePortfolioOptimizer(selected: number[]) {
  const opt = useQuery<OptimizationResult>({
    queryKey: ["analytics", "optimize", selected],
    queryFn: async () => {
      const params = new URLSearchParams();
      selected.forEach(id => params.append("stockIds", String(id)));
      const res = await authFetch(`/api/analytics/portfolio/optimize?${params}`);
      // V-L1 — 백엔드가 종목 수·데이터 부족 같은 입력 오류를 이제 200+error 필드가 아니라
      // 400으로 던진다(docs/validation-hardening-plan.md).
      if (!res.ok) { const e = await res.json(); throw new Error(e.message ?? "최적화 계산에 실패했습니다."); }
      return res.json();
    },
    enabled: false,
  });

  const frontier = useQuery<FrontierPoint[]>({
    queryKey: ["analytics", "frontier", selected],
    queryFn: async () => {
      const params = new URLSearchParams();
      selected.forEach(id => params.append("stockIds", String(id)));
      const res = await authFetch(`/api/analytics/portfolio/frontier?${params}`);
      if (!res.ok) return [];
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
  const [stockId, setStockId] = useState(2);
  const { data, isLoading } = useQuery<PatternMatch[]>({
    queryKey: ["analytics", "patterns", stockId],
    queryFn: async () => {
      const res = await authFetch(`/api/stocks/${stockId}/patterns`);
      return res.json();
    },
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
  const [stockId, setStockId] = useState(2);
  const { data, isLoading } = useQuery<RegimeResult>({
    queryKey: ["analytics", "regime", stockId],
    queryFn: async () => {
      const res = await authFetch(`/api/stocks/${stockId}/regime`);
      return res.json();
    },
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

export default function AnalyticsPage() {
  const [selected, setSelected] = useState<number[]>([2, 3, 5, 6]);
  const { opt, frontier } = usePortfolioOptimizer(selected);
  const data = opt.data;
  const unselected = STOCKS.filter(s => !selected.includes(s.id));

  const run = () => { opt.refetch(); frontier.refetch(); };
  const optSharpe = data ? sharpe(data.expectedReturn, data.expectedRisk) : null;
  const eqSharpe = data ? sharpe(data.currentEqualWeightReturn, data.currentEqualWeightRisk) : null;

  return (
    <TerminalPage
      title="포트폴리오 분석"
      crumb="퀀트랩 · 최적화 도구"
      stats={[
        { label: "선택 종목", value: `${selected.length}개` },
        { label: "분석 기간", value: "보유 일봉 전체", tone: "text-tm-soft" },
        { label: "동일가중 샤프", value: eqSharpe == null ? "—" : eqSharpe.toFixed(2), tone: eqSharpe == null ? "text-tm-muted" : "text-dracula-orange" },
        { label: "최적 샤프", value: optSharpe == null ? "—" : optSharpe.toFixed(2), tone: optSharpe == null ? "text-tm-muted" : "text-dracula-green" },
      ]}
    >
      <PanelRow>
        <Panel tabs={["효율적 프론티어"]} actions={[]} closable={false} className="flex-[999_1_560px]">
          <div className="flex flex-wrap items-center gap-1.5">
            {selected.map(id => {
              const s = STOCKS.find(x => x.id === id);
              return (
                <span key={id} className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-tm-line2 bg-tm-inner pl-3 pr-1.5 text-13">
                  {s?.label ?? id}
                  <IconBtn name="x" label={`${s?.label ?? id} 제거`} size={22} iconSize={11} onClick={() => setSelected(p => p.filter(x => x !== id))} />
                </span>
              );
            })}
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
            <Btn size="sm" className="ml-auto h-8" onClick={run} disabled={selected.length < 2 || opt.isFetching}>
              {opt.isFetching ? "계산 중..." : "최적 비중 계산"}
            </Btn>
          </div>
          {selected.length < 2 && <span className="text-xs text-[#ff8a8a]">2개 이상 종목을 선택하세요</span>}
          {opt.error && <Notice tone="danger">{(opt.error as Error).message}</Notice>}
          <FrontierChart
            points={(frontier.data ?? []).map(f => ({ risk: f.expectedRisk * 100, ret: f.expectedReturn * 100 }))}
            optimal={data ? { risk: data.expectedRisk * 100, ret: data.expectedReturn * 100 } : undefined}
            current={data ? { risk: data.currentEqualWeightRisk * 100, ret: data.currentEqualWeightReturn * 100 } : undefined}
          />
        </Panel>

        <Panel tabs={["추천 비중"]} actions={[]} closable={false} className="flex-[1_1_360px]">
          <AutoGrid min={100}>
            <Stat big label="기대 수익률 (연)" value={data ? pct(data.expectedReturn) : "—"} valueClassName={!data ? "text-tm-muted" : data.expectedReturn >= 0 ? "text-up" : "text-down"} />
            <Stat big label="예상 위험" value={data ? pct(data.expectedRisk) : "—"} valueClassName={data ? undefined : "text-tm-muted"} />
            <Stat big label="샤프" value={optSharpe == null ? "—" : optSharpe.toFixed(2)} valueClassName={optSharpe == null ? "text-tm-muted" : undefined} />
          </AutoGrid>
          {data ? (
            <div className="flex flex-col gap-2.5">
              {(Object.entries(data.weights) as [string, number][]).map(([stockId, w]) => {
                const stock = STOCKS.find(s => s.id === Number(stockId));
                const eq = 100 / Object.keys(data.weights).length;
                return (
                  <div key={stockId} className="grid items-center gap-2.5 text-xs" style={{ gridTemplateColumns: "84px 1fr 52px 76px" }}>
                    <span className="truncate">{stock?.label ?? stockId}</span>
                    <Bar pct={w * 100} h={8} />
                    <span className="num text-right">{(w * 100).toFixed(0)}%</span>
                    <span className="num text-right text-tm-muted">동일 {eq.toFixed(0)}%</span>
                  </div>
                );
              })}
            </div>
          ) : (
            <p className="m-0 py-4 text-center text-13 text-tm-muted">최적 비중을 계산하면 종목별 추천 비중이 표시됩니다.</p>
          )}
          {data?.suggestion && <Notice tone="info">{data.suggestion}</Notice>}
          <div className="flex items-center gap-2">
            <Btn full disabled title="리밸런싱 화면 연동은 준비 중입니다">리밸런싱으로 보내기</Btn>
            <PreviewTag />
          </div>
          <span className="text-2xs text-tm-muted">보유 일봉 수익률 기반 평균-분산 최적화(목표 수익 대비 최소 분산). 샤프는 무위험 수익률 0 가정. 추정치이며 보장되지 않습니다.</span>
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
