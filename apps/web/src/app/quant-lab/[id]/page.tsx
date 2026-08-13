"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";

interface RuleSet {
  id: number; name: string; description: string | null;
  version: number; status: string; ruleDefinition: string;
}

interface BacktestResult {
  id: number; stockId: number; startDate: string; endDate: string;
  initialCapital: number; finalCapital: number;
  totalReturn: number | null; annualReturn: number | null;
  mdd: number | null; winRate: number | null; profitFactor: number | null;
  tradeCount: number | null; avgHoldingDays: number | null;
  benchmarkReturn: number | null; excessReturn: number | null;
  reliabilityScore: string | null; createdAt: string;
}

const RELIABILITY_COLOR: Record<string, string> = {
  A: "text-[#50fa7b] border-[#50fa7b]",
  B: "text-[#bd93f9] border-[#bd93f9]",
  C: "text-[#ffb86c] border-[#ffb86c]",
  D: "text-[#ff5555] border-[#ff5555]",
};

const STOCKS = [
  { id: 1,  label: "삼성전자 (005930)" },
  { id: 2,  label: "SK하이닉스 (000660)" },
  { id: 3,  label: "현대차 (005380)" },
  { id: 4,  label: "NAVER (035420)" },
  { id: 5,  label: "카카오 (035720)" },
  { id: 51, label: "AAPL (Apple)" },
  { id: 52, label: "MSFT (Microsoft)" },
  { id: 53, label: "NVDA (NVIDIA)" },
];

function fmt(n: number | null | undefined, suffix = "%", digits = 2) {
  if (n == null) return "—";
  const s = n.toFixed(digits);
  return n > 0 ? `+${s}${suffix}` : `${s}${suffix}`;
}

function MetricCard({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className="p-3 rounded-lg bg-gray-50 dark:bg-[#282a36] border border-gray-200 dark:border-[#44475a]">
      <p className="text-xs text-gray-500 dark:text-[#6272a4] mb-1">{label}</p>
      <p className={`text-base font-bold tabular-nums ${highlight ? "text-blue-600 dark:text-[#bd93f9]" : "text-gray-900 dark:text-[#f8f8f2]"}`}>{value}</p>
    </div>
  );
}

export default function QuantLabDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const qc = useQueryClient();
  const { toast } = useToast();

  const [stockId, setStockId] = useState(1);
  const [startDate, setStartDate] = useState("2024-01-01");
  const [endDate, setEndDate] = useState("2026-06-01");
  const [capital, setCapital] = useState(10_000_000);

  const { data: ruleSet, isLoading: rsLoading } = useQuery<RuleSet>({
    queryKey: ["quant", "ruleset", id],
    queryFn: async () => {
      const res = await authFetch(`/api/quant/rulesets/${id}`);
      if (!res.ok) throw new Error("룰셋 조회 실패");
      return res.json();
    },
  });

  const { data: results = [], isLoading: resultsLoading } = useQuery<BacktestResult[]>({
    queryKey: ["quant", "backtest", id],
    queryFn: async () => {
      const res = await authFetch(`/api/quant/rulesets/${id}/backtest`);
      if (!res.ok) throw new Error("백테스트 결과 조회 실패");
      return res.json();
    },
  });

  const runMutation = useMutation({
    mutationFn: async () => {
      const res = await authFetch(`/api/quant/rulesets/${id}/backtest`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId, startDate, endDate, initialCapital: capital }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message ?? "백테스트 실패");
      }
      return res.json();
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["quant", "backtest", id] });
      qc.invalidateQueries({ queryKey: ["quant", "rulesets"] });
      toast({ type: "success", title: "백테스트 완료", message: "결과가 저장되었습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "백테스트 실패", message: e.message }),
  });

  if (rsLoading) return <div className="p-8 text-gray-500 dark:text-[#6272a4]">로딩 중...</div>;
  if (!ruleSet) return <div className="p-8 text-[#ff5555]">룰셋을 찾을 수 없습니다.</div>;

  const latestResult = results[0];

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      {/* 헤더 */}
      <div className="flex items-start justify-between mb-6">
        <div>
          <button onClick={() => router.push("/quant-lab")} className="text-gray-500 dark:text-[#6272a4] hover:text-gray-900 dark:hover:text-[#f8f8f2] text-sm mb-2 block transition-colors">← 보관함</button>
          <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-[#f8f8f2]">{ruleSet.name}</h1>
          {ruleSet.description && <p className="text-sm text-gray-500 dark:text-[#6272a4] mt-1">{ruleSet.description}</p>}
        </div>
        <button
          onClick={() => router.push(`/quant-lab/builder?edit=${id}`)}
          className="px-4 py-2 rounded-lg text-xs font-medium bg-gray-100 dark:bg-[#44475a] text-gray-700 dark:text-[#f8f8f2] hover:bg-gray-200 dark:hover:bg-[#6272a4] active:scale-95 transition-all duration-150"
        >
          룰셋 수정
        </button>
      </div>

      {/* 백테스트 실행 패널 */}
      <div className="mb-8 p-5 rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-[#f8f8f2] mb-4">백테스트 실행</h2>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
          <div>
            <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">종목</label>
            <select
              value={stockId}
              onChange={e => setStockId(+e.target.value)}
              className="w-full rounded-lg bg-white dark:bg-[#282a36] border border-gray-300 dark:border-[#44475a] text-gray-900 dark:text-[#f8f8f2] px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-[#6272a4] focus:outline-none focus:ring-2 focus:ring-[#bd93f9]/50"
            >
              {STOCKS.map(s => <option key={s.id} value={s.id}>{s.label}</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">시작일</label>
            <input
              type="date" value={startDate} onChange={e => setStartDate(e.target.value)}
              className="w-full rounded-lg bg-white dark:bg-[#282a36] border border-gray-300 dark:border-[#44475a] text-gray-900 dark:text-[#f8f8f2] px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-[#6272a4] focus:outline-none focus:ring-2 focus:ring-[#bd93f9]/50"
            />
          </div>
          <div>
            <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">종료일</label>
            <input
              type="date" value={endDate} onChange={e => setEndDate(e.target.value)}
              className="w-full rounded-lg bg-white dark:bg-[#282a36] border border-gray-300 dark:border-[#44475a] text-gray-900 dark:text-[#f8f8f2] px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-[#6272a4] focus:outline-none focus:ring-2 focus:ring-[#bd93f9]/50"
            />
          </div>
          <div>
            <label className="text-xs text-gray-500 dark:text-[#6272a4] mb-1 block">초기 자본 (원)</label>
            <input
              type="number" value={capital} onChange={e => setCapital(+e.target.value)}
              className="w-full rounded-lg bg-white dark:bg-[#282a36] border border-gray-300 dark:border-[#44475a] text-gray-900 dark:text-[#f8f8f2] px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-[#6272a4] focus:outline-none focus:ring-2 focus:ring-[#bd93f9]/50"
            />
          </div>
        </div>
        <button
          onClick={() => runMutation.mutate()}
          disabled={runMutation.isPending}
          className="w-full py-2.5 rounded-xl bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100"
        >
          {runMutation.isPending ? "⏳ 백테스트 실행 중..." : "▶ 백테스트 실행"}
        </button>
        <p className="mt-2 text-xs text-gray-500 dark:text-[#6272a4] text-center">
          수수료 0.015% + 슬리피지 0.1% 반영 · 과거 성과가 미래 수익을 보장하지 않습니다
        </p>
      </div>

      {/* 최신 결과 */}
      {latestResult && (
        <div className="mb-8">
          <div className="flex items-center gap-3 mb-4">
            <h2 className="text-sm font-semibold text-gray-900 dark:text-[#f8f8f2]">최신 백테스트 결과</h2>
            {latestResult.reliabilityScore && (
              <span className={`text-xs font-bold px-2.5 py-0.5 rounded-full border ${RELIABILITY_COLOR[latestResult.reliabilityScore] ?? ""}`}>
                신뢰도 {latestResult.reliabilityScore}
              </span>
            )}
            <span className="text-xs text-gray-500 dark:text-[#6272a4] ml-auto">
              {latestResult.startDate} ~ {latestResult.endDate}
            </span>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
            <MetricCard label="총 수익률" value={fmt(latestResult.totalReturn)} highlight />
            <MetricCard label="연환산 수익률" value={fmt(latestResult.annualReturn)} />
            <MetricCard label="최대 낙폭 (MDD)" value={fmt(latestResult.mdd)} />
            <MetricCard label="벤치마크 대비" value={fmt(latestResult.excessReturn)} />
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
            <MetricCard label="승률" value={fmt(latestResult.winRate)} />
            <MetricCard label="손익비" value={latestResult.profitFactor?.toFixed(2) ?? "—"} />
            <MetricCard label="거래 횟수" value={`${latestResult.tradeCount ?? "—"}회`} />
            <MetricCard label="평균 보유" value={`${latestResult.avgHoldingDays?.toFixed(1) ?? "—"}일`} />
          </div>

          {/* 신뢰도 설명 */}
          {latestResult.reliabilityScore && (
            <div className={`p-3 rounded-lg border text-xs ${RELIABILITY_COLOR[latestResult.reliabilityScore]}`}>
              <strong>신뢰도 {latestResult.reliabilityScore}</strong>
              {latestResult.reliabilityScore === "A" && " — 충분한 거래 횟수와 검증 기간을 갖춘 신뢰할 수 있는 결과입니다."}
              {latestResult.reliabilityScore === "B" && " — 전반적으로 신뢰할 수 있으나 더 긴 검증 기간이 필요합니다."}
              {latestResult.reliabilityScore === "C" && " — 거래 횟수가 적어 통계적 신뢰도가 제한적입니다. 더 긴 기간으로 테스트하세요."}
              {latestResult.reliabilityScore === "D" && " — 거래 횟수가 매우 적습니다. 과최적화 위험이 높습니다."}
            </div>
          )}
        </div>
      )}

      {/* 이전 결과 목록 */}
      {results.length > 1 && (
        <div>
          <h2 className="text-sm font-semibold text-gray-900 dark:text-[#f8f8f2] mb-3">이전 백테스트 이력</h2>
          <div className="space-y-2">
            {results.slice(1).map((r: BacktestResult) => (
              <div key={r.id} className="flex items-center justify-between p-3 rounded-lg bg-white dark:bg-[#21222c] border border-gray-200 dark:border-[#44475a] text-xs">
                <span className="text-gray-500 dark:text-[#6272a4] tabular-nums">{r.startDate} ~ {r.endDate}</span>
                <span className={`font-bold tabular-nums ${(r.totalReturn ?? 0) >= 0 ? "text-[#50fa7b]" : "text-[#ff5555]"}`}>
                  {fmt(r.totalReturn)}
                </span>
                <span className="text-gray-500 dark:text-[#6272a4] tabular-nums">MDD {fmt(r.mdd)}</span>
                {r.reliabilityScore && (
                  <span className={`font-bold ${RELIABILITY_COLOR[r.reliabilityScore]}`}>
                    {r.reliabilityScore}
                  </span>
                )}
                <span className="text-gray-400 dark:text-[#44475a] tabular-nums">{new Date(r.createdAt).toLocaleDateString("ko-KR")}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {results.length === 0 && !resultsLoading && (
        <div className="text-center py-12 text-gray-500 dark:text-[#6272a4] text-sm border border-dashed border-gray-300 dark:border-[#44475a] rounded-xl">
          아직 백테스트 결과가 없습니다. 위에서 실행해보세요.
        </div>
      )}
    </div>
  );
}
