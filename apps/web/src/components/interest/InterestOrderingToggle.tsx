"use client";

import { Chip } from "@/components/terminal";
import { cn } from "@/lib/utils";

/**
 * ADR-099 — "관심 분야 순" 스위치. 관심 분야를 고른 사용자에게만 보인다(호출부가 available로 거른다).
 * 켜면 관심 분야 항목을 앞에 두고 표시만 할 뿐, 어떤 항목도 숨기지 않는다. 값은 계정에 저장된다(PATCH preferences).
 */
export function InterestOrderingToggle({ active, onChange }: { active: boolean; onChange: (v: boolean) => void }) {
  return (
    <span title="관심 분야 항목을 앞에 두고 표시합니다. 숨기는 항목은 없습니다. 설정 › 관심 분야에서 분야를 바꿀 수 있어요.">
      <Chip active={active} onClick={() => onChange(!active)} dot={active ? "#bd93f9" : undefined}>
        관심 분야 순
      </Chip>
    </span>
  );
}

/** 관심 분야에 해당하는 항목 옆의 작은 표시 — 색만으로 구분하지 않게 글자로 쓴다. */
export function InterestMark({ className }: { className?: string }) {
  return (
    <span className={cn("inline-flex h-4 flex-none items-center rounded px-1 text-[0.625rem] font-semibold text-[#e2d0ff] ring-1 ring-dracula-purple/60", className)}>
      관심
    </span>
  );
}
