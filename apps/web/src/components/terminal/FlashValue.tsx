"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { usePriceFlashEnabled } from "@/stores/a11yStore";

/**
 * 실시간 가격 셀 — `value`가 바뀌면 오르면 상승색, 내리면 하락색 배경을 잠깐 비춘다.
 * 접근성 설정의 "가격 변동 깜빡임"이 꺼져 있거나 움직임 줄이기가 켜져 있으면(OS 설정 포함) 깜빡이지 않는다.
 * 첫 값과 null(값 없음)로의 변화는 깜빡이지 않는다 — 실제 체결 변화만 강조한다.
 */
export function FlashValue({ value, children, className }: { value: number | null | undefined; children: ReactNode; className?: string }) {
  const enabled = usePriceFlashEnabled();
  const prev = useRef<number | null | undefined>(value);
  const [dir, setDir] = useState<"up" | "down" | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const before = prev.current;
    prev.current = value;
    if (!enabled) { setDir(null); return; }
    if (before == null || value == null || before === value) return;
    setDir(value > before ? "up" : "down");
    setTick((t) => t + 1); // key를 바꿔 같은 방향 연속 변화에도 애니메이션을 다시 시작
    const t = setTimeout(() => setDir(null), 700);
    return () => clearTimeout(t);
  }, [value, enabled]);

  return (
    <span
      key={tick}
      data-flash={dir ?? undefined}
      className={cn("rounded-sm", enabled && dir === "up" && "tm-flash-up", enabled && dir === "down" && "tm-flash-down", className)}
    >
      {children}
    </span>
  );
}
