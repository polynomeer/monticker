// 시안의 작은 SVG 차트들(스파크라인·도넛·라인). 큰 캔들 차트는 기존 ECharts 어댑터
// (components/stock/chart)를 그대로 쓴다 — 여기서 새 차트 엔진을 만들지 않는다.
import type { ReactNode } from "react";

export function Sparkline({ values, color = "currentColor", width = 88, height = 26, fill = true, stretch = false, className }: {
  values: number[];
  color?: string;
  width?: number;
  height?: number;
  fill?: boolean;
  stretch?: boolean;
  className?: string;
}) {
  if (values.length < 2) return <svg width={stretch ? "100%" : width} height={height} aria-hidden className={className} />;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const rng = hi - lo || 1;
  const pts = values.map((v, i) => `${((i / (values.length - 1)) * width).toFixed(1)},${(2 + ((hi - v) / rng) * (height - 4)).toFixed(1)}`).join(" ");
  return (
    <svg
      width={stretch ? "100%" : width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio={stretch ? "none" : undefined}
      aria-hidden
      className={className}
      style={{ display: "block" }}
    >
      {fill && <polygon points={`0,${height} ${pts} ${width},${height}`} fill={color} fillOpacity={0.12} />}
      <polyline points={pts} fill="none" stroke={color} strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

export function Donut({ parts, size = 150, thickness = 22, center, label = "비중 도넛 차트" }: {
  parts: { value: number; color: string }[];
  size?: number;
  thickness?: number;
  center?: [ReactNode, ReactNode];
  label?: string;
}) {
  const r = (size - thickness) / 2;
  const C = 2 * Math.PI * r;
  const total = parts.reduce((a, p) => a + p.value, 0) || 1;
  let acc = 0;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} role="img" aria-label={label} className="flex-none">
      {parts.map((p, i) => {
        const L = (p.value / total) * C;
        const el = (
          <circle
            key={i}
            cx={size / 2}
            cy={size / 2}
            r={r}
            fill="none"
            stroke={p.color}
            strokeWidth={thickness}
            strokeDasharray={`${Math.max(L - 2, 0)} ${C}`}
            strokeDashoffset={-acc}
            transform={`rotate(-90 ${size / 2} ${size / 2})`}
          />
        );
        acc += L;
        return el;
      })}
      {center && (
        <>
          <text x={size / 2} y={size / 2 - 2} textAnchor="middle" fill="#f8f8f2" fontSize={18} fontWeight={600} className="num">{center[0]}</text>
          <text x={size / 2} y={size / 2 + 18} textAnchor="middle" fill="#a4abcf" fontSize={11}>{center[1]}</text>
        </>
      )}
    </svg>
  );
}

export interface LineSeries {
  values: number[];
  color: string;
  dashed?: boolean;
  fill?: boolean;
}

/** 다중 라인 차트(수익 곡선·정규화 비교) — 시안의 line_svg */
export function LineChart({ series, width = 860, height = 240, xLabels = [], yDigits = 0, ySuffix = "", baseline, label }: {
  series: LineSeries[];
  width?: number;
  height?: number;
  xLabels?: [number, string][];
  yDigits?: number;
  ySuffix?: string;
  baseline?: number;
  label: string;
}) {
  const all = series.flatMap((s) => s.values);
  if (all.length === 0) return null;
  let lo = Math.min(...all);
  let hi = Math.max(...all);
  const pad = (hi - lo || 1) * 0.08;
  lo -= pad;
  hi += pad;
  const T = 10;
  const B = height - 26;
  const y = (v: number) => T + ((hi - v) / (hi - lo)) * (B - T);
  const grid = [0, 1, 2, 3, 4].map((k) => lo + ((hi - lo) * k) / 4);
  return (
    <svg viewBox={`0 0 ${width + 60} ${height}`} className="block h-auto w-full" role="img" aria-label={label}>
      {grid.map((g, k) => (
        <g key={k}>
          <line x1={0} x2={width} y1={y(g)} y2={y(g)} stroke="#34364a" strokeDasharray="2 4" />
          <text x={width + 8} y={y(g) + 4} fill="#a4abcf" fontSize={11} className="num">
            {g.toLocaleString("ko-KR", { maximumFractionDigits: yDigits, minimumFractionDigits: yDigits })}
            {ySuffix}
          </text>
        </g>
      ))}
      {baseline != null && <line x1={0} x2={width} y1={y(baseline)} y2={y(baseline)} stroke="#a4abcf" strokeOpacity={0.5} />}
      {series.map((s, si) => {
        const n = s.values.length;
        const pts = s.values.map((v, i) => `${((i / Math.max(n - 1, 1)) * width).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
        return (
          <g key={si}>
            {s.fill && <polygon points={`0,${B} ${pts} ${width},${B}`} fill={s.color} fillOpacity={0.1} />}
            <polyline points={pts} fill="none" stroke={s.color} strokeWidth={s.dashed ? 1.5 : 2.2} strokeDasharray={s.dashed ? "4 3" : undefined} />
          </g>
        );
      })}
      {xLabels.map(([fx, t]) => (
        <text key={t + fx} x={Math.min(fx * width, width - 48)} y={height - 6} fill="#a4abcf" fontSize={11} className="num">
          {t}
        </text>
      ))}
    </svg>
  );
}

export function Legend({ items }: { items: { label: ReactNode; color: string; dashed?: boolean }[] }) {
  return (
    <div className="flex flex-wrap gap-x-3.5 gap-y-1.5 text-xs text-tm-muted">
      {items.map((it, i) => (
        <span key={i} className="flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-[3px]" style={{ background: it.color }} />
          {it.label}
        </span>
      ))}
    </div>
  );
}
