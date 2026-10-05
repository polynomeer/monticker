"use client";

/**
 * 시안의 범위 슬라이더. 눈금(stops) 위치를 고르는 네이티브 range 입력 두 개(또는 하나)를 겹쳐 그린다 —
 * 키보드(←/→)와 스크린리더가 기본으로 동작한다. 값은 눈금 인덱스로 주고받는다.
 */
export default function RangeSlider({
  label, valueLabel, max, a, b, onChange, single = false, aLabel = "하한", bLabel = "상한",
}: {
  label: string;
  valueLabel: string;
  /** 마지막 눈금 인덱스 */
  max: number;
  a: number;
  b?: number;
  onChange: (a: number, b: number) => void;
  /** 하한 하나만(오른쪽 끝까지 채움) */
  single?: boolean;
  aLabel?: string;
  bLabel?: string;
}) {
  const hi = single ? max : (b ?? max);
  const pa = (Math.min(a, hi) / max) * 100;
  const pb = (hi / max) * 100;
  const thumb =
    "pointer-events-none absolute inset-x-0 -top-[5px] h-3.5 w-full appearance-none bg-transparent " +
    "[&::-webkit-slider-thumb]:pointer-events-auto [&::-webkit-slider-thumb]:h-3.5 [&::-webkit-slider-thumb]:w-3.5 [&::-webkit-slider-thumb]:cursor-pointer [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-dracula-fg " +
    "[&::-moz-range-thumb]:pointer-events-auto [&::-moz-range-thumb]:h-3.5 [&::-moz-range-thumb]:w-3.5 [&::-moz-range-thumb]:cursor-pointer [&::-moz-range-thumb]:rounded-full [&::-moz-range-thumb]:border-0 [&::-moz-range-thumb]:bg-dracula-fg " +
    "focus-visible:outline-none [&:focus-visible::-webkit-slider-thumb]:ring-2 [&:focus-visible::-webkit-slider-thumb]:ring-dracula-purple";

  return (
    <div className="flex flex-col gap-2">
      <div className="flex justify-between text-xs">
        <span className="text-tm-soft">{label}</span>
        <span className="num text-tm-muted">{valueLabel}</span>
      </div>
      <div className="relative mx-[7px] h-1 rounded-full bg-tm-inner">
        <div className="absolute h-full rounded-full bg-dracula-purple" style={{ left: `${pa}%`, right: `${100 - pb}%` }} />
        <input
          type="range" min={0} max={max} step={1} value={a}
          aria-label={`${label} ${aLabel}`} aria-valuetext={valueLabel}
          onChange={(e) => onChange(Math.min(Number(e.target.value), hi), hi)}
          className={thumb}
        />
        {!single && (
          <input
            type="range" min={0} max={max} step={1} value={hi}
            aria-label={`${label} ${bLabel}`} aria-valuetext={valueLabel}
            onChange={(e) => onChange(a, Math.max(Number(e.target.value), a))}
            className={thumb}
          />
        )}
      </div>
    </div>
  );
}
