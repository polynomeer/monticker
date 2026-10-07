"use client";

import BacktestCandleChart from "./BacktestCandleChart";
import { AutoGrid, DataTable, LineChart, Panel, Pill, Stat, fmtNum, fmtPct, type Column } from "@/components/terminal";

interface BacktestMetrics {
  totalReturn: number;
  annualizedReturn?: number;
  sharpeRatio: number;
  maxDrawdown: number;
  winRate: number;
  profitTrades: number;
  totalTrades: number;
  profitFactor: number;
  avgHoldingDays: number;
}

interface BacktestTrade {
  entryDate: string;
  exitDate: string;
  entryPrice: number;
  exitPrice: number;
  /** 서버 TradeRecord.quantity(주) */
  quantity?: number;
  pnlPct: number;
  exitReason: string;
}

interface EquityPoint {
  date: string;
  equity: number;
}

/** ADR-079 — 서버가 실제로 적용한 비용 */
interface AppliedCosts {
  commissionPct: number;
  sellTaxPct: number;
  slippagePct: number;
  sellTaxApplied: boolean;
  totalCost: number;
}

export interface BacktestResult {
  /** 서버가 돌려준 대상 종목 — 캔들·마커 패널을 그릴 때 쓴다 */
  stockId?: number;
  strategy: string;
  symbol: string;
  fromDate: string;
  toDate: string;
  initialCapital: number;
  finalCapital: number;
  metrics: BacktestMetrics;
  trades: BacktestTrade[];
  equityCurve: EquityPoint[];
  costs?: AppliedCosts;
}

function costLabel(c: AppliedCosts | undefined) {
  if (!c || (c.commissionPct === 0 && c.sellTaxPct === 0 && c.slippagePct === 0)) return "비용 미반영";
  const parts = [
    c.commissionPct > 0 && `수수료 ${c.commissionPct}%`,
    c.sellTaxApplied && `세금 ${c.sellTaxPct}%`,
    c.slippagePct > 0 && `슬리피지 ${c.slippagePct}%`,
  ].filter(Boolean);
  return `${parts.join(" · ")} · 비용 ${fmtNum(c.totalCost)}원`;
}

interface Props { result: BacktestResult; }

const REASON: Record<string, { label: string; tone: "green" | "red" | "muted" }> = {
  TAKE_PROFIT: { label: "익절", tone: "green" },
  END: { label: "기간 종료", tone: "muted" },
  STOP_LOSS: { label: "손절", tone: "red" },
  SIGNAL: { label: "신호", tone: "muted" },
};

const tone = (n: number) => (n > 0 ? "text-up" : n < 0 ? "text-down" : "text-dracula-fg");

function xLabels(curve: EquityPoint[]): [number, string][] {
  if (curve.length < 2) return [];
  return [0, 0.33, 0.66, 0.95].map((f) => [f, curve[Math.round(f * (curve.length - 1))].date.slice(0, 7).replace("-", ".")]);
}

/** 시안 backtest()의 오른쪽 열 — "결과 차트"(지표 + 자산 곡선)와 "거래 내역" 패널. */
export default function BacktestResultView({ result }: Props) {
  const stockId = result.stockId;
  const { metrics, trades, equityCurve, initialCapital, finalCapital } = result;

  const cols: Column<BacktestTrade>[] = [
    { key: "in", header: "매수", cell: (t) => <span className="num">{t.entryDate}</span> },
    { key: "out", header: "매도", cell: (t) => <span className="num">{t.exitDate}</span> },
    { key: "ip", header: "매수가", align: "right", cell: (t) => <span className="num">{fmtNum(t.entryPrice)}</span> },
    { key: "op", header: "매도가", align: "right", cell: (t) => <span className="num">{fmtNum(t.exitPrice)}</span> },
    { key: "pct", header: "수익", align: "right", cell: (t) => <span className={`num ${tone(t.pnlPct)}`}>{fmtPct(t.pnlPct, 1)}</span> },
    {
      key: "why", header: "사유", cell: (t) => {
        const r = REASON[t.exitReason] ?? { label: "종료", tone: "muted" as const };
        return <Pill tone={r.tone}>{r.label}</Pill>;
      },
    },
  ];

  return (
    <>
      <Panel tabs={["결과 차트"]} actions={[]} closable={false} right={<span className="num text-2xs text-tm-muted">{result.symbol} · {result.fromDate} ~ {result.toDate}</span>}>
        <AutoGrid min={110}>
          <Stat big label="총 수익" value={fmtPct(metrics.totalReturn, 1)} valueClassName={tone(metrics.totalReturn)} sub={`최종 ${fmtNum(finalCapital)}원`} />
          <Stat big label="CAGR" value={metrics.annualizedReturn == null ? "—" : fmtPct(metrics.annualizedReturn, 1)} valueClassName={metrics.annualizedReturn == null ? "text-tm-muted" : tone(metrics.annualizedReturn)} />
          <Stat big label="MDD" value={metrics.maxDrawdown > 0.05 ? `-${metrics.maxDrawdown.toFixed(1)}%` : "0.0%"} valueClassName={metrics.maxDrawdown > 0.05 ? "text-down" : undefined} />
          <Stat big label="승률" value={`${metrics.winRate.toFixed(0)}%`} sub={`${metrics.profitTrades}/${metrics.totalTrades}건`} />
          <Stat big label="거래" value={`${metrics.totalTrades}회`} sub={`평균 보유 ${metrics.avgHoldingDays.toFixed(1)}일`} />
          <Stat big label="샤프" value={metrics.sharpeRatio.toFixed(2)} sub={`손익비 ${metrics.profitFactor.toFixed(2)}`} />
        </AutoGrid>
        <span className="num text-2xs text-tm-muted">{costLabel(result.costs)}</span>
        {equityCurve.length > 1 ? (
          <LineChart
            series={[{ values: equityCurve.map((p) => p.equity), color: finalCapital >= initialCapital ? "#bd93f9" : "#ff79c6", fill: true }]}
            width={860}
            height={300}
            baseline={initialCapital}
            xLabels={xLabels(equityCurve)}
            label="백테스트 기간 자산 곡선"
          />
        ) : (
          <p className="m-0 py-10 text-center text-13 text-tm-muted">자산 곡선 데이터가 없습니다.</p>
        )}
      </Panel>

      {stockId != null && (
        <Panel tabs={["캔들 · 매매"]} actions={[]} closable={false} right={<span className="text-2xs text-tm-muted">▲ 매수 · ▼ 매도 · 같은 날 여러 건은 숫자로 묶음</span>}>
          <BacktestCandleChart stockId={stockId} fromDate={result.fromDate} toDate={result.toDate} trades={trades} />
        </Panel>
      )}

      <Panel tabs={["거래 내역"]} actions={[]} closable={false} bodyClassName="px-1.5 pb-1.5 pt-1">
        <DataTable columns={cols} rows={trades} rowKey={(_, i) => i} minWidth={600} empty="이 기간에 체결된 거래가 없습니다." />
      </Panel>
    </>
  );
}
