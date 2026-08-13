"use client";

import { useEffect, useState } from "react";

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
    <div className="bg-gray-100 dark:bg-gray-800 rounded-lg p-3">
      <p className="text-[10px] text-gray-500 mb-0.5">{label}</p>
      <p className={`text-lg font-bold font-mono ${colorClass ?? "text-gray-900 dark:text-gray-100"}`}>{value}</p>
      {desc && <p className="text-[10px] text-gray-400 mt-0.5">{desc}</p>}
    </div>
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
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" preserveAspectRatio="none">
      <line x1={0} y1={H / 2} x2={W} y2={H / 2} stroke="#9ca3af" strokeWidth={0.5} />
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
            fill={v >= 0 ? "#0ecb81" : "#f6465d"}
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
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" preserveAspectRatio="none">
      <path d={areaPath} fill="#f6465d20" />
      <polyline points={polyPts} fill="none" stroke="#f6465d" strokeWidth={1.5} />
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
      <div className="border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 rounded-xl p-5 shadow-sm">
        <div className="h-4 w-32 bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-gray-700/50 dark:via-gray-600/50 dark:to-gray-700/50 bg-[length:200%_100%] animate-shimmer rounded mb-3" />
        <div className="grid grid-cols-3 gap-2">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <div key={i} className="h-16 bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-gray-700/50 dark:via-gray-600/50 dark:to-gray-700/50 bg-[length:200%_100%] animate-shimmer rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  if (!data) return null;

  if (!data.hasEnoughData) {
    return (
      <div className="border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 rounded-xl p-5">
        <h2 className="text-sm font-semibold mb-2">리스크 지표</h2>
        <p className="text-sm text-gray-500">{data.message}</p>
      </div>
    );
  }

  const sharpeColor =
    data.sharpeRatio >= 1
      ? "text-[#0ecb81]"
      : data.sharpeRatio >= 0
        ? "text-gray-900 dark:text-gray-100"
        : "text-[#f6465d]";
  const betaColor = data.beta <= 1.2 ? "text-gray-900 dark:text-gray-100" : "text-[#f6465d]";

  return (
    <div className="border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-900 rounded-xl p-5 space-y-4 shadow-sm animate-fade-up">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold">리스크 지표</h2>
        <span className="text-[10px] text-gray-400">보유 종목 일봉 기준</span>
      </div>

      {/* 지표 카드 */}
      <div className="grid grid-cols-3 sm:grid-cols-6 gap-2">
        <RiskBadge
          label="Sharpe Ratio"
          value={data.sharpeRatio.toFixed(2)}
          desc="1 이상 양호"
          colorClass={sharpeColor}
        />
        <RiskBadge
          label="Beta"
          value={data.beta.toFixed(2)}
          desc="시장 민감도"
          colorClass={betaColor}
        />
        <RiskBadge
          label="MDD"
          value={`${data.maxDrawdown.toFixed(1)}%`}
          desc="최대 낙폭"
          colorClass="text-[#f6465d]"
        />
        <RiskBadge label="연변동성" value={`${data.volatility.toFixed(1)}%`} desc="연환산 σ" />
        <RiskBadge
          label="VaR (95%)"
          value={`${data.var95.toFixed(1)}%`}
          desc="1일 최대손실"
          colorClass="text-[#f6465d]"
        />
        <RiskBadge
          label="승률"
          value={`${data.winRate.toFixed(1)}%`}
          desc={`${data.totalTrades}건 거래`}
          colorClass={data.winRate >= 50 ? "text-[#0ecb81]" : "text-[#f6465d]"}
        />
      </div>

      {/* 일수익률 바차트 */}
      {data.dailyReturns.length > 0 && (
        <div>
          <p className="text-[10px] text-gray-400 mb-1">일별 수익률</p>
          <div className="border border-gray-200 dark:border-gray-700 rounded-lg overflow-hidden bg-gray-50 dark:bg-gray-950 p-1">
            <ReturnBarChart data={data.dailyReturns} />
          </div>
        </div>
      )}

      {/* MDD 곡선 */}
      {data.drawdownSeries.length > 1 && (
        <div>
          <p className="text-[10px] text-gray-400 mb-1">낙폭 (Drawdown)</p>
          <div className="border border-gray-200 dark:border-gray-700 rounded-lg overflow-hidden bg-gray-50 dark:bg-gray-950 p-1">
            <DrawdownChart data={data.drawdownSeries} />
          </div>
        </div>
      )}

      {/* 범례 */}
      <div className="text-[10px] text-gray-400 flex flex-wrap gap-3">
        <span>Sharpe ≥ 1: 우수</span>
        <span>MDD: 낮을수록 안정적</span>
        <span>Beta ≈ 1: 시장과 동행</span>
        <span>VaR: 하루 최대 손실 예상</span>
      </div>
    </div>
  );
}
