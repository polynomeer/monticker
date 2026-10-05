"use client";

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
  pnlPct: number;
  exitReason: string;
}

interface EquityPoint {
  date: string;
  equity: number;
}

export interface BacktestResult {
  strategy: string;
  symbol: string;
  fromDate: string;
  toDate: string;
  initialCapital: number;
  finalCapital: number;
  metrics: BacktestMetrics;
  trades: BacktestTrade[];
  equityCurve: EquityPoint[];
}

interface Props { result: BacktestResult; }

const REASON: Record<string, { label: string; tone: "green" | "red" | "muted" }> = {
  TAKE_PROFIT: { label: "익절", tone: "green" },
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
          <Stat big label="MDD" value={`-${metrics.maxDrawdown.toFixed(1)}%`} valueClassName="text-down" />
          <Stat big label="승률" value={`${metrics.winRate.toFixed(0)}%`} sub={`${metrics.profitTrades}/${metrics.totalTrades}건`} />
          <Stat big label="거래" value={`${metrics.totalTrades}회`} sub={`평균 보유 ${metrics.avgHoldingDays.toFixed(1)}일`} />
          <Stat big label="샤프" value={metrics.sharpeRatio.toFixed(2)} sub={`손익비 ${metrics.profitFactor.toFixed(2)}`} />
        </AutoGrid>
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

      <Panel tabs={["거래 내역"]} actions={[]} closable={false} bodyClassName="px-1.5 pb-1.5 pt-1">
        <DataTable columns={cols} rows={trades} rowKey={(_, i) => i} minWidth={600} empty="이 기간에 체결된 거래가 없습니다." />
      </Panel>
    </>
  );
}
