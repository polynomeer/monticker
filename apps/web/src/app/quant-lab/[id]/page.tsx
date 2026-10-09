"use client";

import { useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { RuleSet, QuantBacktestResult, ForwardTestResult } from "@monticker/types";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import { getForwardTestStatus, startForwardTest, stopForwardTest } from "@/services/forwardTest";
import { useForwardTestSignalsWs } from "@/hooks/useForwardTestSignalsWs";
import { getScreenerQuotes } from "@/services/screener";
import { StockPicker } from "@/components/quant/StockPicker";
import { shareStrategy } from "@/services/strategyMarket";
import {
  AutoGrid, Btn, BtnLink, Checkbox, DataTable, Field, KV, Legend, LineChart, Notice, Panel, PanelCol, PanelRow,
  Pill, PreviewTag, Stat, TerminalPage, fmtNum, fmtPct, type Column,
} from "@/components/terminal";
import { MonthlyHeatmap, TradeHistogram, fmtMatch, fmtMdd, matchTone, rulesetStatus } from "@/components/quant/parts";
import { defaultBacktestRange } from "@/lib/backtestRange";

type BacktestResult = QuantBacktestResult;
type Trade = BacktestResult["trades"][number];

const EXIT_REASON_LABEL: Record<string, { label: string; tone: "green" | "red" | "muted" }> = {
  SIGNAL: { label: "청산 신호", tone: "muted" },
  END: { label: "기간 종료", tone: "muted" },
  TAKE_PROFIT: { label: "익절", tone: "green" },
  STOP_LOSS: { label: "손절", tone: "red" },
  MAX_HOLD: { label: "최대 보유", tone: "muted" },
  TRAILING_STOP: { label: "트레일링", tone: "red" },
};

const SIGNAL_DIRECTION_LABEL: Record<string, string> = { BUY: "매수", SELL: "매도" };

const RELIABILITY_TONE: Record<string, "green" | "purple" | "orange" | "red"> = { A: "green", B: "purple", C: "orange", D: "red" };
const RELIABILITY_TEXT: Record<string, string> = {
  A: "충분한 거래 횟수와 검증 기간을 갖춘 신뢰할 수 있는 결과입니다.",
  B: "전반적으로 신뢰할 수 있으나 더 긴 검증 기간이 필요합니다.",
  C: "거래 횟수가 적어 통계적 신뢰도가 제한적입니다. 더 긴 기간으로 테스트하세요.",
  D: "거래 횟수가 매우 적습니다. 과최적화 위험이 높습니다.",
};

function won(n: number) {
  return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 });
}

function dateLabels(dates: string[]): [number, string][] {
  if (dates.length < 2) return [];
  return [0, 0.33, 0.66, 0.97].map((f) => [f, dates[Math.min(dates.length - 1, Math.round(f * (dates.length - 1)))].slice(0, 7).replace("-", ".")]);
}

function weeksSince(iso: string) {
  return Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / (7 * 86400_000)));
}

export default function QuantLabDetailPage() {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const { toast } = useToast();

  // 종목은 고르기 전까지 비워 둔다 — 예전 기본값 1은 화면엔 빈칸인데 실행하면 그 id로 돌았다(로컬엔 없는 종목)
  const [stockId, setStockId] = useState<number | null>(null);
  const [{ startDate: defaultStart, endDate: defaultEnd }] = useState(() => defaultBacktestRange(730));
  const [startDate, setStartDate] = useState(defaultStart);
  const [endDate, setEndDate] = useState(defaultEnd);
  const [capital, setCapital] = useState(10_000_000);
  const [fwStockId, setFwStockId] = useState<number | null>(null);
  const [fwCapital, setFwCapital] = useState(10_000_000);
  const [chartTab, setChartTab] = useState("equity");
  const [shareDesc, setShareDesc] = useState("");
  const [sharePrice, setSharePrice] = useState(0);
  const [shareAck, setShareAck] = useState(false);

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
      // 상태가 초안 → 백테스트 완료로 바뀐다 — 이 키를 빼먹어 새로고침 전까지 포워드 테스트·마켓 공유가 "백테스트를 먼저"로 막혀 있었다
      qc.invalidateQueries({ queryKey: ["quant", "ruleset", id] });
      toast({ type: "success", title: "백테스트 완료", message: "결과가 저장되었습니다." });
    },
    onError: (e: Error) => toast({ type: "error", title: "백테스트 실패", message: e.message }),
  });

  const { data: forwardTest } = useQuery<ForwardTestResult | null>({
    queryKey: ["quant", "forward-test", id],
    queryFn: () => getForwardTestStatus(id),
  });

  const startFwMutation = useMutation({
    mutationFn: () => startForwardTest(id, fwStockId!, fwCapital),
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

  const latestResult = results[0];
  const { data: stockQuote } = useQuery({
    queryKey: ["screener", "quotes", latestResult?.stockId],
    queryFn: () => getScreenerQuotes([latestResult!.stockId]),
    enabled: !!latestResult,
    staleTime: 5 * 60_000,
  });
  const stockName = stockQuote?.[0]?.name ?? (latestResult ? `#${latestResult.stockId}` : "—");

  const curve = useMemo(() => latestResult?.equityCurve ?? [], [latestResult]);

  const tradeCols: Column<Trade>[] = [
    { key: "in", header: "진입", cell: (t) => <span className="num text-tm-muted">{t.entryDate}</span> },
    { key: "out", header: "청산", cell: (t) => <span className="num text-tm-muted">{t.exitDate}</span> },
    { key: "stock", header: "종목", cell: () => stockName },
    { key: "ip", header: "진입가", align: "right", cell: (t) => <span className="num">{won(t.entryPrice)}</span> },
    { key: "op", header: "청산가", align: "right", cell: (t) => <span className="num">{won(t.exitPrice)}</span> },
    { key: "qty", header: "수량", align: "right", cell: (t) => <span className="num text-tm-muted">{fmtNum(t.quantity)}</span> },
    { key: "pnl", header: "손익", align: "right", cell: (t) => <span className={`num ${t.pnl >= 0 ? "text-up" : "text-down"}`}>{t.pnl >= 0 ? "+" : ""}{won(t.pnl)}</span> },
    { key: "pct", header: "수익", align: "right", cell: (t) => <span className={`num ${t.pnlPct >= 0 ? "text-up" : "text-down"}`}>{fmtPct(t.pnlPct, 1)}</span> },
    {
      key: "why", header: "청산 사유", cell: (t) => {
        const r = EXIT_REASON_LABEL[t.exitReason] ?? { label: t.exitReason, tone: "muted" as const };
        return <Pill tone={r.tone}>{r.label}</Pill>;
      },
    },
  ];

  if (rsLoading) {
    return (
      <TerminalPage title="전략 상세" crumb="퀀트랩 / 전략 상세">
        <p className="p-8 text-tm-muted">로딩 중...</p>
      </TerminalPage>
    );
  }
  if (!ruleSet) {
    return (
      <TerminalPage title="전략 상세" crumb="퀀트랩 / 전략 상세">
        <Notice tone="danger" className="m-2">룰셋을 찾을 수 없습니다. 로그인 상태나 주소를 확인해 주세요.</Notice>
      </TerminalPage>
    );
  }

  const universe: { market?: string; marketCapTier?: string } = (() => {
    try { return JSON.parse(ruleSet.universeJson || "{}"); } catch { return {}; }
  })();
  const universeMarket = universe.market ?? "all";
  const universeMarketCapTier = universe.marketCapTier ?? "all";
  const canShare = ruleSet.status === "BACKTESTED" || ruleSet.status === "RUNNING";
  const st = rulesetStatus(ruleSet.status);
  const fwRunning = forwardTest?.status === "RUNNING";
  const years = latestResult ? (new Date(latestResult.endDate).getTime() - new Date(latestResult.startDate).getTime()) / (365.25 * 86400_000) : null;

  return (
    <TerminalPage
      title={ruleSet.name}
      crumb={<>퀀트랩 / 전략 상세 · <span className="num">v{ruleSet.version}</span> · {st.label}</>}
      stats={[
        { label: "포워드 일치율", value: fmtMatch(forwardTest?.matchRate, forwardTest?.comparedSignals), tone: matchTone(forwardTest?.matchRate) },
        { label: "포워드 기간", value: fwRunning ? `${weeksSince(forwardTest!.startedAt)}주` : "—", tone: fwRunning ? undefined : "text-tm-muted" },
        { label: "운용 자산", value: fwRunning ? `${won(forwardTest!.currentEquity)}원` : "—", tone: fwRunning ? undefined : "text-tm-muted" },
        { label: "구독자", value: "—", tone: "text-tm-muted" },
      ]}
    >
      <Panel
        tabs={["실행 조건"]}
        actions={[]}
        closable={false}
        right={<BtnLink href={`/quant-lab/builder?edit=${id}`} kind="ghost" size="sm" icon="pencil">룰셋 수정</BtnLink>}
      >
        <div className="flex flex-wrap items-end gap-2">
          <StockPicker label="종목 / 유니버스" market={universeMarket} marketCapTier={universeMarketCapTier} value={stockId} onChange={setStockId} className="flex-[1_1_200px]" />
          <Field label="시작일" type="date" value={startDate} onChange={e => setStartDate(e.target.value)} className="flex-[1_1_150px]" />
          <Field label="종료일" type="date" value={endDate} onChange={e => setEndDate(e.target.value)} className="flex-[1_1_150px]" />
          <Field label="초기 자본" unit="원" type="number" value={capital} onChange={e => setCapital(+e.target.value)} className="flex-[1_1_170px]" />
          <Btn icon="play" size="xl" className="h-[52px]" onClick={() => runMutation.mutate()} disabled={runMutation.isPending || stockId == null} title={stockId == null ? "종목을 먼저 고르세요" : undefined}>
            {runMutation.isPending ? "실행 중..." : "백테스트 실행"}
          </Btn>
        </div>
        <span className="text-2xs text-tm-muted">수수료 0.015% + 슬리피지 0.1% 반영</span>
      </Panel>

      <PanelRow>
        <PanelCol className="flex-[999_1_640px]">
          <Panel
            tabs={[{ key: "equity", label: "운용 자산 곡선" }, { key: "dd", label: "낙폭" }, { key: "dist", label: "거래 분포" }]}
            active={chartTab}
            onTabChange={setChartTab}
            actions={[]}
            closable={false}
            right={latestResult?.reliabilityScore ? <Pill tone={RELIABILITY_TONE[latestResult.reliabilityScore] ?? "muted"}>신뢰도 {latestResult.reliabilityScore}</Pill> : undefined}
          >
            {results.length === 0 && !resultsLoading ? (
              <div className="grid h-64 place-items-center rounded-lg border border-dashed border-tm-line2 text-center text-13 text-tm-muted">
                <div>아직 백테스트 결과가 없습니다.<br />위의 실행 조건을 정한 뒤 실행하세요.</div>
              </div>
            ) : latestResult ? (
              <>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <Legend items={chartTab === "equity" ? [{ label: "전략", color: "#bd93f9" }] : chartTab === "dd" ? [{ label: "낙폭 (%)", color: "#ff79c6" }] : [{ label: "거래별 수익률 (%)", color: "#a4abcf" }]} />
                  <span className="num text-2xs text-tm-muted">{latestResult.startDate} ~ {latestResult.endDate} · {stockName}</span>
                </div>
                {chartTab === "equity" && curve.length > 1 && (
                  <LineChart series={[{ values: curve.map(p => p.equity), color: "#bd93f9", fill: true }]} width={880} height={300} xLabels={dateLabels(curve.map(p => p.date))} label="전략 운용 자산 곡선" />
                )}
                {chartTab === "dd" && curve.length > 1 && (
                  <LineChart series={[{ values: curve.map(p => p.drawdown), color: "#ff79c6", fill: true }]} width={880} height={300} xLabels={dateLabels(curve.map(p => p.date))} ySuffix="%" yDigits={1} baseline={0} label="낙폭 곡선" />
                )}
                {chartTab === "dist" && <TradeHistogram pcts={latestResult.trades.map(t => t.pnlPct)} />}
                {latestResult.reliabilityScore && (
                  <Notice tone={latestResult.reliabilityScore <= "B" ? "ok" : "warn"}>
                    <b>신뢰도 {latestResult.reliabilityScore}</b> — {RELIABILITY_TEXT[latestResult.reliabilityScore]}
                  </Notice>
                )}
                <AutoGrid min={120}>
                  <Stat big label="누적 수익" value={fmtPct(latestResult.totalReturn, 1)} valueClassName={(latestResult.totalReturn ?? 0) >= 0 ? "text-up" : "text-down"} sub={`벤치마크 ${fmtPct(latestResult.benchmarkReturn, 1)} · 초과 ${fmtPct(latestResult.excessReturn, 1)}`} />
                  <Stat big label="CAGR" value={fmtPct(latestResult.annualReturn, 1)} valueClassName={(latestResult.annualReturn ?? 0) >= 0 ? "text-up" : "text-down"} sub={years ? `${years.toFixed(1)}년` : undefined} />
                  <Stat big label="MDD" value={fmtMdd(latestResult.mdd)} valueClassName={(latestResult.mdd ?? 0) > 0.05 ? "text-down" : undefined} />
                  <Stat big label="샤프" value={latestResult.sharpe == null ? "—" : latestResult.sharpe.toFixed(2)} valueClassName={latestResult.sharpe == null ? "text-tm-muted" : undefined} sub={latestResult.sharpe == null ? "이전 결과는 다시 실행하면 계산" : "연환산 · 무위험 3%"} />
                  <Stat big label="승률" value={latestResult.winRate == null ? "—" : `${latestResult.winRate.toFixed(0)}%`} sub={`${latestResult.tradeCount ?? "—"}회 · 평균 보유 ${latestResult.avgHoldingDays?.toFixed(1) ?? "—"}일`} />
                  <Stat big label="손익비" value={latestResult.profitFactor?.toFixed(2) ?? "—"} sub="평균 익/손" />
                </AutoGrid>
              </>
            ) : (
              <p className="m-0 py-10 text-center text-tm-muted">불러오는 중…</p>
            )}
          </Panel>

          <Panel tabs={["월별 수익률 (%)"]} actions={[]} closable={false}>
            {latestResult ? <MonthlyHeatmap curve={curve} /> : <p className="m-0 py-6 text-center text-13 text-tm-muted">백테스트 결과가 있으면 월별 수익률을 보여 줍니다.</p>}
          </Panel>

          <Panel tabs={[`거래 내역 ${latestResult?.trades.length ?? 0}`]} actions={[]} closable={false} bodyClassName="px-1.5 pb-1.5 pt-1">
            <DataTable columns={tradeCols} rows={latestResult?.trades ?? []} rowKey={(_, i) => i} minWidth={720} empty="거래 내역이 없습니다." />
          </Panel>
        </PanelCol>

        <PanelCol className="flex-[1_1_340px] self-start">
          {/* 포워드 테스트 — 시안에는 상단 지표로만 있지만 시작/중지·신호 이력은 기존 기능이라 패널로 둔다 */}
          <div id="forward-test" />
          <Panel tabs={["포워드 테스트"]} actions={[]} closable={false} right={fwRunning ? <Pill tone="green">운용 중</Pill> : undefined}>
            {fwRunning && forwardTest ? (
              <>
                <AutoGrid min={130} gap={8}>
                  <Stat label="현재 자산" value={`${won(forwardTest.currentEquity)}원`} valueClassName="text-dracula-purple" />
                  <Stat label="초기 자본" value={`${won(forwardTest.initialCapital)}원`} />
                  <Stat label="포지션" value={forwardTest.holdingQty > 0 ? `보유 ${forwardTest.holdingQty}주` : "미보유"} />
                  <Stat label="시작일" value={new Date(forwardTest.startedAt).toLocaleDateString("ko-KR")} />
                  <Stat
                    label="포워드 일치율"
                    value={fmtMatch(forwardTest.matchRate, forwardTest.comparedSignals)}
                    valueClassName={matchTone(forwardTest.matchRate)}
                    sub={forwardTest.comparedSignals ? `${forwardTest.matchedSignals}/${forwardTest.comparedSignals} 신호 일치` : "같은 기간 재실행과 비교"}
                  />
                </AutoGrid>
                {forwardTest.equityCurve.length > 1 && (
                  <LineChart series={[{ values: forwardTest.equityCurve.map(p => p.equity), color: "#50fa7b", fill: true }]} width={320} height={140} label="포워드 테스트 운용 자산 곡선" />
                )}
                <div className="flex flex-col">
                  <div className="flex items-center justify-between border-b border-tm-line pb-1.5 text-xs">
                    <span className="font-semibold">신호 이력</span>
                    <span className="num text-tm-muted">{forwardTest.signals.length}건</span>
                  </div>
                  {forwardTest.signals.length === 0 ? (
                    <p className="m-0 py-4 text-center text-2xs text-tm-muted">아직 신호 없음</p>
                  ) : (
                    <ul className="m-0 max-h-56 list-none overflow-y-auto p-0">
                      {forwardTest.signals.map((s, i) => (
                        <li key={i} className="flex items-center justify-between border-b border-tm-line py-2 text-xs">
                          <Pill tone={s.direction === "BUY" ? "green" : "red"}>{SIGNAL_DIRECTION_LABEL[s.direction] ?? s.direction}</Pill>
                          <span className="num text-tm-muted">{s.evalDate ?? new Date(s.signalTime).toLocaleDateString("ko-KR")}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
                <Btn kind="danger" icon="pause" full onClick={() => stopFwMutation.mutate()} disabled={stopFwMutation.isPending}>
                  {stopFwMutation.isPending ? "중지 중..." : "포워드 테스트 중지"}
                </Btn>
                <span className="text-center text-2xs text-tm-muted">매일 장 마감 후(KST 16:00) 자동 평가</span>
              </>
            ) : ruleSet.status === "BACKTESTED" ? (
              <>
                <StockPicker market={universeMarket} marketCapTier={universeMarketCapTier} value={fwStockId} onChange={setFwStockId} />
                <Field label="초기 자본" unit="원" type="number" value={fwCapital} onChange={e => setFwCapital(+e.target.value)} />
                <Btn icon="play" full onClick={() => startFwMutation.mutate()} disabled={startFwMutation.isPending || fwStockId == null} title={fwStockId == null ? "종목을 먼저 고르세요" : undefined}>
                  {startFwMutation.isPending ? "시작 중..." : "포워드 테스트 시작"}
                </Btn>
                <span className="text-center text-2xs text-tm-muted">시작하면 룰셋 수정이 잠기고, 매일 장 마감 후 자동 평가합니다.</span>
              </>
            ) : (
              <p className="m-0 rounded-lg border border-dashed border-tm-line2 py-6 text-center text-2xs text-tm-muted">
                백테스트를 먼저 완료해야<br />포워드 테스트를 시작할 수 있습니다.
              </p>
            )}
          </Panel>

          <Panel tabs={["전략 마켓에 공유"]} actions={[]} closable={false}>
            <Notice tone="ok" icon="lock">
              룰셋은 서버에서만 실행되고 구독자에게는 <b>신호와 성과 지표만</b> 전달됩니다. 조건식은 공개되지 않습니다. 구독료의 70%가 제작자 수익으로 적립됩니다.
            </Notice>
            <label className="flex flex-col gap-1.5 text-13 text-tm-soft">
              전략 소개
              <textarea
                rows={4}
                value={shareDesc}
                onChange={e => setShareDesc(e.target.value)}
                placeholder="이 전략을 소개해주세요 (선택)"
                className="resize-y rounded-[10px] border border-tm-line2 bg-tm-inner p-3 text-sm leading-relaxed text-dracula-fg outline-none placeholder:text-[#8b92b8] focus:border-dracula-purple"
              />
            </label>
            <Field label="월 구독료 (0이면 무료)" unit="원" type="number" min={0} max={1000000} step={1000} value={sharePrice} onChange={e => setSharePrice(Math.min(1_000_000, Math.max(0, Math.floor(+e.target.value))))} />
            {sharePrice > 0 && (
              <Notice tone="warn">유료 구독 결제는 아직 열리지 않았습니다. 가격은 표시되지만 구독자는 결제가 열릴 때까지 이 전략을 구독할 수 없습니다.</Notice>
            )}
            <div className="flex flex-col gap-2">
              <div className="flex items-start gap-2">
                <Checkbox checked={false} disabled label="포워드 테스트 12주 이상 — 검증 배지 신청" sub={fwRunning ? `현재 ${weeksSince(forwardTest!.startedAt)}주 · 일치율 ${fmtMatch(forwardTest!.matchRate, forwardTest!.comparedSignals)}` : "포워드 테스트 중이 아닙니다"} />
                <PreviewTag className="mt-0.5" />
              </div>
              <Checkbox checked={shareAck} onChange={setShareAck} label="과거 성과가 미래 수익을 보장하지 않음을 구독자에게 고지" />
            </div>
            {!canShare && <span className="text-xs text-tm-muted">백테스트를 완료한 전략만 공유할 수 있습니다.</span>}
            <Btn
              icon="share"
              full
              size="lg"
              disabled={!canShare || !shareAck || shareMutation.isPending}
              onClick={() => shareMutation.mutate({ description: shareDesc, price: sharePrice })}
            >
              {shareMutation.isPending ? "공유 중..." : "전략 마켓에 공유"}
            </Btn>
          </Panel>

          {results.length > 1 && (
            <Panel tabs={["이전 이력"]} actions={[]} closable={false}>
              {results.slice(1).map((r: BacktestResult) => (
                <KV
                  key={r.id}
                  k={<span className="num">{r.startDate?.slice(0, 7)} ~ {r.endDate?.slice(0, 7)}</span>}
                  v={<span className="inline-flex items-center gap-2"><span className={(r.totalReturn ?? 0) >= 0 ? "text-up" : "text-down"}>{fmtPct(r.totalReturn)}</span>{r.reliabilityScore && <Pill tone={RELIABILITY_TONE[r.reliabilityScore] ?? "muted"}>{r.reliabilityScore}</Pill>}</span>}
                />
              ))}
            </Panel>
          )}

          <p className="m-0 text-center text-2xs text-tm-muted">과거 성과가 미래 수익을 보장하지 않습니다</p>
        </PanelCol>
      </PanelRow>
    </TerminalPage>
  );
}
