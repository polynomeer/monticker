"use client";

import { useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useTheme } from "next-themes";
import { HourglassMedium, Play, Broadcast, Stop } from "@phosphor-icons/react";
import type { RuleSet, QuantBacktestResult, QuantEquityPoint, ForwardTestResult } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";
import { getForwardTestStatus, startForwardTest, stopForwardTest } from "@/services/forwardTest";
import { useForwardTestSignalsWs } from "@/hooks/useForwardTestSignalsWs";

type BacktestResult = QuantBacktestResult;

const EXIT_REASON_LABEL: Record<string, string> = {
  SIGNAL: "청산 신호", END: "기간 종료",
};

const SIGNAL_DIRECTION_LABEL: Record<string, string> = { BUY: "매수", SELL: "매도" };

const RELIABILITY_COLOR: Record<string, string> = {
  A: "text-dracula-green border-dracula-green",
  B: "text-dracula-purple border-dracula-purple",
  C: "text-dracula-orange border-dracula-orange",
  D: "text-dracula-red border-dracula-red",
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

function won(n: number) {
  return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 });
}

function EquityCurveChart({ equityCurve, isDark }: { equityCurve: QuantEquityPoint[]; isDark: boolean }) {
  const chartRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!chartRef.current || equityCurve.length === 0) return;
    let disposed = false;
    let chart: import("echarts").ECharts | undefined;
    let onResize: (() => void) | undefined;
    import("echarts").then(echarts => {
      if (disposed || !chartRef.current) return;
      const existing = echarts.getInstanceByDom(chartRef.current);
      if (existing) existing.dispose();

      chart = echarts.init(chartRef.current, undefined, { renderer: "canvas", height: 220 });
      const rising = equityCurve[equityCurve.length - 1].equity >= equityCurve[0].equity;

      chart.setOption({
        backgroundColor: "transparent",
        animation: false,
        tooltip: {
          trigger: "axis",
          backgroundColor: isDark ? "#282a36" : "#fff",
          borderColor: "#44475a",
          textStyle: { color: isDark ? "#f8f8f2" : "#374151", fontSize: 11 },
          formatter: (params: unknown) => {
            const p = (params as Array<{ axisValue: string; data: number }>)[0];
            const point = equityCurve.find(e => e.date === p.axisValue);
            return `${p.axisValue}<br/>자산 ${won(p.data)}원${point ? `<br/>낙폭 ${point.drawdown.toFixed(2)}%` : ""}`;
          },
        },
        grid: { left: 64, right: 16, top: 16, bottom: 28 },
        xAxis: {
          type: "category", data: equityCurve.map(p => p.date),
          axisLabel: { color: isDark ? "#6272a4" : "#6b7280", fontSize: 10 },
          axisLine: { lineStyle: { color: isDark ? "#44475a" : "#e5e7eb" } },
        },
        yAxis: {
          type: "value", position: "left",
          axisLabel: {
            color: isDark ? "#6272a4" : "#6b7280", fontSize: 10,
            formatter: (v: number) => `${(v / 10000).toFixed(0)}만`,
          },
          splitLine: { lineStyle: { color: isDark ? "#44475a" : "#e5e7eb", type: "dashed" } },
        },
        series: [{
          type: "line", data: equityCurve.map(p => p.equity),
          smooth: true, symbol: "none",
          lineStyle: { color: rising ? "#0ecb81" : "#f6465d", width: 2 },
          areaStyle: {
            color: { type: "linear", x: 0, y: 0, x2: 0, y2: 1, colorStops: [
              { offset: 0, color: rising ? "#0ecb8133" : "#f6465d33" },
              { offset: 1, color: "transparent" },
            ] },
          },
        }],
      });
      onResize = () => chart?.resize();
      window.addEventListener("resize", onResize);
    });
    return () => {
      disposed = true;
      if (onResize) window.removeEventListener("resize", onResize);
      chart?.dispose();
    };
  }, [equityCurve, isDark]);

  return <div ref={chartRef} className="w-full" />;
}

function MetricCard({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className="p-3 rounded-lg bg-gray-50 dark:bg-dracula-bg border border-gray-200 dark:border-dracula-line">
      <p className="text-xs text-gray-500 dark:text-dracula-comment mb-1">{label}</p>
      <p className={`text-base font-bold tabular-nums ${highlight ? "text-blue-600 dark:text-dracula-purple" : "text-gray-900 dark:text-dracula-fg"}`}>{value}</p>
    </div>
  );
}

export default function QuantLabDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const qc = useQueryClient();
  const { toast } = useToast();
  const { resolvedTheme } = useTheme();

  const [stockId, setStockId] = useState(1);
  const [startDate, setStartDate] = useState("2024-01-01");
  const [endDate, setEndDate] = useState("2026-06-01");
  const [capital, setCapital] = useState(10_000_000);
  const [fwStockId, setFwStockId] = useState(1);
  const [fwCapital, setFwCapital] = useState(10_000_000);

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

  const { data: forwardTest } = useQuery<ForwardTestResult | null>({
    queryKey: ["quant", "forward-test", id],
    queryFn: () => getForwardTestStatus(id),
  });

  const startFwMutation = useMutation({
    mutationFn: () => startForwardTest(id, fwStockId, fwCapital),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["quant", "forward-test", id] });
      qc.invalidateQueries({ queryKey: ["quant", "ruleset", id] });
      toast({ type: "success", title: "포워드 테스트 시작", message: "장 마감 후 매일 자동으로 평가됩니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "시작 실패", message: e.message }),
  });

  const stopFwMutation = useMutation({
    mutationFn: () => stopForwardTest(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["quant", "forward-test", id] });
      qc.invalidateQueries({ queryKey: ["quant", "ruleset", id] });
      toast({ type: "success", title: "포워드 테스트 중지", message: "룰셋을 다시 수정할 수 있습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "중지 실패", message: e.message }),
  });

  useForwardTestSignalsWs(
    forwardTest?.status === "RUNNING" ? id : undefined,
    (event) => {
      qc.invalidateQueries({ queryKey: ["quant", "forward-test", id] });
      toast({
        type: "success",
        title: `${SIGNAL_DIRECTION_LABEL[event.direction] ?? event.direction} 신호 발생`,
        message: `${event.evalDate} · ${won(event.price)}원`,
      });
    },
  );

  if (rsLoading) return <div className="p-8 text-gray-500 dark:text-dracula-comment">로딩 중...</div>;
  if (!ruleSet) return <div className="p-8 text-dracula-red">룰셋을 찾을 수 없습니다.</div>;

  const latestResult = results[0];

  return (
    <div className="max-w-4xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      {/* 헤더 */}
      <div className="flex items-start justify-between mb-6">
        <div>
          <button onClick={() => router.push("/quant-lab")} className="text-gray-500 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg text-sm mb-2 block transition-colors">← 보관함</button>
          <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">{ruleSet.name}</h1>
          {ruleSet.description && <p className="text-sm text-gray-500 dark:text-dracula-comment mt-1">{ruleSet.description}</p>}
        </div>
        <button
          onClick={() => router.push(`/quant-lab/builder?edit=${id}`)}
          className="px-4 py-2 rounded-lg text-xs font-medium bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg hover:bg-gray-200 dark:hover:bg-dracula-comment active:scale-95 transition-all duration-150"
        >
          룰셋 수정
        </button>
      </div>

      {/* 백테스트 실행 패널 */}
      <Card className="p-5" outerClassName="mb-8">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mb-4">백테스트 실행</h2>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-4">
          <div>
            <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종목</label>
            <select
              value={stockId}
              onChange={e => setStockId(+e.target.value)}
              className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
            >
              {STOCKS.map(s => <option key={s.id} value={s.id}>{s.label}</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">시작일</label>
            <input
              type="date" value={startDate} onChange={e => setStartDate(e.target.value)}
              className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
            />
          </div>
          <div>
            <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종료일</label>
            <input
              type="date" value={endDate} onChange={e => setEndDate(e.target.value)}
              className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
            />
          </div>
          <div>
            <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">초기 자본 (원)</label>
            <input
              type="number" value={capital} onChange={e => setCapital(+e.target.value)}
              className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
            />
          </div>
        </div>
        <button
          onClick={() => runMutation.mutate()}
          disabled={runMutation.isPending}
          className="w-full py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100 inline-flex items-center justify-center gap-1.5"
        >
          {runMutation.isPending
            ? <><HourglassMedium size={14} weight="bold" aria-hidden /> 백테스트 실행 중...</>
            : <><Play size={14} weight="fill" aria-hidden /> 백테스트 실행</>}
        </button>
        <p className="mt-2 text-xs text-gray-500 dark:text-dracula-comment text-center">
          수수료 0.015% + 슬리피지 0.1% 반영 · 과거 성과가 미래 수익을 보장하지 않습니다
        </p>
      </Card>

      {/* 최신 결과 */}
      {latestResult && (
        <div className="mb-8">
          <div className="flex items-center gap-3 mb-4">
            <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">최신 백테스트 결과</h2>
            {latestResult.reliabilityScore && (
              <span className={`text-xs font-bold px-2.5 py-0.5 rounded-full border ${RELIABILITY_COLOR[latestResult.reliabilityScore] ?? ""}`}>
                신뢰도 {latestResult.reliabilityScore}
              </span>
            )}
            <span className="text-xs text-gray-500 dark:text-dracula-comment ml-auto">
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

          {/* 자산 곡선 */}
          {latestResult.equityCurve.length > 0 && (
            <Card className="overflow-hidden mt-4">
              <div className="px-4 pt-3 text-xs font-medium text-gray-500 dark:text-dracula-comment">자산 곡선</div>
              <EquityCurveChart equityCurve={latestResult.equityCurve} isDark={resolvedTheme === "dark"} />
            </Card>
          )}

          {/* 거래 내역 */}
          {latestResult.trades.length > 0 && (
            <Card className="overflow-hidden mt-4">
              <div className="px-4 py-3 border-b border-gray-200 dark:border-dracula-line bg-gray-50 dark:bg-transparent">
                <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">거래 내역</span>
                <span className="ml-2 text-xs text-gray-500 dark:text-dracula-comment">{latestResult.trades.length}건</span>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-gray-200 dark:border-dracula-line text-gray-500 dark:text-dracula-comment">
                      {["매수일", "매도일", "매수가", "매도가", "수량", "손익", "수익률", "사유"].map(h => (
                        <th key={h} className="px-3 py-2 text-left">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {latestResult.trades.map((t, i) => (
                      <tr key={i} className="border-b border-gray-100 dark:border-dracula-line/40 hover:bg-gray-50 dark:hover:bg-dracula-line/10 transition-colors">
                        <td className="px-3 py-2 text-gray-500 dark:text-dracula-comment tabular-nums">{t.entryDate}</td>
                        <td className="px-3 py-2 text-gray-500 dark:text-dracula-comment tabular-nums">{t.exitDate}</td>
                        <td className="px-3 py-2 font-mono tabular-nums text-gray-900 dark:text-dracula-fg">{won(t.entryPrice)}</td>
                        <td className="px-3 py-2 font-mono tabular-nums text-gray-900 dark:text-dracula-fg">{won(t.exitPrice)}</td>
                        <td className="px-3 py-2 font-mono tabular-nums text-gray-500 dark:text-dracula-comment">{t.quantity}</td>
                        <td className={`px-3 py-2 font-mono tabular-nums font-bold ${t.pnl >= 0 ? "text-dracula-green" : "text-dracula-red"}`}>
                          {t.pnl >= 0 ? "+" : ""}{won(t.pnl)}
                        </td>
                        <td className={`px-3 py-2 font-mono tabular-nums font-bold ${t.pnlPct >= 0 ? "text-dracula-green" : "text-dracula-red"}`}>
                          {fmt(t.pnlPct)}
                        </td>
                        <td className="px-3 py-2">
                          <span className="px-1.5 py-0.5 rounded text-[10px] bg-gray-100 text-gray-500 dark:bg-dracula-line dark:text-dracula-comment">
                            {EXIT_REASON_LABEL[t.exitReason] ?? t.exitReason}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          )}
        </div>
      )}

      {/* 이전 결과 목록 */}
      {results.length > 1 && (
        <div>
          <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mb-3">이전 백테스트 이력</h2>
          <div className="space-y-2">
            {results.slice(1).map((r: BacktestResult) => (
              <div key={r.id} className="flex items-center justify-between p-3 rounded-lg bg-white dark:bg-dracula-surface border border-gray-200 dark:border-dracula-line text-xs">
                <span className="text-gray-500 dark:text-dracula-comment tabular-nums">{r.startDate} ~ {r.endDate}</span>
                <span className={`font-bold tabular-nums ${(r.totalReturn ?? 0) >= 0 ? "text-dracula-green" : "text-dracula-red"}`}>
                  {fmt(r.totalReturn)}
                </span>
                <span className="text-gray-500 dark:text-dracula-comment tabular-nums">MDD {fmt(r.mdd)}</span>
                {r.reliabilityScore && (
                  <span className={`font-bold ${RELIABILITY_COLOR[r.reliabilityScore]}`}>
                    {r.reliabilityScore}
                  </span>
                )}
                <span className="text-gray-400 dark:text-dracula-line tabular-nums">{new Date(r.createdAt).toLocaleDateString("ko-KR")}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {results.length === 0 && !resultsLoading && (
        <div className="text-center py-12 text-gray-500 dark:text-dracula-comment text-sm border border-dashed border-gray-300 dark:border-dracula-line rounded-xl">
          아직 백테스트 결과가 없습니다. 위에서 실행해보세요.
        </div>
      )}

      {/* 포워드 테스트 */}
      <div className="mt-8">
        <h2 className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mb-3 flex items-center gap-1.5">
          <Broadcast size={16} weight="bold" aria-hidden /> 포워드 테스트
        </h2>

        {forwardTest?.status === "RUNNING" ? (
          <div className="space-y-4">
            <Card className="p-5">
              <div className="flex items-center justify-between mb-4">
                <span className="inline-flex items-center gap-1.5 text-xs font-bold px-2.5 py-1 rounded-full bg-dracula-green/10 text-dracula-green">
                  <span className="w-1.5 h-1.5 rounded-full bg-dracula-green animate-pulse" /> 운용 중
                </span>
                <button
                  onClick={() => stopFwMutation.mutate()}
                  disabled={stopFwMutation.isPending}
                  className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-medium border border-dracula-red/40 text-dracula-red hover:bg-dracula-red/10 active:scale-95 transition-all duration-150 disabled:opacity-40"
                >
                  <Stop size={12} weight="fill" aria-hidden /> {stopFwMutation.isPending ? "중지 중..." : "중지"}
                </button>
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <MetricCard label="현재 자산" value={`${won(forwardTest.currentEquity)}원`} highlight />
                <MetricCard label="초기 자본" value={`${won(forwardTest.initialCapital)}원`} />
                <MetricCard
                  label="포지션"
                  value={forwardTest.holdingQty > 0 ? `보유 ${forwardTest.holdingQty}주` : "미보유"}
                />
                <MetricCard label="시작일" value={new Date(forwardTest.startedAt).toLocaleDateString("ko-KR")} />
              </div>
              <p className="mt-3 text-xs text-gray-500 dark:text-dracula-comment text-center">
                매일 장 마감 후(KST 16:00) 자동으로 평가되며, 신호 발생 시 실시간으로 알려드립니다.
              </p>
            </Card>

            {forwardTest.equityCurve.length > 0 && (
              <Card className="overflow-hidden">
                <div className="px-4 pt-3 text-xs font-medium text-gray-500 dark:text-dracula-comment">자산 곡선</div>
                <EquityCurveChart equityCurve={forwardTest.equityCurve} isDark={resolvedTheme === "dark"} />
              </Card>
            )}

            <Card className="overflow-hidden">
              <div className="px-4 py-3 border-b border-gray-200 dark:border-dracula-line bg-gray-50 dark:bg-transparent">
                <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">신호 이력</span>
                <span className="ml-2 text-xs text-gray-500 dark:text-dracula-comment">{forwardTest.signals.length}건</span>
              </div>
              {forwardTest.signals.length === 0 ? (
                <div className="text-center py-8 text-xs text-gray-500 dark:text-dracula-comment">
                  아직 발생한 신호가 없습니다.
                </div>
              ) : (
                <div className="divide-y divide-gray-100 dark:divide-dracula-line/40">
                  {forwardTest.signals.map((s, i) => (
                    <div key={i} className="flex items-center justify-between px-4 py-2.5 text-xs">
                      <span className={`font-bold px-2 py-0.5 rounded ${s.direction === "BUY" ? "bg-dracula-green/10 text-dracula-green" : "bg-dracula-red/10 text-dracula-red"}`}>
                        {SIGNAL_DIRECTION_LABEL[s.direction] ?? s.direction}
                      </span>
                      <span className="text-gray-500 dark:text-dracula-comment tabular-nums">{s.evalDate ?? new Date(s.signalTime).toLocaleDateString("ko-KR")}</span>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </div>
        ) : ruleSet.status === "BACKTESTED" ? (
          <Card className="p-5">
            <div className="grid grid-cols-2 gap-3 mb-4">
              <div>
                <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종목</label>
                <select
                  value={fwStockId}
                  onChange={e => setFwStockId(+e.target.value)}
                  className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
                >
                  {STOCKS.map(s => <option key={s.id} value={s.id}>{s.label}</option>)}
                </select>
              </div>
              <div>
                <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">초기 자본 (원)</label>
                <input
                  type="number" value={fwCapital} onChange={e => setFwCapital(+e.target.value)}
                  className="w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
                />
              </div>
            </div>
            <button
              onClick={() => startFwMutation.mutate()}
              disabled={startFwMutation.isPending}
              className="w-full py-2.5 rounded-xl bg-dracula-green text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100 inline-flex items-center justify-center gap-1.5"
            >
              <Broadcast size={14} weight="bold" aria-hidden /> {startFwMutation.isPending ? "시작 중..." : "포워드 테스트 시작"}
            </button>
            <p className="mt-2 text-xs text-gray-500 dark:text-dracula-comment text-center">
              시작하면 룰셋 수정이 잠기고, 매일 장 마감 후 자동으로 신호를 평가합니다.
            </p>
          </Card>
        ) : (
          <div className="text-center py-8 text-gray-500 dark:text-dracula-comment text-sm border border-dashed border-gray-300 dark:border-dracula-line rounded-xl">
            백테스트를 먼저 완료해야 포워드 테스트를 시작할 수 있습니다.
          </div>
        )}
      </div>
    </div>
  );
}
