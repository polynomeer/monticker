"use client";

import { useEffect, useState, type KeyboardEvent } from "react";
import { capRangeError, capToEokText, parseCapEok } from "./criteria";

const inputCls =
  "h-8 w-full min-w-0 rounded-lg border border-tm-line2 bg-tm-inner px-2.5 text-13 text-dracula-fg outline-none focus:border-dracula-purple aria-[invalid=true]:border-[#ff8a8a]";

/**
 * 시가총액 범위(억원) — 입력 중에는 조회하지 않고 포커스를 벗어나거나 Enter를 누를 때 적용한다.
 * 잘못된 값(음수·숫자 아님·최소>최대)은 적용하지 않고 메시지만 보여준다.
 */
export default function MarketCapRange({
  minCap,
  maxCap,
  onApply,
}: {
  minCap: number | null;
  maxCap: number | null;
  onApply: (range: { minCap: number | null; maxCap: number | null }) => void;
}) {
  const [minText, setMinText] = useState(capToEokText(minCap));
  const [maxText, setMaxText] = useState(capToEokText(maxCap));

  // 초기화·저장 스크린 불러오기처럼 바깥에서 조건이 바뀌면 입력칸도 맞춘다
  useEffect(() => setMinText(capToEokText(minCap)), [minCap]);
  useEffect(() => setMaxText(capToEokText(maxCap)), [maxCap]);

  const min = parseCapEok(minText);
  const max = parseCapEok(maxText);
  const error = capRangeError(min, max);

  const apply = () => {
    if (error || min === "invalid" || max === "invalid") return;
    if (min !== minCap || max !== maxCap) onApply({ minCap: min, maxCap: max });
  };
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") apply();
  };

  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-2xs text-tm-muted">시가총액 범위 (억원)</span>
      <div className="flex items-center gap-1.5">
        <input
          inputMode="decimal"
          aria-label="시가총액 최소(억원)"
          placeholder="최소"
          maxLength={12}
          value={minText}
          aria-invalid={min === "invalid" || (error != null && min != null)}
          onChange={(e) => setMinText(e.target.value)}
          onBlur={apply}
          onKeyDown={onKeyDown}
          className={inputCls}
        />
        <span className="text-xs text-tm-muted">~</span>
        <input
          inputMode="decimal"
          aria-label="시가총액 최대(억원)"
          placeholder="최대"
          maxLength={12}
          value={maxText}
          aria-invalid={max === "invalid" || (error != null && max != null)}
          onChange={(e) => setMaxText(e.target.value)}
          onBlur={apply}
          onKeyDown={onKeyDown}
          className={inputCls}
        />
      </div>
      {error ? (
        <span role="alert" className="text-xs text-[#ff8a8a]">{error}</span>
      ) : (minCap != null || maxCap != null) ? (
        <span className="text-2xs text-tm-muted">시가총액 정보가 없는 종목은 결과에서 빠집니다. 일부 시가총액은 모의값일 수 있습니다.</span>
      ) : null}
    </div>
  );
}
