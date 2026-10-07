"use client";
import { useCallback, useEffect, useState } from "react";

/**
 * 상자가 실제로 차지한 높이(최소값 보장)를 잰다. ECharts처럼 높이를 숫자로 받아야 그리는 차트를
 * 패널이 늘어난 만큼 채우게 할 때 쓴다 — 고정 높이면 옆 열이 길 때 패널 바닥에 빈 띠가 남는다.
 * 좁은 화면(<640px)에서는 minNarrow를 최소값으로 쓴다.
 *
 * 콜백 ref를 돌려준다 — 상자가 로딩·게이트 화면 뒤에 늦게 마운트돼도 그때 붙어서 잰다
 * (객체 ref + 마운트 시 1회 effect였을 때는 늦게 생긴 상자를 끝내 재지 못했다).
 */
export function useFillHeight(min: number, minNarrow = min) {
  const [el, setEl] = useState<HTMLElement | null>(null);
  const [h, setH] = useState(min);
  const ref = useCallback((node: HTMLElement | null) => setEl(node), []);

  useEffect(() => {
    const measure = () => {
      const floor = window.innerWidth < 640 ? minNarrow : min;
      setH(Math.max(floor, Math.floor(el?.clientHeight ?? 0)));
    };
    measure();
    window.addEventListener("resize", measure);
    const ro = el && typeof ResizeObserver !== "undefined" ? new ResizeObserver(measure) : null;
    if (el && ro) ro.observe(el);
    return () => {
      window.removeEventListener("resize", measure);
      ro?.disconnect();
    };
  }, [el, min, minNarrow]);

  return [ref, h] as const;
}
