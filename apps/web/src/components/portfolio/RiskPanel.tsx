"use client";

import { useEffect, useState } from "react";
import { Panel, Tile } from "@/components/terminal";
import { Skeleton } from "./PaperStates";

interface RiskMetrics {
  sharpeRatio: number;
  beta: number;
  maxDrawdown: number;
  volatility: number;
  var95: number;
  winRate: number;
  totalTrades: number;
  avgReturn: number;
  dailyReturns: Array<{ date: string; returnPct: number }>;
  drawdownSeries: Array<{ date: string; drawdown: number }>;
  hasEnoughData: boolean;
  message: string | null;
}

function RiskBadge({
  label,
  value,
  desc,
  colorClass,
}: {
  label: string;
  value: string;
  desc?: string;
  colorClass?: string;
}) {
  return (
    <Tile className="gap-1 p-3">
      <span className="text-2xs text-tm-muted">{label}</span>
      <span className={`num text-lg font-semibold ${colorClass ?? "text-dracula-fg"}`}>{value}</span>
      {desc && <span className="text-2xs text-tm-muted">{desc}</span>}
    </Tile>
  );
}

function ReturnBarChart({ data }: { data: Array<{ date: string; returnPct: number }> }) {
  if (!data.length) return null;
  const values = data.map((d) => d.returnPct);
  const max = Math.max(...values.map(Math.abs), 0.01);
  const W = 600;
  const H = 80;
  const gap = 1;
  const barW = Math.max(1, Math.floor(W / values.length) - gap);

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" preserveAspectRatio="none" aria-hidden>
      <line x1={0} y1={H / 2} x2={W} y2={H / 2} stroke="#44475a" strokeWidth={0.5} />
      {values.map((v, i) => {
        const barH = Math.max(1, (Math.abs(v) / max) * (H / 2 - 2));
        const x = i * (barW + gap);
        const y = v >= 0 ? H / 2 - barH : H / 2;
        return (
          <rect
            key={i}
            x={x}
            y={y}
            width={barW}
            height={barH}
            fill={v >= 0 ? "rgb(var(--mt-up))" : "rgb(var(--mt-down))"}
            opacity={0.85}
          />
        );
      })}
    </svg>
  );
}

function DrawdownChart({ data }: { data: Array<{ date: string; drawdown: number }> }) {
  if (data.length < 2) return null;
  const values = data.map((d) => d.drawdown);
  const maxDD = Math.max(...values, 0.01);
  const W = 600;
  const H = 80;

  const toPoint = (v: number, i: number) => {
    const x = (i / (values.length - 1)) * W;
    const y = (v / maxDD) * (H - 4) + 2;
    return { x, y };
  };

  const pts = values.map((v, i) => toPoint(v, i));
  const polyPts = pts.map((p) => `${p.x},${p.y}`).join(" ");
  const first = pts[0];
  const last = pts[pts.length - 1];
  const areaPath = `M${first.x},${first.y} ${pts.map((p) => `L${p.x},${p.y}`).join(" ")} L${last.x},${H} L${first.x},${H} Z`;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" preserveAspectRatio="none" aria-hidden>
      <path d={areaPath} fill="rgb(var(--mt-down) / 0.12)" />
      <polyline points={polyPts} fill="none" stroke="rgb(var(--mt-down))" strokeWidth={1.5} />
    </svg>
  );
}

export default function RiskPanel() {
  const [data, setData] = useState<RiskMetrics | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchRisk = async () => {
    try {
      const res = await fetch("/api/paper/risk");
      if (res.ok) setData(await res.json());
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchRisk();
    const id = setInterval(fetchRisk, 30_000);
    return () => clearInterval(id);
  }, []);

  if (loading) {
    return (
      <Panel tabs={["리스크 지표"]} actions={["refresh"]} onAction={() => fetchRisk()}>
        <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
          {[1, 2, 3, 4, 5, 6].map((i) => <Skeleton key={i} className="h-16" />)}
        </div>
      </Panel>
    );
  }

  if (!data) return null;

  if (!data.hasEnoughData) {
    return (
      <Panel tabs={["리스크 지표"]} actions={["refresh"]} onAction={() => fetchRisk()}>
        <p className="m-0 text-13 text-tm-muted">{data.message}</p>
      </Panel>
    );
  }

  const sharpeColor = data.sharpeRatio >= 1 ? "text-up" : data.sharpeRatio >= 0 ? "text-dracula-fg" : "text-down";
  const betaColor = data.beta <= 1.2 ? "text-dracula-fg" : "text-down";

  return (
    <Panel tabs={["리스크 지표"]} actions={["refresh"]} onAction={() => fetchRisk()} right={<span className="text-2xs text-tm-muted">보유 종목 일봉 기준</span>}>
      <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
        <RiskBadge label="Sharpe Ratio" value={data.sharpeRatio.toFixed(2)} desc="1 이상 양호" colorClass={sharpeColor} />
        <RiskBadge label="Beta" value={data.beta.toFixed(2)} desc="시장 민감도" colorClass={betaColor} />
        <RiskBadge label="MDD" value={`${data.maxDrawdown.toFixed(1)}%`} desc="최대 낙폭" colorClass="text-down" />
        <RiskBadge label="연변동성" value={`${data.volatility.toFixed(1)}%`} desc="연환산 σ" />
        <RiskBadge label="VaR (95%)" value={`${data.var95.toFixed(1)}%`} desc="1일 최대손실" colorClass="text-down" />
        <RiskBadge
          label="승률"
          value={`${data.winRate.toFixed(1)}%`}
          desc={`${data.totalTrades}건 거래`}
          colorClass={data.winRate >= 50 ? "text-up" : "text-down"}
        />
      </div>

      {data.dailyReturns.length > 0 && (
        <div className="flex flex-col gap-1">
          <span className="text-2xs text-tm-muted">일별 수익률</span>
          <div className="overflow-hidden rounded-lg bg-tm-inner p-1">
            <ReturnBarChart data={data.dailyReturns} />
          </div>
        </div>
      )}

      {data.drawdownSeries.length > 1 && (
        <div className="flex flex-col gap-1">
          <span className="text-2xs text-tm-muted">낙폭 (Drawdown)</span>
          <div className="overflow-hidden rounded-lg bg-tm-inner p-1">
            <DrawdownChart data={data.drawdownSeries} />
          </div>
        </div>
      )}

      <div className="flex flex-wrap gap-3 text-2xs text-tm-muted">
        <span>Sharpe ≥ 1: 우수</span>
        <span>MDD: 낮을수록 안정적</span>
        <span>Beta ≈ 1: 시장과 동행</span>
        <span>VaR: 하루 최대 손실 예상</span>
      </div>
    </Panel>
  );
}
