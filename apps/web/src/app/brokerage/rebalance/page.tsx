"use client";

import { useEffect, useState } from "react";
import { getAccessToken } from "@/services/auth";
import {
  useBrokerageAccount,
  useBrokerageBalance,
  useRebalanceTarget,
  useSaveRebalanceTarget,
  useRebalancePreview,
  useExecuteRebalance,
} from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { ApiError } from "@/services/brokerage";
import { authFetch } from "@/services/api";
import {
  Btn, DataTable, Icon, IconBtn, KV, Notice, Panel, PanelRow, Pill, Seg, TerminalPage, fmtNum, type Column,
} from "@/components/terminal";
import { TradingHaltBanner } from "@/components/brokerage/TradingHaltBanner";
import { LiveNotice, LoginRequired, NoAccount, StockSearchBox, sideClass, sideLabel, useLastRebalanceExecution, useSymbolQuotes, type StockHit } from "@/components/brokerage/shared";
import { cn } from "@/lib/utils";
import type { RebalanceExecutionResponse, RebalanceLegResponse, RebalanceTargetSource } from "@monticker/types";
import { takeRebalanceDraft } from "@/lib/rebalanceDraft";

interface WeightRow { symbol: string; name: string; weightPct: string; id?: number; }

/** /api/analytics/portfolio/optimize 응답 — weights 는 stockId 키. (analytics 페이지의 로컬 타입과 동일) */
interface OptimizationResult {
  stockIds: number[];
  weights: Record<string, number>;
  expectedReturn: number;
  expectedRisk: number;
  suggestion: string;
}

/** 표의 한 행 — 목표에 있는 종목 + 목표엔 없지만 보유 중인 종목(서버 미리보기는 이것도 매도 대상으로 본다). */
interface TableRow { symbol: string; name: string; inTarget: boolean; weightPct: string; currentPct: number | null; }

function pct(n: number) { return (n * 100).toFixed(2); }

const THRESHOLD_PRESETS = ["2.00", "3.00", "5.00"] as const;

export default function RebalancePage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [rows, setRows] = useState<WeightRow[]>([]);
  const [thresholdPct, setThresholdPct] = useState("5.00");
  const [saveError, setSaveError] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [executeError, setExecuteError] = useState<string | null>(null);
  const [lastExecution, setLastExecution] = useState<RebalanceExecutionResponse | null>(null);
  const [showPreview, setShowPreview] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [source, setSource] = useState<RebalanceTargetSource>("MANUAL");
  const [optimizing, setOptimizing] = useState(false);
  const [optimizeError, setOptimizeError] = useState<string | null>(null);
  const [optimizeInfo, setOptimizeInfo] = useState<{ expectedReturn: number; expectedRisk: number; suggestion: string } | null>(null);
  // V-M6 — 괴리 미리보기/실행은 서버에 저장된 target만 본다. rows/thresholdPct를 편집(직접
  // 수정 또는 최적화 결과 채우기)하고 저장을 누르지 않으면, 미리보기·실행은 그 편집 내용을
  // 조용히 무시하고 마지막으로 저장된 값 기준으로 동작한다 — 편집 중이라는 걸 알린다.
  const [isDirty, setIsDirty] = useState(false);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: account, isLoading: accountLoading } = useBrokerageAccount();
  const { data: balance, isError: balanceError } = useBrokerageBalance(!!account);
  const { data: target, isFetched: targetFetched } = useRebalanceTarget(!!account);
  // /analytics "리밸런싱으로 보내기"가 남긴 초안 — 저장된 목표를 먼저 읽은 뒤 편집 중 상태로만 채운다.
  const [fromAnalytics, setFromAnalytics] = useState(false);
  const saveTarget = useSaveRebalanceTarget();
  const { data: previewData, isLoading: previewLoading, isFetching: previewFetching, refetch: refetchPreview } = useRebalancePreview(false);
  const executeMutation = useExecuteRebalance();
  const { data: latestExecution, refetch: refetchLatestExecution } = useLastRebalanceExecution(!!account);
  const { toast } = useToast();

  useEffect(() => {
    if (!target) return;
    setThresholdPct(target.thresholdPct.toFixed(2));
    setRows(Object.entries(target.weights).map(([symbol, w]) => ({ symbol, name: symbol, weightPct: (w * 100).toFixed(1) })));
    setSource(target.source);
    setIsDirty(false);
  }, [target]);

  useEffect(() => {
    if (!targetFetched) return;
    const draft = takeRebalanceDraft();
    if (!draft) return;
    setRows(draft.rows.map(r => ({ symbol: r.symbol, name: r.name, id: r.stockId, weightPct: r.weightPct.toFixed(1) })));
    setSource("OPTIMIZER");
    setOptimizeInfo({ expectedReturn: draft.expectedReturn, expectedRisk: draft.expectedRisk, suggestion: draft.suggestion });
    setIsDirty(true);
    setFromAnalytics(true);
  }, [targetFetched]);

  // 미리보기를 다시 보거나 편집하면 실행 확인은 처음부터 다시 받는다.
  useEffect(() => { setConfirming(false); }, [isDirty, previewData, showPreview]);

  const holdings = balance?.holdings ?? [];
  const names = useSymbolQuotes([...rows.filter(r => r.name === r.symbol).map(r => r.symbol), ...holdings.map(h => h.symbol)], false);
  const nameOf = (symbol: string, fallback?: string) => (fallback && fallback !== symbol ? fallback : names.get(symbol)?.name ?? symbol);

  // 수동 편집은 최적화 산출물의 출처를 무효화한다 — MANUAL 로 되돌리고 최적화 요약도 지운다.
  const markManual = () => { setSource("MANUAL"); setOptimizeInfo(null); setIsDirty(true); };

  const addStock = (hit: StockHit) => {
    if (rows.some(r => r.symbol === hit.symbol)) return;
    setRows(rs => [...rs, { symbol: hit.symbol, name: hit.name, weightPct: "", id: hit.id }]);
    markManual();
  };

  const removeStock = (symbol: string) => { setRows(rs => rs.filter(r => r.symbol !== symbol)); markManual(); };
  const updateWeight = (symbol: string, weightPct: string) => {
    setRows(rs => rs.map(r => (r.symbol === symbol ? { ...r, weightPct } : r)));
    markManual();
  };
  const updateThreshold = (v: string) => { setThresholdPct(v); setIsDirty(true); };

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
      // V-L1 — 백엔드가 입력 오류를 이제 200+error 필드가 아니라 400으로 던진다.
      if (!res.ok) { const e = await res.json(); throw new Error(e.message ?? "최적화 계산에 실패했습니다."); }
      const result: OptimizationResult = await res.json();
      // V-L4 — 옵티마이저 결과에 없는 종목을 조용히 0%로 채우면, 뒤이은 저장 검증(모든 비중>0)이
      // 원인 표시 없이 막혀버린다. 생략된 종목이 있으면 미리 알린다.
      const missingIds = withIds.filter(r => !(String(r.id) in result.weights));
      if (missingIds.length > 0) {
        setOptimizeError(`다음 종목은 최적화 결과에 없어 0%로 채워졌습니다: ${missingIds.map(r => nameOf(r.symbol, r.name)).join(", ")}`);
      }
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
      setIsDirty(true);
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
      setIsDirty(false);
    } catch (e) {
      setSaveError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  const handlePreview = async () => {
    setPreviewError(null);
    setLastExecution(null);
    try {
      const r = await refetchPreview();
      if (r.error) throw r.error;
      setShowPreview(true);
    } catch (e) {
      setPreviewError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  const handleExecute = async () => {
    // 저장 안 된 편집이 있으면 실행하지 않는다(V-M6) — 버튼도 막혀 있지만 한 번 더 확인한다.
    if (isDirty) return;
    setExecuteError(null);
    setConfirming(false);
    try {
      const result = await executeMutation.mutateAsync();
      setLastExecution(result);
      setShowPreview(false);
      refetchLatestExecution();
      toast({
        type: result.status === "COMPLETED" ? "success" : "error",
        title: result.status === "COMPLETED" ? "실행 완료" : "일부 실패",
        message: `${result.legs.length}건 중 ${result.legs.filter(l => l.status === "EXECUTED").length}건 체결`,
      });
    } catch (e) {
      setExecuteError(e instanceof ApiError ? e.message : (e as Error).message);
    }
  };

  if (!isLoggedIn) return <LoginRequired title="리밸런싱" message="리밸런싱을 이용하려면 로그인이 필요합니다." />;
  if (!accountLoading && !account) return <NoAccount title="리밸런싱" message="리밸런싱을 실행하려면 먼저 증권사 계좌를 연동하세요." />;

  // 현재 비중 = 보유 평가액 / 증권사 총평가액 — 서버 미리보기(RebalanceExecutionService)와 같은 식.
  const total = balance && balance.totalEvaluated > 0 ? balance.totalEvaluated : null;
  const currentPctOf = (symbol: string) => {
    if (!total) return null;
    const h = holdings.find(x => x.symbol === symbol);
    return h ? (h.currentPrice * h.quantity / total) * 100 : 0;
  };
  const tableRows: TableRow[] = [
    ...rows.map(r => ({ symbol: r.symbol, name: nameOf(r.symbol, r.name), inTarget: true, weightPct: r.weightPct, currentPct: currentPctOf(r.symbol) })),
    ...holdings.filter(h => !rows.some(r => r.symbol === h.symbol))
      .map(h => ({ symbol: h.symbol, name: nameOf(h.symbol), inTarget: false, weightPct: "0", currentPct: currentPctOf(h.symbol) })),
  ];
  const cashPct = total && balance ? (balance.cash / total) * 100 : null;
  const th = Number(thresholdPct) || 0;
  const diffOf = (r: TableRow) => (r.currentPct == null ? null : r.currentPct - (Number(r.weightPct) || 0));
  const suggestionOf = (r: TableRow): { label: string; tone: "red" | "green" | "muted" } | null => {
    const d = diffOf(r);
    if (d == null) return null;
    if (th > 0 && d >= th) return { label: "매도", tone: "red" };
    if (th > 0 && d <= -th) return { label: "매수", tone: "green" };
    return { label: "유지", tone: "muted" };
  };
  const maxDiffRow = tableRows.reduce<TableRow | null>((best, r) => {
    const d = diffOf(r);
    if (d == null) return best;
    const bd = best ? diffOf(best) : null;
    return bd == null || Math.abs(d) > Math.abs(bd) ? r : best;
  }, null);
  const maxDiff = maxDiffRow ? diffOf(maxDiffRow) : null;
  const targetCount = tableRows.filter(r => { const s = suggestionOf(r); return s && s.label !== "유지"; }).length;

  const cols: Column<TableRow>[] = [
    { key: "name", header: "종목", cell: r => (
      <span className="flex flex-col gap-px">
        <span className="font-semibold">{r.name}</span>
        {r.name !== r.symbol && <span className="num text-2xs text-tm-muted">{r.symbol}</span>}
      </span>
    ) },
    { key: "target", header: "목표", cell: r => r.inTarget ? (
      <label className="inline-flex h-8 items-center gap-1.5 rounded-md border border-tm-line2 bg-tm-inner px-2.5">
        <span className="sr-only">{r.name} 목표 비중</span>
        <input
          className="num w-12 bg-transparent text-right text-13 text-dracula-fg outline-none"
          type="number" min={0} max={100} step={0.1} inputMode="decimal"
          value={r.weightPct}
          placeholder="0.0"
          onChange={e => updateWeight(r.symbol, e.target.value)}
        />
        <span className="text-tm-muted">%</span>
      </label>
    ) : (
      <span className="text-xs text-tm-muted">목표 없음 (0%)</span>
    ) },
    { key: "cur", header: "현재", align: "right", cell: r => <span className="num">{r.currentPct == null ? "—" : `${r.currentPct.toFixed(1)}%`}</span> },
    { key: "bar", header: "현재 vs 목표", cell: r => {
      const tgt = Number(r.weightPct) || 0;
      return (
        <div className="relative h-2.5 w-[200px] rounded-full bg-tm-inner" aria-hidden>
          {r.currentPct != null && <div className="absolute left-0 h-full rounded-full bg-tm-line2" style={{ width: `${Math.min(100, r.currentPct * 2.5)}%` }} />}
          <div className="absolute -top-[3px] h-4 w-0.5 bg-dracula-purple" style={{ left: `${Math.min(100, tgt * 2.5)}%` }} />
        </div>
      );
    } },
    { key: "diff", header: "괴리", align: "right", cell: r => {
      const d = diffOf(r);
      return <span className={cn("num", d != null && th > 0 && Math.abs(d) >= th ? "text-dracula-orange" : "text-tm-muted")}>{d == null ? "—" : `${d > 0 ? "+" : ""}${d.toFixed(1)}%p`}</span>;
    } },
    { key: "sugg", header: "제안", cell: r => { const s = suggestionOf(r); return s ? <Pill tone={s.tone}>{s.label}</Pill> : <span className="text-tm-muted">—</span>; } },
    { key: "rm", header: <span className="sr-only">삭제</span>, align: "right", cell: r => r.inTarget
      ? <IconBtn name="x" label={`${r.name} 목표에서 제거`} size={28} iconSize={14} onClick={() => removeStock(r.symbol)} />
      : null },
  ];

  const previewCols: Column<RebalanceLegResponse>[] = [
    { key: "sym", header: "종목", cell: l => <span className="font-medium">{nameOf(l.symbol)}</span> },
    { key: "side", header: "구분", cell: l => <span className={sideClass(l.side)}>{sideLabel(l.side)}</span> },
    { key: "qty", header: "수량", align: "right", cell: l => <span className="num">{fmtNum(l.quantity)}</span> },
    { key: "w", header: "비중", align: "right", cell: l => <span className="num text-tm-muted">{pct(l.currentWeight)}→{pct(l.targetWeight)}%</span> },
    // 서버 미리보기가 수량을 계산한 가격(보유 = 증권사 잔고 현재가, 신규 = 최근 1분봉) × 수량. 실행은 시장가라 실제 체결가와 다르다.
    { key: "amt", header: "예상 금액", align: "right", cell: l => (
      <span className="num" title={`추정 가격 ${fmtNum(l.estimatedPrice)}원 (${l.priceSource === "BROKER_BALANCE" ? "증권사 잔고 현재가" : "최근 1분봉 종가"}) · 수수료 ${fmtNum(l.estimatedFee)}원${l.estimatedTax > 0 ? ` · 거래세 ${fmtNum(l.estimatedTax)}원` : ""}`}>
        {l.estimatedAmount > 0 ? fmtNum(l.estimatedAmount) : "—"}
      </span>
    ) },
  ];

  const legs = showPreview && previewData ? previewData.legs : null;
  // 거래비용은 대상이 있을 때만 보여 준다(대상 0건이면 0원이 아니라 "—")
  const costPreview = legs && legs.length > 0 ? previewData : null;
  const presetValue = (THRESHOLD_PRESETS as readonly string[]).includes(Number(thresholdPct).toFixed(2)) ? Number(thresholdPct).toFixed(2) : "custom";

  return (
    <TerminalPage
      title="리밸런싱"
      crumb="실전투자 · 목표 비중과 괴리 확인 후 직접 실행"
      stats={[
        { label: "최대 괴리", value: maxDiffRow && maxDiff != null ? `${maxDiffRow.name} ${maxDiff > 0 ? "+" : ""}${maxDiff.toFixed(1)}%p` : "—", tone: maxDiff != null && th > 0 && Math.abs(maxDiff) >= th ? "text-dracula-orange" : undefined },
        { label: "대상 종목", value: legs ? `${legs.length}개` : total ? `${targetCount}개` : "—" },
        { label: "임계값", value: `${Number(thresholdPct) || 0}%p` },
        { label: "마지막 실행", value: (lastExecution ?? latestExecution) ? new Date((lastExecution ?? latestExecution)!.requestedAt).toLocaleDateString("ko-KR", { month: "2-digit", day: "2-digit" }) : "—" },
      ]}
      account={{ kind: "live" }}
    >
      <LiveNotice />
      <TradingHaltBanner enabled={!!account} note="중단 중에는 리밸런싱을 실행할 수 없습니다. 목표 비중 저장과 미리보기는 가능합니다." />

      <PanelRow>
        {/* ── 목표 비중 ── */}
        <Panel
          tabs={["목표 비중"]}
          actions={[]}
          right={source === "OPTIMIZER" ? <Pill tone="purple">최적화됨</Pill> : undefined}
          className="flex-[999_1_620px]"
          bodyClassName="px-1.5 pb-2.5 pt-1"
        >
          <div className="flex flex-wrap items-center gap-3 px-1.5 py-2">
            <span className="text-13 text-tm-soft">실행 임계값</span>
            <Seg
              size="lg"
              options={THRESHOLD_PRESETS.map(v => ({ value: v as string, label: `${Number(v)}%p` }))}
              value={presetValue}
              onChange={updateThreshold}
            />
            <label className="inline-flex h-8 items-center gap-1.5 rounded-md border border-tm-line2 bg-tm-inner px-2.5">
              <span className="sr-only">실행 임계값 직접 입력</span>
              <input
                className="num w-12 bg-transparent text-right text-13 text-dracula-fg outline-none"
                type="number" min={0} max={100} step={0.1}
                value={thresholdPct}
                onChange={e => updateThreshold(e.target.value)}
              />
              <span className="text-tm-muted">%p</span>
            </label>
            <span className="text-xs text-tm-muted">
              괴리가 이 값 이상인 종목만 대상 · 합계{" "}
              <b className={cn("num", totalWeightPct > 100 ? "text-[#ff8a8a]" : totalWeightPct === 100 ? "text-dracula-green" : "text-dracula-fg")}>{totalWeightPct.toFixed(1)}%</b>
            </span>
            <span className="ml-auto flex flex-wrap gap-1.5">
              <Btn kind="ghost" size="sm" icon="zap" onClick={handleOptimize} disabled={rows.length < 2 || optimizing} className="h-[34px]">
                {optimizing ? "계산 중..." : "최적 비중 채우기"}
              </Btn>
              <Btn kind="ghost" size="sm" onClick={handleSave} disabled={!isSaveValid || saveTarget.isPending} className="h-[34px]">
                {saveTarget.isPending ? "저장 중..." : "목표 비중 저장"}
              </Btn>
            </span>
          </div>

          <div className="px-1.5 pb-2"><StockSearchBox onSelect={addStock} label="종목 추가" placeholder="종목 추가 — 종목명 또는 코드 검색" /></div>

          {tableRows.length === 0 ? (
            <p className="px-3 py-8 text-center text-13 text-tm-muted">종목을 추가해 목표 비중을 설정하세요.</p>
          ) : (
            <DataTable columns={cols} rows={tableRows} rowKey={r => r.symbol} minWidth={760} dense={false} />
          )}
          {total && cashPct != null && (
            <div className="flex justify-between px-3 py-2 text-xs text-tm-muted">
              <span>현금 (목표 합계가 100%보다 작으면 나머지는 현금으로 남습니다)</span>
              <span className="num">{cashPct.toFixed(1)}%</span>
            </div>
          )}
          {balanceError && <p className="px-3 text-xs text-dracula-orange">잔고를 확인할 수 없어 현재 비중을 표시하지 않습니다.</p>}

          <div className="flex flex-col gap-2 px-1.5 pt-1">
            {fromAnalytics && isDirty && (
              <Notice tone="warn">포트폴리오 분석 화면의 분석 결과 비중을 초안으로 채웠습니다. 아직 저장되지 않았고 주문도 나가지 않았습니다 — 비중을 확인한 뒤 저장하고, 괴리 미리보기와 실행 확인을 거쳐야 실제 주문이 제출됩니다.</Notice>
            )}
            {optimizeError && <Notice tone="danger">{optimizeError}</Notice>}
            {optimizeInfo && optimizeInfo.suggestion && <Notice tone="info">{optimizeInfo.suggestion}</Notice>}
            {saveError && <Notice tone="danger">{saveError}</Notice>}
            {isDirty && (
              <span className="text-xs text-dracula-orange">저장되지 않은 변경사항이 있습니다 — 저장해야 오른쪽 미리보기/실행에 반영됩니다.</span>
            )}
          </div>
        </Panel>

        {/* ── 실행 미리보기 ── */}
        <Panel tabs={["실행 미리보기"]} actions={[]} closable={false} className="flex-[1_1_340px] self-start">
          {!target ? (
            <p className="text-13 text-tm-muted">목표 비중을 저장하면 현재 보유와의 괴리를 미리보고 실행할 수 있습니다.</p>
          ) : (
            <>
              {/* V-M6 — 미리보기/실행 둘 다 위 편집 상태가 아니라 마지막으로 저장된 target을
                  대상으로 동작한다. 편집 중인데 모르고 실행하면 의도하지 않은 비중으로 실제
                  주문이 나갈 수 있어, 편집 중엔 눈에 띄게 알리고 실행 자체를 막는다. */}
              {isDirty && (
                <Notice tone="warn">
                  편집 중인 내용이 아직 저장되지 않았습니다. 아래 미리보기/실행은 저장된 이전 목표 비중을 기준으로 동작합니다 — 지금 편집한 비중으로 실행하려면 먼저 저장하세요.
                </Notice>
              )}

              <Btn kind="soft" full icon="refresh" onClick={handlePreview} disabled={previewLoading || previewFetching}>
                {previewLoading || previewFetching ? "계산 중..." : "괴리 미리보기"}
              </Btn>
              {previewError && <Notice tone="danger">{previewError}</Notice>}

              {legs && (legs.length === 0 ? (
                <p className="py-2 text-center text-xs text-tm-muted">임계값을 넘는 괴리가 없습니다 — 리밸런싱이 필요하지 않습니다.</p>
              ) : (
                <DataTable columns={previewCols} rows={legs} rowKey={l => l.symbol} minWidth={360} />
              ))}

              <div className="flex flex-col gap-2">
                <KV k="기대 수익률 (연)" v={optimizeInfo ? `${pct(optimizeInfo.expectedReturn)}%` : "—"} valueClassName={optimizeInfo ? "text-up" : "text-tm-muted"} />
                <KV k="예상 위험" v={optimizeInfo ? `${pct(optimizeInfo.expectedRisk)}%` : "—"} valueClassName={optimizeInfo ? undefined : "text-tm-muted"} />
                <KV
                  k="예상 거래비용"
                  v={costPreview ? `${fmtNum(costPreview.estimatedCost)}원` : "—"}
                  valueClassName={costPreview ? undefined : "text-tm-muted"}
                />
                <KV
                  k="매수 예상 금액"
                  v={costPreview ? `${fmtNum(costPreview.estimatedBuyAmount)}원` : "—"}
                  valueClassName={costPreview ? undefined : "text-tm-muted"}
                />
                {costPreview && costPreview.estimatedNewBuyAmount > 0 && (
                  <KV k="그중 신규 종목 매수" v={`${fmtNum(costPreview.estimatedNewBuyAmount)}원`} />
                )}
                {costPreview && (
                  <span className="text-2xs leading-relaxed text-tm-muted">
                    추정치입니다 — 수수료 {(costPreview.costModel.feeRate * 100).toFixed(3)}%·매도 거래세 {(costPreview.costModel.sellTaxRate * 100).toFixed(2)}%를
                    미리보기 가격에 적용했습니다. 실행은 시장가라 체결가가 다르고, 증권사·계좌별 실제 수수료율과도 다를 수 있습니다.
                  </span>
                )}
                {optimizeInfo && <span className="text-2xs leading-relaxed text-tm-muted">기대 수익률·위험은 최적 비중 계산 결과(과거 데이터 기반 참고용)이며 투자자문이 아닙니다.</span>}
              </div>

              <Notice tone="info">
                실행은 매도 후 매수 순서로 진행되며, 각 주문은 기존 리스크 한도를 그대로 적용받습니다. 실행 전에 한 번 더 확인합니다.
              </Notice>

              {executeError && <Notice tone="danger">{executeError}</Notice>}

              {legs && legs.length > 0 && (
                confirming && !isDirty ? (
                  <div role="region" aria-label="리밸런싱 실행 확인" className="flex flex-col gap-2.5 rounded-xl border-[1.5px] border-dracula-orange bg-tm-inner p-3.5">
                    <span className="flex items-center gap-2 font-bold"><Icon name="alert" size={18} className="text-dracula-orange" />실제 주문 {legs.length}건을 제출할까요?</span>
                    <span className="text-xs text-tm-muted">서버가 실행 시점의 잔고로 다시 계산하므로 수량은 미리보기와 다를 수 있습니다.</span>
                    <div className="flex gap-2">
                      <Btn kind="ghost" className="flex-1" onClick={() => setConfirming(false)} disabled={executeMutation.isPending}>취소</Btn>
                      <Btn kind="warn" className="flex-[2]" onClick={handleExecute} disabled={executeMutation.isPending || isDirty}>
                        {executeMutation.isPending ? "실행 중..." : `주문 ${legs.length}건 실행`}
                      </Btn>
                    </div>
                  </div>
                ) : (
                  <Btn kind="warn" size="lg" full onClick={() => setConfirming(true)} disabled={executeMutation.isPending || isDirty}>
                    {executeMutation.isPending ? "실행 중..." : isDirty ? "저장되지 않은 변경사항이 있습니다" : `주문 ${legs.length}건 직접 실행`}
                  </Btn>
                )
              )}
            </>
          )}

          {lastExecution && (
            <div className="flex flex-col gap-1.5">
              <span className="text-13 font-semibold">실행 결과</span>
              {lastExecution.legs.map(leg => (
                <div key={leg.symbol} className="flex flex-wrap items-center gap-2 text-xs">
                  {leg.status === "EXECUTED"
                    ? <Icon name="check" size={16} strokeWidth={2.4} className="text-dracula-green" />
                    : leg.status === "UNKNOWN"
                      ? <Icon name="clock" size={16} className="text-dracula-yellow" />
                      : <Icon name="x" size={16} strokeWidth={2.4} className="text-[#ff8a8a]" />}
                  <span className={sideClass(leg.side)}>{sideLabel(leg.side)}</span>
                  <span className="font-semibold">{nameOf(leg.symbol)}</span>
                  <span className="num text-tm-muted">{fmtNum(leg.quantity)}주</span>
                  {leg.status === "UNKNOWN"
                    ? <span className="ml-auto text-dracula-yellow">체결 여부 확인 중 — 주문 내역에서 확인</span>
                    : leg.failReason && <span className="ml-auto text-[#ff8a8a]">{leg.failReason}</span>}
                </div>
              ))}
            </div>
          )}
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
