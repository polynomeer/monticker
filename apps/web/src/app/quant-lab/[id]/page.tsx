"use client";

import { useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useTheme } from "next-themes";
import { HourglassMedium, Play, Broadcast, Stop, ShareNetwork } from "@phosphor-icons/react";
import type { RuleSet, QuantBacktestResult, QuantEquityPoint, ForwardTestResult } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";
import { getForwardTestStatus, startForwardTest, stopForwardTest } from "@/services/forwardTest";
import { useForwardTestSignalsWs } from "@/hooks/useForwardTestSignalsWs";
import { StockPicker } from "@/components/quant/StockPicker";
import { shareStrategy } from "@/services/strategyMarket";

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

function ShareModal({ onClose, onSubmit, isPending }: {
  onClose: () => void;
  onSubmit: (description: string, price: number) => void;
  isPending: boolean;
}) {
  const [description, setDescription] = useState("");
  const [price, setPrice] = useState(0);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <Card className="p-6" outerClassName="w-full max-w-sm mx-4 animate-fade-up">
        <h2 className="text-base font-bold text-gray-900 dark:text-dracula-fg mb-1">전략 마켓에 공유</h2>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mb-5">
          룰 로직은 공개되지 않고 구독자에게는 성과 지표만 보입니다. 구독료의 70%가 제작자 수익으로 적립됩니다.
        </p>
        <div className="space-y-3">
          <div>
            <label htmlFor="share-description" className="text-xs text-gray-500 dark:text-dracula-comment block mb-1">전략 소개</label>
            <textarea
              id="share-description"
              value={description}
              onChange={e => setDescription(e.target.value)}
              rows={3}
              placeholder="이 전략을 소개해주세요 (선택)"
              className="w-full px-3 py-2 rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm resize-none focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
            />
          </div>
          <div>
            <label htmlFor="share-price" className="text-xs text-gray-500 dark:text-dracula-comment block mb-1">구독료 (원, 0이면 무료)</label>
            <input
              id="share-price"
              type="number"
              min={0}
              value={price}
              onChange={e => setPrice(Math.max(0, +e.target.value))}
              className="w-full px-3 py-2 rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg text-sm focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
            />
          </div>
        </div>
        <div className="flex gap-2 pt-5">
          <button onClick={onClose} disabled={isPending}
            className="flex-1 py-2.5 rounded-xl border border-gray-300 dark:border-dracula-line text-gray-500 dark:text-dracula-comment text-sm hover:bg-gray-50 dark:hover:bg-dracula-line/30 active:scale-[0.98] transition-all duration-150 disabled:opacity-40">
            취소
          </button>
          <button onClick={() => onSubmit(description, price)} disabled={isPending}
            className="flex-1 py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-sm font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40">
            {isPending ? "공유 중..." : "공유하기"}
          </button>
        </div>
      </Card>
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
  const [showShareModal, setShowShareModal] = useState(false);

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

  const shareMutation = useMutation({
    mutationFn: ({ description, price }: { description: string; price: number }) => shareStrategy(id, description, price),
    onSuccess: () => {
      setShowShareModal(false);
      toast({ type: "success", title: "공유 완료", message: "전략 마켓에서 확인할 수 있습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "공유 실패", message: e.message }),
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
  const universe: { market?: string; marketCapTier?: string } = (() => {
    try { return JSON.parse(ruleSet.universeJson || "{}"); } catch { return {}; }
  })();
  const universeMarket = universe.market ?? "all";
  const universeMarketCapTier = universe.marketCapTier ?? "all";
  const canShare = ruleSet.status === "BACKTESTED" || ruleSet.status === "RUNNING";

  const STATUS_LABEL_MAP: Record<string, { label: string; color: string }> = {
    DRAFT:      { label: "작성 중",        color: "text-gray-500 dark:text-dracula-comment bg-gray-100 dark:bg-dracula-line" },
    BACKTESTED: { label: "백테스트 완료",  color: "text-dracula-green bg-dracula-green/10" },
    RUNNING:    { label: "운용 중",         color: "text-dracula-purple bg-dracula-purple/10" },
    ARCHIVED:   { label: "보관됨",          color: "text-gray-500 dark:text-dracula-comment bg-gray-100 dark:bg-dracula-line" },
  };
  const st = STATUS_LABEL_MAP[ruleSet.status] ?? STATUS_LABEL_MAP.DRAFT;

  const inputCls = "w-full rounded-lg bg-white dark:bg-dracula-bg border border-gray-300 dark:border-dracula-line text-gray-900 dark:text-dracula-fg px-3 py-2 text-xs transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50";

  return (
    <div className="flex flex-col animate-fade-up">
      {showShareModal && (
        <ShareModal
          onClose={() => setShowShareModal(false)}
          onSubmit={(description, price) => shareMutation.mutate({ description, price })}
          isPending={shareMutation.isPending}
        />
      )}

      {/* ── 상단 헤더 바 ─────────────────────────────────────────── */}
      <div className="flex items-center gap-4 border-b border-gray-100 dark:border-white/5
                      px-4 py-3 bg-white dark:bg-dracula-bg overflow-x-auto [scrollbar-width:none]">
        <button
          onClick={() => router.push("/quant-lab")}
          className="text-gray-400 dark:text-dracula-comment hover:text-gray-900 dark:hover:text-dracula-fg text-xs transition-colors shrink-0"
        >
          ← Quant Lab
        </button>
        <div className="w-px h-5 bg-gray-200 dark:bg-dracula-line shrink-0" />
        <div className="flex items-center gap-2 shrink-0">
          <span className="font-bold text-sm text-gray-900 dark:text-dracula-fg">{ruleSet.name}</span>
          {ruleSet.description && (
            <span className="text-xs text-gray-500 dark:text-dracula-comment hidden sm:block">{ruleSet.description}</span>
          )}
          <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full ${st.color}`}>{st.label}</span>
          <span className="text-[10px] text-gray-400 dark:text-dracula-line">v{ruleSet.version}</span>
        </div>
        <div className="ml-auto flex items-center gap-2 shrink-0">
          {canShare && (
            <button
              onClick={() => setShowShareModal(true)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border border-dracula-purple/40 text-blue-600 dark:text-dracula-purple hover:bg-blue-50 dark:hover:bg-dracula-purple/10 active:scale-95 transition-all duration-150"
            >
              <ShareNetwork size={13} weight="bold" aria-hidden /> 전략 공유
            </button>
          )}
          <button
            onClick={() => router.push(`/quant-lab/builder?edit=${id}`)}
            className="px-3 py-1.5 rounded-lg text-xs font-medium bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg hover:bg-gray-200 dark:hover:bg-dracula-comment active:scale-95 transition-all duration-150"
          >
            룰셋 수정
          </button>
        </div>
      </div>

      {/* ── 2컬럼 메인 레이아웃: [설정 사이드바 | 결과 메인] ─────── */}
      <div className="flex flex-col lg:flex-row lg:min-h-[calc(100vh-108px)]
                      lg:divide-x dark:lg:divide-white/5">

        {/* ===== 좌측 패널: 백테스트 설정 + 포워드 테스트 ===== */}
        <aside className="w-full lg:w-[300px] lg:flex-none lg:overflow-y-auto
                          px-4 py-4 space-y-5
                          dark:bg-[#1e202a]
                          [scrollbar-width:thin]">

          {/* 백테스트 설정 */}
          <section>
            <h2 className="text-[11px] font-bold uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-3">
              백테스트 설정
            </h2>
            <div className="space-y-3">
              <div>
                <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종목</label>
                <StockPicker market={universeMarket} marketCapTier={universeMarketCapTier} value={stockId} onChange={setStockId} />
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">시작일</label>
                  <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} className={inputCls} />
                </div>
                <div>
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종료일</label>
                  <input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} className={inputCls} />
                </div>
              </div>
              <div>
                <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">초기 자본 (원)</label>
                <input type="number" value={capital} onChange={e => setCapital(+e.target.value)} className={inputCls} />
              </div>
              <button
                onClick={() => runMutation.mutate()}
                disabled={runMutation.isPending}
                className="w-full py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100 inline-flex items-center justify-center gap-1.5"
              >
                {runMutation.isPending
                  ? <><HourglassMedium size={13} weight="bold" aria-hidden /> 실행 중...</>
                  : <><Play size={13} weight="fill" aria-hidden /> 백테스트 실행</>}
              </button>
              <p className="text-[10px] text-gray-400 dark:text-dracula-comment text-center leading-tight">
                수수료 0.015% + 슬리피지 0.1% 반영
              </p>
            </div>
          </section>

          {/* 구분선 */}
          <div className="border-t dark:border-dracula-line/60" />

          {/* 포워드 테스트 */}
          <section>
            <h2 className="text-[11px] font-bold uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-3 flex items-center gap-1.5">
              <Broadcast size={12} weight="bold" aria-hidden /> 포워드 테스트
            </h2>

            {forwardTest?.status === "RUNNING" ? (
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <span className="inline-flex items-center gap-1.5 text-[11px] font-bold px-2 py-1 rounded-full bg-dracula-green/10 text-dracula-green">
                    <span className="w-1.5 h-1.5 rounded-full bg-dracula-green animate-pulse" /> 운용 중
                  </span>
                  <button
                    onClick={() => stopFwMutation.mutate()}
                    disabled={stopFwMutation.isPending}
                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-[11px] font-medium border border-dracula-red/40 text-dracula-red hover:bg-dracula-red/10 active:scale-95 transition-all duration-150 disabled:opacity-40"
                  >
                    <Stop size={11} weight="fill" aria-hidden /> {stopFwMutation.isPending ? "중지 중..." : "중지"}
                  </button>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <MetricCard label="현재 자산" value={`${won(forwardTest.currentEquity)}원`} highlight />
                  <MetricCard label="초기 자본" value={`${won(forwardTest.initialCapital)}원`} />
                  <MetricCard label="포지션" value={forwardTest.holdingQty > 0 ? `보유 ${forwardTest.holdingQty}주` : "미보유"} />
                  <MetricCard label="시작일" value={new Date(forwardTest.startedAt).toLocaleDateString("ko-KR")} />
                </div>
                {forwardTest.equityCurve.length > 0 && (
                  <div className="rounded-lg overflow-hidden border border-gray-200 dark:border-dracula-line">
                    <div className="px-3 pt-2 text-[10px] font-medium text-gray-500 dark:text-dracula-comment">운용 자산 곡선</div>
                    <EquityCurveChart equityCurve={forwardTest.equityCurve} isDark={resolvedTheme === "dark"} />
                  </div>
                )}
                {/* 신호 이력 */}
                <div className="rounded-lg border border-gray-200 dark:border-dracula-line overflow-hidden">
                  <div className="flex items-center justify-between px-3 py-2 border-b border-gray-100 dark:border-dracula-line/50">
                    <span className="text-[11px] font-semibold text-gray-900 dark:text-dracula-fg">신호 이력</span>
                    <span className="text-[10px] text-gray-400 dark:text-dracula-comment">{forwardTest.signals.length}건</span>
                  </div>
                  {forwardTest.signals.length === 0 ? (
                    <p className="text-center py-4 text-[10px] text-gray-400 dark:text-dracula-comment">아직 신호 없음</p>
                  ) : (
                    <div className="divide-y divide-gray-100 dark:divide-dracula-line/30">
                      {forwardTest.signals.map((s, i) => (
                        <div key={i} className="flex items-center justify-between px-3 py-2 text-[11px]">
                          <span className={`font-bold px-1.5 py-0.5 rounded ${s.direction === "BUY" ? "bg-dracula-green/10 text-dracula-green" : "bg-dracula-red/10 text-dracula-red"}`}>
                            {SIGNAL_DIRECTION_LABEL[s.direction] ?? s.direction}
                          </span>
                          <span className="text-gray-400 dark:text-dracula-comment tabular-nums">
                            {s.evalDate ?? new Date(s.signalTime).toLocaleDateString("ko-KR")}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                <p className="text-[10px] text-gray-400 dark:text-dracula-comment text-center">
                  매일 장 마감 후(KST 16:00) 자동 평가
                </p>
              </div>
            ) : ruleSet.status === "BACKTESTED" ? (
              <div className="space-y-3">
                <div>
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">종목</label>
                  <StockPicker market={universeMarket} marketCapTier={universeMarketCapTier} value={fwStockId} onChange={setFwStockId} />
                </div>
                <div>
                  <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">초기 자본 (원)</label>
                  <input type="number" value={fwCapital} onChange={e => setFwCapital(+e.target.value)} className={inputCls} />
                </div>
                <button
                  onClick={() => startFwMutation.mutate()}
                  disabled={startFwMutation.isPending}
                  className="w-full py-2.5 rounded-xl bg-dracula-green text-dracula-bg font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 inline-flex items-center justify-center gap-1.5"
                >
                  <Broadcast size={13} weight="bold" aria-hidden />
                  {startFwMutation.isPending ? "시작 중..." : "포워드 테스트 시작"}
                </button>
                <p className="text-[10px] text-gray-400 dark:text-dracula-comment text-center leading-tight">
                  시작하면 룰셋 수정이 잠기고, 매일 장 마감 후 자동 평가합니다.
                </p>
              </div>
            ) : (
              <div className="text-center py-6 text-[11px] text-gray-400 dark:text-dracula-comment border border-dashed border-gray-200 dark:border-dracula-line rounded-lg">
                백테스트를 먼저 완료해야<br />포워드 테스트를 시작할 수 있습니다.
              </div>
            )}
          </section>

          {/* 이전 이력 */}
          {results.length > 1 && (
            <>
              <div className="border-t dark:border-dracula-line/60" />
              <section>
                <h2 className="text-[11px] font-bold uppercase tracking-wider text-gray-400 dark:text-dracula-comment mb-2">
                  이전 이력
                </h2>
                <div className="space-y-1.5">
                  {results.slice(1).map((r: BacktestResult) => (
                    <div key={r.id} className="flex items-center justify-between p-2 rounded-lg border border-gray-100 dark:border-dracula-line/40 text-[11px]">
                      <span className="text-gray-400 dark:text-dracula-comment tabular-nums">{r.startDate?.slice(0, 7)} ~</span>
                      <span className={`font-bold tabular-nums ${(r.totalReturn ?? 0) >= 0 ? "text-dracula-green" : "text-dracula-red"}`}>
                        {fmt(r.totalReturn)}
                      </span>
                      {r.reliabilityScore && (
                        <span className={`font-bold border rounded px-1 py-0.5 text-[10px] ${RELIABILITY_COLOR[r.reliabilityScore]}`}>
                          {r.reliabilityScore}
                        </span>
                      )}
                    </div>
                  ))}
                </div>
              </section>
            </>
          )}

          <p className="text-[9px] text-gray-300 dark:text-dracula-line text-center pb-2">
            과거 성과가 미래 수익을 보장하지 않습니다
          </p>
        </aside>

        {/* ===== 우측 메인: 성과 지표 + 자산 곡선 + 거래 내역 ===== */}
        <main className="flex-1 min-w-0 px-4 py-4 overflow-y-auto space-y-4">

          {/* 결과 없는 빈 상태 */}
          {results.length === 0 && !resultsLoading && (
            <div className="flex flex-col items-center justify-center h-80 text-gray-400 dark:text-dracula-comment text-sm border border-dashed border-gray-200 dark:border-dracula-line rounded-xl">
              <p className="mb-1">아직 백테스트 결과가 없습니다.</p>
              <p className="text-xs">왼쪽 패널에서 설정 후 실행하세요.</p>
            </div>
          )}

          {latestResult && (
            <>
              {/* 결과 헤더 */}
              <div className="flex items-center gap-3 flex-wrap">
                <h2 className="text-sm font-bold text-gray-900 dark:text-dracula-fg">최신 백테스트 결과</h2>
                {latestResult.reliabilityScore && (
                  <span className={`text-xs font-bold px-2.5 py-0.5 rounded-full border ${RELIABILITY_COLOR[latestResult.reliabilityScore] ?? ""}`}>
                    신뢰도 {latestResult.reliabilityScore}
                  </span>
                )}
                <span className="text-xs text-gray-400 dark:text-dracula-comment ml-auto tabular-nums">
                  {latestResult.startDate} ~ {latestResult.endDate}
                </span>
              </div>

              {/* 신뢰도 설명 배너 */}
              {latestResult.reliabilityScore && (
                <div className={`p-2.5 rounded-lg border text-xs ${RELIABILITY_COLOR[latestResult.reliabilityScore]}`}>
                  <strong>신뢰도 {latestResult.reliabilityScore}</strong>
                  {latestResult.reliabilityScore === "A" && " — 충분한 거래 횟수와 검증 기간을 갖춘 신뢰할 수 있는 결과입니다."}
                  {latestResult.reliabilityScore === "B" && " — 전반적으로 신뢰할 수 있으나 더 긴 검증 기간이 필요합니다."}
                  {latestResult.reliabilityScore === "C" && " — 거래 횟수가 적어 통계적 신뢰도가 제한적입니다. 더 긴 기간으로 테스트하세요."}
                  {latestResult.reliabilityScore === "D" && " — 거래 횟수가 매우 적습니다. 과최적화 위험이 높습니다."}
                </div>
              )}

              {/* 성과 지표 2행 그리드 */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                <MetricCard label="총 수익률"       value={fmt(latestResult.totalReturn)}                 highlight />
                <MetricCard label="연환산 수익률"    value={fmt(latestResult.annualReturn)} />
                <MetricCard label="최대 낙폭 (MDD)"  value={fmt(latestResult.mdd)} />
                <MetricCard label="벤치마크 대비"    value={fmt(latestResult.excessReturn)} />
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                <MetricCard label="승률"       value={fmt(latestResult.winRate)} />
                <MetricCard label="손익비"     value={latestResult.profitFactor?.toFixed(2) ?? "—"} />
                <MetricCard label="거래 횟수"  value={`${latestResult.tradeCount ?? "—"}회`} />
                <MetricCard label="평균 보유"  value={`${latestResult.avgHoldingDays?.toFixed(1) ?? "—"}일`} />
              </div>

              {/* 자산 곡선 */}
              {latestResult.equityCurve.length > 0 && (
                <Card className="overflow-hidden">
                  <div className="px-4 pt-3 pb-1 flex items-center justify-between">
                    <span className="text-xs font-semibold text-gray-700 dark:text-dracula-fg">자산 곡선</span>
                    <span className="text-[10px] text-gray-400 dark:text-dracula-comment tabular-nums">
                      {latestResult.equityCurve.length}개 데이터 포인트
                    </span>
                  </div>
                  <EquityCurveChart equityCurve={latestResult.equityCurve} isDark={resolvedTheme === "dark"} />
                </Card>
              )}

              {/* 거래 내역 테이블 */}
              {latestResult.trades.length > 0 && (
                <Card className="overflow-hidden">
                  <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200 dark:border-dracula-line bg-gray-50 dark:bg-transparent">
                    <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">거래 내역</span>
                    <span className="text-xs text-gray-400 dark:text-dracula-comment">{latestResult.trades.length}건</span>
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="border-b border-gray-200 dark:border-dracula-line text-gray-400 dark:text-dracula-comment bg-gray-50/50 dark:bg-transparent">
                          {["매수일", "매도일", "매수가", "매도가", "수량", "손익", "수익률", "사유"].map(h => (
                            <th key={h} className="px-3 py-2 text-left font-medium">{h}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {latestResult.trades.map((t, i) => (
                          <tr key={i} className="border-b border-gray-100 dark:border-dracula-line/30 hover:bg-gray-50 dark:hover:bg-dracula-line/10 transition-colors">
                            <td className="px-3 py-2 text-gray-400 dark:text-dracula-comment tabular-nums">{t.entryDate}</td>
                            <td className="px-3 py-2 text-gray-400 dark:text-dracula-comment tabular-nums">{t.exitDate}</td>
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
            </>
          )}
        </main>
      </div>
    </div>
  );
}
