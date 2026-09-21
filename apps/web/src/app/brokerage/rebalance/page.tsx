"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ShieldWarning, CheckCircle, XCircle, ArrowsClockwise, Sparkle } from "@phosphor-icons/react";
import { getAccessToken } from "@/services/auth";
import {
  useBrokerageAccount,
  useRebalanceTarget,
  useSaveRebalanceTarget,
  useRebalancePreview,
  useExecuteRebalance,
} from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { ApiError } from "@/services/brokerage";
import { authFetch } from "@/services/api";
import { Card } from "@/components/ui/Card";
import type { RebalanceExecutionResponse, RebalanceTargetSource } from "@monticker/types";

interface StockHit { id: number; symbol: string; name: string; }
interface WeightRow { symbol: string; name: string; weightPct: string; id?: number; }

/** /api/analytics/portfolio/optimize 응답 — weights 는 stockId 키. (analytics 페이지의 로컬 타입과 동일) */
interface OptimizationResult {
  stockIds: number[];
  weights: Record<string, number>;
  expectedReturn: number;
  expectedRisk: number;
  suggestion: string;
  error: string | null;
}

function pct(n: number) { return (n * 100).toFixed(2); }

export default function RebalancePage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [rows, setRows] = useState<WeightRow[]>([]);
  const [thresholdPct, setThresholdPct] = useState("5.00");
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<StockHit[]>([]);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [executeError, setExecuteError] = useState<string | null>(null);
  const [lastExecution, setLastExecution] = useState<RebalanceExecutionResponse | null>(null);
  const [showPreview, setShowPreview] = useState(false);
  const [source, setSource] = useState<RebalanceTargetSource>("MANUAL");
  const [optimizing, setOptimizing] = useState(false);
  const [optimizeError, setOptimizeError] = useState<string | null>(null);
  const [optimizeInfo, setOptimizeInfo] = useState<{ expectedReturn: number; expectedRisk: number; suggestion: string } | null>(null);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: account, isLoading: accountLoading } = useBrokerageAccount();
  const { data: target } = useRebalanceTarget(!!account);
  const saveTarget = useSaveRebalanceTarget();
  const { data: previewData, isLoading: previewLoading, refetch: refetchPreview } = useRebalancePreview(false);
  const executeMutation = useExecuteRebalance();
  const { toast } = useToast();

  useEffect(() => {
    if (!target) return;
    setThresholdPct(target.thresholdPct.toFixed(2));
    setRows(Object.entries(target.weights).map(([symbol, w]) => ({ symbol, name: symbol, weightPct: (w * 100).toFixed(1) })));
    setSource(target.source);
  }, [target]);

  // 수동 편집은 최적화 산출물의 출처를 무효화한다 — MANUAL 로 되돌리고 최적화 요약도 지운다.
  const markManual = () => { setSource("MANUAL"); setOptimizeInfo(null); };

  useEffect(() => {
    if (searchQuery.length < 1) { setSearchResults([]); return; }
    const timer = setTimeout(async () => {
      const r = await fetch(`/api/stocks/search?query=${encodeURIComponent(searchQuery)}`);
      if (r.ok) setSearchResults((await r.json()).slice(0, 6));
    }, 200);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  const addStock = (hit: StockHit) => {
    if (rows.some(r => r.symbol === hit.symbol)) { setSearchQuery(""); setSearchResults([]); return; }
    setRows(rs => [...rs, { symbol: hit.symbol, name: hit.name, weightPct: "", id: hit.id }]);
    setSearchQuery("");
    setSearchResults([]);
    markManual();
  };

  const removeStock = (symbol: string) => { setRows(rs => rs.filter(r => r.symbol !== symbol)); markManual(); };
  const updateWeight = (symbol: string, weightPct: string) => {
    setRows(rs => rs.map(r => (r.symbol === symbol ? { ...r, weightPct } : r)));
    markManual();
  };

  /** 저장된 목표에서 복원한 행은 id 가 없다 — 최적화 호출에 필요한 stockId 를 검색 API 로 보강한다. */
  const ensureIds = async (): Promise<Array<WeightRow & { id: number }>> =>
    Promise.all(rows.map(async r => {
      if (r.id != null) return { ...r, id: r.id };
      const res = await fetch(`/api/stocks/search?query=${encodeURIComponent(r.symbol)}`);
      const hits: StockHit[] = res.ok ? await res.json() : [];
      const hit = hits.find(h => h.symbol === r.symbol);
      if (!hit) throw new Error(`종목 ID를 찾을 수 없습니다: ${r.symbol}`);
      return { ...r, id: hit.id };
    }));

  const handleOptimize = async () => {
    setOptimizeError(null);
    if (rows.length < 2) { setOptimizeError("최적 비중을 계산하려면 종목이 2개 이상이어야 합니다."); return; }
    setOptimizing(true);
    try {
      const withIds = await ensureIds();
      const params = new URLSearchParams();
      withIds.forEach(r => params.append("stockIds", String(r.id)));
      const res = await authFetch(`/api/analytics/portfolio/optimize?${params}`);
      if (!res.ok) throw new Error("최적화 계산에 실패했습니다.");
      const result: OptimizationResult = await res.json();
      if (result.error) throw new Error(result.error);
      // 각 비중을 소수 1자리로 반올림하면 합이 100을 살짝 넘어(예: 100.1%) 저장이 막힐 수 있다.
      // 초과분은 가장 큰 비중에서 덜어 합계를 100% 이하로 맞춘다.
      const rounded = withIds.map(r => Math.round((result.weights[String(r.id)] ?? 0) * 1000) / 10);
      const overflow = rounded.reduce((a, b) => a + b, 0) - 100;
      if (overflow > 0) {
        const maxIdx = rounded.indexOf(Math.max(...rounded));
        rounded[maxIdx] = Math.round((rounded[maxIdx] - overflow) * 10) / 10;
      }
      setRows(withIds.map((r, i) => ({
        symbol: r.symbol,
        name: r.name,
        id: r.id,
        weightPct: rounded[i].toFixed(1),
      })));
      setOptimizeInfo({ expectedReturn: result.expectedReturn, expectedRisk: result.expectedRisk, suggestion: result.suggestion });
      setSource("OPTIMIZER");
    } catch (e) {
      setOptimizeError(e instanceof ApiError ? e.message : (e as Error).message);
    } finally {
      setOptimizing(false);
    }
  };

  const totalWeightPct = rows.reduce((sum, r) => sum + (Number(r.weightPct) || 0), 0);
  const isSaveValid = rows.length > 0 && rows.every(r => Number(r.weightPct) > 0) && totalWeightPct <= 100 &&
    Number(thresholdPct) > 0 && Number(thresholdPct) <= 100;

  const handleSave = async () => {
    setSaveError(null);
    try {
      await saveTarget.mutateAsync({
        weights: Object.fromEntries(rows.map(r => [r.symbol, Number(r.weightPct) / 100])),
        thresholdPct: Number(thresholdPct),
        source,
      });
      toast({ type: "success", title: "저장 완료", message: "목표 비중이 저장되었습니다." });
      setShowPreview(false);
      setLastExecution(null);
    } catch (e) {
      setSaveError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  const handlePreview = async () => {
    setPreviewError(null);
    setLastExecution(null);
    try {
      await refetchPreview();
      setShowPreview(true);
    } catch (e) {
      setPreviewError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  const handleExecute = async () => {
    setExecuteError(null);
    try {
      const result = await executeMutation.mutateAsync();
      setLastExecution(result);
      setShowPreview(false);
      toast({
        type: result.status === "COMPLETED" ? "success" : "error",
        title: result.status === "COMPLETED" ? "실행 완료" : "일부 실패",
        message: `${result.legs.length}건 중 ${result.legs.filter(l => l.status === "EXECUTED").length}건 체결`,
      });
    } catch (e) {
      setExecuteError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  if (!isLoggedIn) return (
    <div className="max-w-3xl mx-auto p-6 text-center py-20">
      <p className="text-gray-500 dark:text-dracula-comment mb-4">리밸런싱을 이용하려면 로그인이 필요합니다.</p>
      <Link href="/login" className="inline-block bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg text-white px-6 py-2 rounded-lg font-medium hover:opacity-90 active:scale-[0.98] transition-all duration-150">로그인</Link>
    </div>
  );

  if (!accountLoading && !account) return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 text-center">
      <Card className="p-6">
        <p className="text-gray-900 dark:text-dracula-fg font-semibold mb-1">연동된 계좌가 없습니다</p>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mb-4">리밸런싱을 실행하려면 먼저 증권사 계좌를 연동하세요.</p>
        <Link href="/brokerage/connect" className="inline-block px-4 py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-sm font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150">
          계좌 연동하기
        </Link>
      </Card>
    </div>
  );

  return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-8">
        <h1 className="text-xl font-bold text-gray-900 dark:text-dracula-fg">리밸런싱</h1>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">목표 비중을 저장하고, 현재 보유와의 괴리를 확인한 뒤 직접 실행합니다</p>
      </div>

      {/* 목표 비중 설정 */}
      <Card className="p-5" outerClassName="mb-6">
        <div className="flex items-center gap-2 mb-3">
          <h2 className="text-sm font-bold text-gray-900 dark:text-dracula-fg">목표 비중</h2>
          {source === "OPTIMIZER" && (
            <span className="inline-flex items-center gap-1 rounded-full bg-dracula-purple/10 text-dracula-purple text-[10px] font-semibold px-2 py-0.5">
              <Sparkle size={10} weight="fill" aria-hidden /> 최적화됨
            </span>
          )}
        </div>

        <div className="relative mb-4">
          <input
            type="text"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="종목 추가 — 종목명 또는 코드 검색"
            className="w-full rounded-lg border px-4 py-2.5 text-sm border-gray-300 bg-white text-gray-900 placeholder-gray-400 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg dark:placeholder-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple transition-all duration-150"
          />
          {searchResults.length > 0 && (
            <div className="absolute z-10 mt-1 w-full rounded-lg border border-gray-200 dark:border-dracula-line bg-white dark:bg-dracula-surface shadow-lg overflow-hidden">
              {searchResults.map(r => (
                <button key={r.id} onClick={() => addStock(r)} className="w-full text-left px-4 py-2.5 text-sm hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors">
                  <span className="font-medium text-gray-900 dark:text-dracula-fg">{r.name}</span>
                  <span className="text-xs text-gray-500 dark:text-dracula-comment ml-2">{r.symbol}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        {rows.length === 0 ? (
          <p className="text-xs text-gray-500 dark:text-dracula-comment text-center py-6">종목을 추가해 목표 비중을 설정하세요.</p>
        ) : (
          <div className="space-y-2 mb-4">
            {rows.map(row => (
              <div key={row.symbol} className="flex items-center gap-2">
                <div className="flex-1 min-w-0">
                  <span className="text-sm font-medium text-gray-900 dark:text-dracula-fg">{row.symbol}</span>
                </div>
                <input
                  type="number" min={0} max={100} step={0.1} value={row.weightPct}
                  onChange={e => updateWeight(row.symbol, e.target.value)}
                  placeholder="0.0"
                  className="w-20 rounded-lg border px-2 py-1.5 text-sm text-right font-mono border-gray-300 bg-white text-gray-900 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple transition-all duration-150"
                />
                <span className="text-xs text-gray-500 dark:text-dracula-comment">%</span>
                <button onClick={() => removeStock(row.symbol)} className="text-gray-400 hover:text-dracula-red transition-colors">
                  <XCircle size={16} weight="bold" aria-hidden />
                </button>
              </div>
            ))}
            <div className="flex justify-between text-xs pt-2 border-t border-gray-200 dark:border-dracula-line">
              <span className="text-gray-500 dark:text-dracula-comment">합계</span>
              <span className={`font-mono font-semibold ${totalWeightPct > 100 ? "text-dracula-red" : "text-gray-900 dark:text-dracula-fg"}`}>{totalWeightPct.toFixed(1)}%</span>
            </div>
          </div>
        )}

        <div className="mb-4">
          <label className="text-xs text-gray-500 dark:text-dracula-comment mb-1 block">실행 임계값 — 괴리가 이 값 이상인 종목만 리밸런싱 대상이 됩니다</label>
          <div className="flex items-center gap-2">
            <input
              type="number" min={0} max={100} step={0.1} value={thresholdPct}
              onChange={e => setThresholdPct(e.target.value)}
              className="w-24 rounded-lg border px-3 py-2 text-sm text-right font-mono border-gray-300 bg-white text-gray-900 dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple transition-all duration-150"
            />
            <span className="text-xs text-gray-500 dark:text-dracula-comment">%p</span>
          </div>
        </div>

        {/* 최적화 — /api/analytics/portfolio/optimize 결과를 목표 비중에 채운다 */}
        <button onClick={handleOptimize} disabled={rows.length < 2 || optimizing}
          className="w-full py-2.5 rounded-xl font-bold text-sm border border-dracula-purple/40 text-dracula-purple hover:bg-dracula-purple/10 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100 mb-3 flex items-center justify-center gap-2">
          <Sparkle size={16} weight="bold" aria-hidden />
          {optimizing ? "계산 중..." : "최적 비중 계산해 채우기"}
        </button>

        {optimizeError && <p className="text-xs text-dracula-red mb-3">{optimizeError}</p>}

        {optimizeInfo && (
          <div className="rounded-lg bg-dracula-purple/5 border border-dracula-purple/20 p-3 mb-4 space-y-2">
            <div className="flex justify-between text-xs">
              <span className="text-gray-500 dark:text-dracula-comment">기대 수익률 (연환산)</span>
              <span className="font-mono font-semibold text-dracula-green">{pct(optimizeInfo.expectedReturn)}%</span>
            </div>
            <div className="flex justify-between text-xs">
              <span className="text-gray-500 dark:text-dracula-comment">예상 위험 (변동성)</span>
              <span className="font-mono font-semibold text-gray-900 dark:text-dracula-fg">{pct(optimizeInfo.expectedRisk)}%</span>
            </div>
            {optimizeInfo.suggestion && (
              <p className="text-xs text-dracula-purple leading-relaxed pt-1 border-t border-dracula-purple/20">{optimizeInfo.suggestion}</p>
            )}
            <p className="text-[10px] text-gray-500 dark:text-dracula-comment leading-relaxed">
              최적화 결과는 과거 데이터 기반 참고용이며 투자자문이 아닙니다. 저장 전 비중을 검토하세요.
            </p>
          </div>
        )}

        {saveError && (
          <div className="flex items-start gap-2 rounded-lg border border-dracula-red/40 bg-dracula-red/10 p-3 mb-4">
            <ShieldWarning size={18} weight="bold" className="text-dracula-red shrink-0 mt-0.5" aria-hidden />
            <p className="text-xs text-dracula-red">{saveError}</p>
          </div>
        )}

        <button onClick={handleSave} disabled={!isSaveValid || saveTarget.isPending}
          className="w-full py-2.5 rounded-xl font-bold text-sm text-white bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100">
          {saveTarget.isPending ? "저장 중..." : "목표 비중 저장"}
        </button>
      </Card>

      {/* 미리보기 / 실행 */}
      {target && (
        <Card className="p-5" outerClassName="mb-6">
          <h2 className="text-sm font-bold text-gray-900 dark:text-dracula-fg mb-3">실행</h2>

          <button onClick={handlePreview} disabled={previewLoading}
            className="w-full py-2.5 rounded-xl font-bold text-sm border border-gray-300 dark:border-dracula-line text-gray-700 dark:text-dracula-fg hover:bg-gray-50 dark:hover:bg-dracula-line/30 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 mb-3 flex items-center justify-center gap-2">
            <ArrowsClockwise size={16} weight="bold" aria-hidden />
            {previewLoading ? "계산 중..." : "괴리 미리보기"}
          </button>

          {previewError && <p className="text-xs text-dracula-red mb-3">{previewError}</p>}

          {showPreview && previewData && (
            previewData.legs.length === 0 ? (
              <p className="text-xs text-gray-500 dark:text-dracula-comment text-center py-4">임계값을 넘는 괴리가 없습니다 — 리밸런싱이 필요하지 않습니다.</p>
            ) : (
              <>
                <div className="space-y-1.5 mb-4">
                  {previewData.legs.map(leg => (
                    <div key={leg.symbol} className="flex items-center justify-between text-xs py-1.5 border-b border-gray-100 dark:border-dracula-line/50 last:border-0">
                      <div className="flex items-center gap-2">
                        <span className={`font-medium ${leg.side === "BUY" ? "text-dracula-red" : "text-dracula-cyan"}`}>{leg.side === "BUY" ? "매수" : "매도"}</span>
                        <span className="text-gray-900 dark:text-dracula-fg font-semibold">{leg.symbol}</span>
                        <span className="text-gray-500 dark:text-dracula-comment">{leg.quantity}주</span>
                      </div>
                      <span className="font-mono text-gray-500 dark:text-dracula-comment">
                        {pct(leg.currentWeight)}% → {pct(leg.targetWeight)}%
                      </span>
                    </div>
                  ))}
                </div>

                {executeError && (
                  <div className="flex items-start gap-2 rounded-lg border border-dracula-red/40 bg-dracula-red/10 p-3 mb-4">
                    <ShieldWarning size={18} weight="bold" className="text-dracula-red shrink-0 mt-0.5" aria-hidden />
                    <p className="text-xs text-dracula-red">{executeError}</p>
                  </div>
                )}

                <button onClick={handleExecute} disabled={executeMutation.isPending}
                  className="w-full py-3 rounded-xl font-bold text-sm text-white bg-dracula-orange active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100">
                  {executeMutation.isPending ? "실행 중..." : `${previewData.legs.length}건 실행 — 실제 주문이 제출됩니다`}
                </button>
              </>
            )
          )}
        </Card>
      )}

      {lastExecution && (
        <Card className="p-5" outerClassName="mb-6">
          <h2 className="text-sm font-bold text-gray-900 dark:text-dracula-fg mb-3">실행 결과</h2>
          <div className="space-y-1.5">
            {lastExecution.legs.map(leg => (
              <div key={leg.symbol} className="flex items-center gap-2 text-xs py-1.5">
                {leg.status === "EXECUTED"
                  ? <CheckCircle size={16} weight="bold" className="text-dracula-green shrink-0" aria-hidden />
                  : <XCircle size={16} weight="bold" className="text-dracula-red shrink-0" aria-hidden />}
                <span className={leg.side === "BUY" ? "text-dracula-red" : "text-dracula-cyan"}>{leg.side === "BUY" ? "매수" : "매도"}</span>
                <span className="text-gray-900 dark:text-dracula-fg font-semibold">{leg.symbol}</span>
                <span className="text-gray-500 dark:text-dracula-comment">{leg.quantity}주</span>
                {leg.failReason && <span className="text-dracula-red ml-auto">{leg.failReason}</span>}
              </div>
            ))}
          </div>
        </Card>
      )}

      <p className="text-xs text-gray-500 dark:text-dracula-comment text-center mt-8">
        비중의 합이 100%보다 작으면 나머지는 현금으로 남습니다. 실행은 매도 후 매수 순서로 진행되며, 각 주문은 기존 리스크 한도를 그대로 적용받습니다.
      </p>
    </div>
  );
}
