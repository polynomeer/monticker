// 퀀트랩 화면들이 같이 쓰는 작은 조각 — 룰셋 상태 표식, 비활성 옵션이 있는 세그먼트,
// 자산 곡선에서 파생한 월별 수익률 히트맵·수익 분포. 공용 터미널 키트(components/terminal)에
// 없는 것만 여기 둔다.
"use client";

import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { PreviewTag, type Tone, fmtPct } from "@/components/terminal";

export const RULESET_STATUS: Record<string, { label: string; tone: Tone }> = {
  DRAFT: { label: "초안", tone: "muted" },
  BACKTESTED: { label: "백테스트 완료", tone: "muted" },
  // 백엔드의 RUNNING은 포워드 테스트 진행 중이다(ADR-024) — 실전 자동 운용이 아니다.
  RUNNING: { label: "포워드 테스트", tone: "purple" },
  ARCHIVED: { label: "보관됨", tone: "muted" },
};

export function rulesetStatus(s: string | undefined) {
  return RULESET_STATUS[s ?? ""] ?? RULESET_STATUS.DRAFT;
}

/** 일부 옵션을 "준비 중"으로 막아 둘 수 있는 세그먼트 — 키트의 Seg와 같은 모양. */
export function SegOpts<T extends string>({
  options, value, onChange, size = "md", className, label,
}: {
  options: readonly { value: T; label: ReactNode; disabled?: boolean }[];
  value: T;
  onChange?: (v: T) => void;
  size?: "md" | "lg";
  className?: string;
  label?: string;
}) {
  const h = size === "lg" ? "h-[30px]" : "h-7";
  return (
    <div role="group" aria-label={label} className={cn("inline-flex flex-wrap gap-0.5 rounded-lg bg-tm-inner p-[3px]", className)}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            aria-pressed={on}
            disabled={o.disabled}
            title={o.disabled ? "준비 중인 기능입니다" : undefined}
            onClick={() => onChange?.(o.value)}
            className={cn(
              h, "inline-flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 text-13 disabled:cursor-not-allowed disabled:opacity-50",
              on ? "bg-tm-line2 font-semibold text-dracula-fg" : "text-tm-muted hover:text-dracula-fg",
            )}
          >
            {o.label}
            {o.disabled && <PreviewTag />}
          </button>
        );
      })}
    </div>
  );
}

/** 자산 곡선(일별 equity) → 연·월별 수익률(%) */
export function monthlyReturns(curve: { date: string; equity: number }[]) {
  const lastByMonth = new Map<string, number>();
  for (const p of curve) lastByMonth.set(p.date.slice(0, 7), p.equity);
  const months = [...lastByMonth.keys()].sort();
  const out = new Map<string, number>();
  let prev = curve[0]?.equity;
  for (const m of months) {
    const v = lastByMonth.get(m)!;
    if (prev) out.set(m, ((v - prev) / prev) * 100);
    prev = v;
  }
  return out;
}

/** 월별 수익률 히트맵 — 시안의 detail() heat 그리드. 색은 상승/하락 테마를 따른다. */
export function MonthlyHeatmap({ curve }: { curve: { date: string; equity: number }[] }) {
  const map = monthlyReturns(curve);
  const years = [...new Set([...map.keys()].map((k) => k.slice(0, 4)))].sort();
  if (years.length === 0) return <p className="m-0 py-6 text-center text-13 text-tm-muted">월별로 나눌 데이터가 아직 없습니다.</p>;
  return (
    <div className="overflow-x-auto">
      <div className="grid min-w-[560px] gap-[3px]" style={{ gridTemplateColumns: "44px repeat(12,minmax(0,1fr))" }}>
        <span />
        {Array.from({ length: 12 }, (_, i) => (
          <span key={i} className="text-center text-2xs text-tm-muted">{i + 1}월</span>
        ))}
        {years.map((y) => (
          <Row key={y} year={y} map={map} />
        ))}
      </div>
    </div>
  );
}

function Row({ year, map }: { year: string; map: Map<string, number> }) {
  return (
    <>
      <span className="num self-center text-2xs text-tm-muted">{year}</span>
      {Array.from({ length: 12 }, (_, i) => {
        const v = map.get(`${year}-${String(i + 1).padStart(2, "0")}`);
        if (v == null) return <span key={i} className="h-[30px] rounded-[5px] bg-tm-inner" />;
        const a = Math.min(Math.abs(v) / 8, 1) * 0.75 + 0.15;
        return (
          <span
            key={i}
            title={`${year}년 ${i + 1}월 ${fmtPct(v, 1)}`}
            className="num grid h-[30px] place-items-center rounded-[5px] text-2xs text-dracula-fg"
            style={{ backgroundColor: `rgb(var(${v >= 0 ? "--mt-up" : "--mt-down"}) / ${a.toFixed(2)})` }}
          >
            {fmtPct(v, 1).replace("%", "")}
          </span>
        );
      })}
    </>
  );
}

/** 거래별 수익률 분포 — 1%p 구간 막대 */
export function TradeHistogram({ pcts }: { pcts: number[] }) {
  if (pcts.length === 0) return <p className="m-0 py-6 text-center text-13 text-tm-muted">거래가 없습니다.</p>;
  const lo = Math.floor(Math.min(...pcts));
  const hi = Math.ceil(Math.max(...pcts));
  const step = Math.max(1, Math.ceil((hi - lo) / 24));
  const bins: { from: number; n: number }[] = [];
  for (let b = lo; b <= hi; b += step) bins.push({ from: b, n: 0 });
  for (const p of pcts) {
    const i = Math.min(bins.length - 1, Math.floor((p - lo) / step));
    bins[i].n++;
  }
  const mx = Math.max(...bins.map((b) => b.n), 1);
  return (
    <div role="img" aria-label="거래별 수익률 분포" className="flex h-[220px] items-end gap-1 pt-2">
      {bins.map((b) => (
        <div key={b.from} className="flex h-full flex-1 flex-col items-center justify-end gap-1" title={`${b.from}% ~ ${b.from + step}% : ${b.n}건`}>
          <span className="num text-2xs text-tm-muted">{b.n || ""}</span>
          <div className={cn("w-full rounded-t", b.from >= 0 ? "bg-up" : "bg-down")} style={{ height: `${(b.n / mx) * 170}px` }} />
          <span className="num text-[0.625rem] text-tm-muted">{b.from}</span>
        </div>
      ))}
    </div>
  );
}
