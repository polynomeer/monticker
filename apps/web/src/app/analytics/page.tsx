"use client";

import { useState } from "react";
import { useQuery, useMutation } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

// ── Types ──────────────────────────────────────────────────────────────────

interface FrontierPoint {
  targetReturn: number; expectedReturn: number; expectedRisk: number;
  weights: Record<string, number>;
}
interface OptimizationResult {
  stockIds: number[]; weights: Record<string, number>;
  expectedReturn: number; expectedRisk: number;
  currentEqualWeightRisk: number; currentEqualWeightReturn: number;
  suggestion: string; error: string | null;
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
const REGIME_META: Record<string, { label: string; color: string }> = {
  BULL: { label: "상승장", color: "text-[#50fa7b] bg-[#50fa7b]/10" },
  BEAR: { label: "하락장", color: "text-[#ff5555] bg-[#ff5555]/10" },
  SIDEWAYS: { label: "횡보장", color: "text-[#6272a4] bg-[#44475a]" },
  HIGH_VOL: { label: "고변동성", color: "text-[#ffb86c] bg-[#ffb86c]/10" },
};

function won(n: number) { return Math.round(n).toLocaleString("ko-KR"); }
function pct(n: number) { return (n * 100).toFixed(2) + "%"; }

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick}
      className={`px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px
        ${active ? "border-[#bd93f9] text-[#bd93f9]" : "border-transparent text-[#6272a4] hover:text-[#f8f8f2]"}`}>
      {children}
    </button>
  );
}

// ── 1. Portfolio Optimizer ───────────────────────────────────────────────────

function PortfolioOptimizerTab() {
  const [selected, setSelected] = useState<number[]>([2, 3, 5, 6]);

  const { data, refetch, isFetching } = useQuery<OptimizationResult>({
    queryKey: ["analytics", "optimize", selected],
    queryFn: async () => {
      const params = new URLSearchParams();
      selected.forEach(id => params.append("stockIds", String(id)));
      const res = await authFetch(`/api/analytics/portfolio/optimize?${params}`);
      return res.json();
    },
    enabled: false,
  });

  const { data: frontier } = useQuery<FrontierPoint[]>({
    queryKey: ["analytics", "frontier", selected],
    queryFn: async () => {
      const params = new URLSearchParams();
      selected.forEach(id => params.append("stockIds", String(id)));
      const res = await authFetch(`/api/analytics/portfolio/frontier?${params}`);
      return res.json();
    },
    enabled: false,
  });

  const toggle = (id: number) =>
    setSelected(p => p.includes(id) ? p.filter(x => x !== id) : [...p, id]);

  return (
    <div className="space-y-4">
      <div className="p-5 rounded-xl border border-[#44475a] bg-[#21222c]">
        <p className="text-sm font-semibold text-[#f8f8f2] mb-3">최적화 대상 종목 선택</p>
        <div className="flex flex-wrap gap-2 mb-4">
          {STOCKS.map(s => (
            <button key={s.id} onClick={() => toggle(s.id)}
              className={`px-3 py-1.5 rounded-full text-xs font-medium transition-colors
                ${selected.includes(s.id) ? "bg-[#bd93f9] text-[#282a36]" : "bg-[#44475a] text-[#6272a4]"}`}>
              {s.label}
            </button>
          ))}
        </div>
        <button onClick={() => { refetch(); }} disabled={selected.length < 2 || isFetching}
          className="px-5 py-2 rounded-xl bg-[#bd93f9] text-[#282a36] text-sm font-bold hover:bg-[#ff79c6] transition-colors disabled:opacity-40">
          {isFetching ? "계산 중..." : "최적 비중 계산"}
        </button>
        {selected.length < 2 && <p className="text-xs text-[#ff5555] mt-2">2개 이상 종목을 선택하세요</p>}
      </div>

      {data?.error && <p className="text-sm text-[#ff5555]">{data.error}</p>}

      {data && !data.error && (
        <div className="p-5 rounded-xl border border-[#44475a] bg-[#21222c] space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-xs text-[#6272a4]">기대 수익률 (연환산)</p>
              <p className="text-lg font-bold text-[#50fa7b]">{pct(data.expectedReturn)}</p>
            </div>
            <div>
              <p className="text-xs text-[#6272a4]">예상 위험 (변동성)</p>
              <p className="text-lg font-bold text-[#f8f8f2]">{pct(data.expectedRisk)}</p>
            </div>
          </div>

          <div>
            <p className="text-xs text-[#6272a4] mb-2">추천 비중</p>
            <div className="space-y-2">
              {(Object.entries(data.weights) as [string, number][]).map(([stockId, w]) => {
                const stock = STOCKS.find(s => s.id === Number(stockId));
                return (
                  <div key={stockId} className="flex items-center gap-3">
                    <span className="text-xs text-[#f8f8f2] w-20">{stock?.label ?? stockId}</span>
                    <div className="flex-1 h-2 rounded-full bg-[#44475a] overflow-hidden">
                      <div className="h-full bg-[#bd93f9] rounded-full" style={{ width: `${w * 100}%` }} />
                    </div>
                    <span className="text-xs text-[#6272a4] w-12 text-right">{pct(w)}</span>
                  </div>
                );
              })}
            </div>
          </div>

          {data.suggestion && (
            <p className="text-xs text-[#bd93f9] p-3 rounded-lg bg-[#bd93f9]/5 border border-[#bd93f9]/20">
              💡 {data.suggestion}
            </p>
          )}
        </div>
      )}

      {frontier && frontier.length > 0 && (
        <div className="p-5 rounded-xl border border-[#44475a] bg-[#21222c]">
          <p className="text-sm font-semibold text-[#f8f8f2] mb-3">효율적 프론티어</p>
          <div className="space-y-1.5">
            {frontier.map((f: FrontierPoint, i: number) => (
              <div key={i} className="flex justify-between text-xs">
                <span className="text-[#6272a4]">위험 {pct(f.expectedRisk)}</span>
                <span className="text-[#f8f8f2]">수익 {pct(f.expectedReturn)}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ── 2. Tax Optimizer ──────────────────────────────────────────────────────────

function TaxOptimizerTab() {
  const { data, isLoading } = useQuery<TaxHarvestingResponse>({
    queryKey: ["analytics", "tax"],
    queryFn: async () => {
      const res = await authFetch("/api/analytics/tax/harvesting-candidates");
      return res.json();
    },
  });

  if (isLoading) return <div className="text-center py-12 text-[#6272a4] text-sm">로딩 중...</div>;
  if (!data) return null;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <div className="p-4 rounded-xl border border-[#44475a] bg-[#21222c]">
          <p className="text-xs text-[#6272a4]">올해 실현 이익</p>
          <p className="text-lg font-bold text-[#50fa7b]">{won(data.realizedGainYtd)}원</p>
        </div>
        <div className="p-4 rounded-xl border border-[#44475a] bg-[#21222c]">
          <p className="text-xs text-[#6272a4]">예상 절세 효과</p>
          <p className="text-lg font-bold text-[#bd93f9]">{won(data.totalEstimatedTaxSaving)}원</p>
        </div>
      </div>

      {data.candidates.length === 0 ? (
        <div className="text-center py-12 border border-dashed border-[#44475a] rounded-xl text-[#6272a4] text-sm">
          현재 손실 종목이 없습니다.
        </div>
      ) : (
        <div className="space-y-2">
          {data.candidates.map((c: HarvestingCandidate) => (
            <div key={c.stockId} className="p-4 rounded-xl border border-[#44475a] bg-[#21222c]">
              <div className="flex justify-between items-center mb-1">
                <span className="text-sm font-semibold text-[#f8f8f2]">{c.name}</span>
                <span className="text-xs text-[#ff5555]">{won(c.unrealizedLoss)}원 평가손실</span>
              </div>
              <div className="flex justify-between text-xs text-[#6272a4]">
                <span>{c.quantity}주 · 평단 {won(c.avgPrice)}원 → 현재 {won(c.currentPrice)}원</span>
                <span className="text-[#bd93f9] font-medium">절세 {won(c.estimatedTaxSaving)}원</span>
              </div>
            </div>
          ))}
        </div>
      )}

      <p className="text-xs text-[#6272a4] text-center">{data.disclaimer}</p>
    </div>
  );
}

// ── 3. Position Sizer (Kelly) ─────────────────────────────────────────────────

function KellyTab() {
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

  return (
    <div className="space-y-4">
      <div className="p-5 rounded-xl border border-[#44475a] bg-[#21222c] space-y-3">
        <p className="text-sm font-semibold text-[#f8f8f2]">백테스트 결과 입력</p>
        <div className="grid grid-cols-3 gap-3">
          <div>
            <label className="text-xs text-[#6272a4] mb-1 block">승률 (%)</label>
            <input type="number" value={winRate} onChange={e => setWinRate(+e.target.value)}
              className="w-full rounded-lg bg-[#282a36] border border-[#44475a] text-[#f8f8f2] text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-[#bd93f9]" />
          </div>
          <div>
            <label className="text-xs text-[#6272a4] mb-1 block">평균 이익 (%)</label>
            <input type="number" value={avgWin} onChange={e => setAvgWin(+e.target.value)}
              className="w-full rounded-lg bg-[#282a36] border border-[#44475a] text-[#f8f8f2] text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-[#bd93f9]" />
          </div>
          <div>
            <label className="text-xs text-[#6272a4] mb-1 block">평균 손실 (%)</label>
            <input type="number" value={avgLoss} onChange={e => setAvgLoss(+e.target.value)}
              className="w-full rounded-lg bg-[#282a36] border border-[#44475a] text-[#f8f8f2] text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-[#bd93f9]" />
          </div>
        </div>
        <button onClick={() => mutation.mutate()} disabled={mutation.isPending}
          className="px-5 py-2 rounded-xl bg-[#bd93f9] text-[#282a36] text-sm font-bold hover:bg-[#ff79c6] transition-colors disabled:opacity-40">
          {mutation.isPending ? "계산 중..." : "켈리 비율 계산"}
        </button>
      </div>

      {mutation.data && (
        <div className="p-5 rounded-xl border border-[#44475a] bg-[#21222c] space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="p-3 rounded-lg bg-[#282a36]">
              <p className="text-xs text-[#6272a4]">Full Kelly</p>
              <p className="text-xl font-bold text-[#f8f8f2]">{pct(mutation.data.fullKelly)}</p>
            </div>
            <div className="p-3 rounded-lg bg-[#bd93f9]/10 border border-[#bd93f9]/30">
              <p className="text-xs text-[#bd93f9]">권장 (Half Kelly)</p>
              <p className="text-xl font-bold text-[#bd93f9]">{pct(mutation.data.halfKelly)}</p>
            </div>
          </div>
          <p className="text-xs text-[#f8f8f2] p-3 rounded-lg bg-[#282a36]">{mutation.data.recommendation}</p>
        </div>
      )}
    </div>
  );
}

// ── 4. Pattern Recognizer ─────────────────────────────────────────────────────

function PatternTab() {
  const [stockId, setStockId] = useState(2);
  const { data, isLoading } = useQuery<PatternMatch[]>({
    queryKey: ["analytics", "patterns", stockId],
    queryFn: async () => {
      const res = await authFetch(`/api/stocks/${stockId}/patterns`);
      return res.json();
    },
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        {STOCKS.map(s => (
          <button key={s.id} onClick={() => setStockId(s.id)}
            className={`px-3 py-1.5 rounded-full text-xs font-medium transition-colors
              ${stockId === s.id ? "bg-[#bd93f9] text-[#282a36]" : "bg-[#44475a] text-[#6272a4]"}`}>
            {s.label}
          </button>
        ))}
      </div>

      {isLoading ? (
        <div className="text-center py-12 text-[#6272a4] text-sm">패턴 분석 중...</div>
      ) : !data || data.length === 0 ? (
        <div className="text-center py-12 border border-dashed border-[#44475a] rounded-xl text-[#6272a4] text-sm">
          감지된 패턴이 없습니다.
        </div>
      ) : (
        <div className="space-y-2">
          {data.map((p: PatternMatch, i: number) => (
            <div key={i} className="p-4 rounded-xl border border-[#44475a] bg-[#21222c]">
              <div className="flex justify-between items-center mb-1">
                <span className="text-sm font-semibold text-[#f8f8f2]">
                  {PATTERN_LABEL[p.patternType] ?? p.patternType}
                </span>
                <span className="text-xs font-bold text-[#bd93f9]">완성도 {p.confidenceScore}%</span>
              </div>
              <p className="text-xs text-[#6272a4] mb-1">{p.candleFrom} ~ {p.candleTo}</p>
              <p className="text-xs text-[#f8f8f2]">{p.description}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── 5. Regime Detector ────────────────────────────────────────────────────────

function RegimeTab() {
  const [stockId, setStockId] = useState(2);
  const { data, isLoading } = useQuery<RegimeResult>({
    queryKey: ["analytics", "regime", stockId],
    queryFn: async () => {
      const res = await authFetch(`/api/stocks/${stockId}/regime`);
      return res.json();
    },
  });

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        {STOCKS.map(s => (
          <button key={s.id} onClick={() => setStockId(s.id)}
            className={`px-3 py-1.5 rounded-full text-xs font-medium transition-colors
              ${stockId === s.id ? "bg-[#bd93f9] text-[#282a36]" : "bg-[#44475a] text-[#6272a4]"}`}>
            {s.label}
          </button>
        ))}
      </div>

      {isLoading ? (
        <div className="text-center py-12 text-[#6272a4] text-sm">분석 중...</div>
      ) : data?.error ? (
        <p className="text-sm text-[#ff5555] text-center py-8">{data.error}</p>
      ) : data ? (
        <div className="p-5 rounded-xl border border-[#44475a] bg-[#21222c] space-y-4">
          <div className="flex items-center gap-3">
            <span className={`px-4 py-2 rounded-full text-lg font-bold ${REGIME_META[data.regime]?.color ?? ""}`}>
              {REGIME_META[data.regime]?.label ?? data.regime}
            </span>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <p className="text-xs text-[#6272a4]">ADX (추세강도)</p>
              <p className="text-sm font-bold text-[#f8f8f2]">{data.adx.toFixed(1)}</p>
            </div>
            <div>
              <p className="text-xs text-[#6272a4]">변동성 (연환산)</p>
              <p className="text-sm font-bold text-[#f8f8f2]">{pct(data.volatility)}</p>
            </div>
            <div>
              <p className="text-xs text-[#6272a4]">추세 기울기</p>
              <p className={`text-sm font-bold ${data.trendSlope >= 0 ? "text-[#50fa7b]" : "text-[#ff5555]"}`}>
                {data.trendSlope >= 0 ? "+" : ""}{(data.trendSlope * 100).toFixed(3)}%
              </p>
            </div>
          </div>
          <p className="text-xs text-[#f8f8f2] p-3 rounded-lg bg-[#282a36]">{data.explanation}</p>
        </div>
      ) : null}
    </div>
  );
}

// ── Page ────────────────────────────────────────────────────────────────────

const TABS = [
  { key: "portfolio", label: "포트폴리오 최적화" },
  { key: "tax",       label: "세금 최적화" },
  { key: "kelly",     label: "Kelly Criterion" },
  { key: "pattern",   label: "차트 패턴" },
  { key: "regime",    label: "시장 국면" },
] as const;

export default function AnalyticsPage() {
  const [tab, setTab] = useState<typeof TABS[number]["key"]>("portfolio");

  return (
    <div className="max-w-3xl mx-auto px-4 py-8">
      <div className="mb-6">
        <h1 className="text-xl font-bold text-[#f8f8f2]">Quant Analytics</h1>
        <p className="text-xs text-[#6272a4] mt-0.5">
          포트폴리오 최적화 · 세금 시뮬레이션 · 포지션 사이징 · 패턴/국면 분석
        </p>
      </div>

      <div className="flex gap-1 mb-6 border-b border-[#44475a] overflow-x-auto">
        {TABS.map(t => (
          <TabButton key={t.key} active={tab === t.key} onClick={() => setTab(t.key)}>
            {t.label}
          </TabButton>
        ))}
      </div>

      {tab === "portfolio" && <PortfolioOptimizerTab />}
      {tab === "tax" && <TaxOptimizerTab />}
      {tab === "kelly" && <KellyTab />}
      {tab === "pattern" && <PatternTab />}
      {tab === "regime" && <RegimeTab />}
    </div>
  );
}
