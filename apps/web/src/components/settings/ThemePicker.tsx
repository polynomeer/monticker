"use client";

import { useRef, type KeyboardEvent } from "react";
import { cn } from "@/lib/utils";
import { APP_THEME_KEYS, THEMES, type AppThemeKey } from "@/lib/themes";

function choiceBorder(on: boolean) {
  return on ? "border-2 border-dracula-purple" : "border border-tm-line2 hover:border-tm-muted";
}

/** 테마 카드 — 미리보기는 그 테마의 실제 값(지금 테마와 무관하게 고정 hex): 페이지·패널·강조·상승·하락 */
function ThemeCard({ themeKey, on, onSelect, onKeyDown }: {
  themeKey: AppThemeKey; on: boolean; onSelect: () => void; onKeyDown: (e: KeyboardEvent<HTMLButtonElement>) => void;
}) {
  const { name, description, palette: p } = THEMES[themeKey];
  return (
    <button
      type="button"
      role="radio"
      aria-checked={on}
      // 라디오 그룹은 선택된 카드만 탭 순서에 — 나머지는 화살표로 옮긴다
      tabIndex={on ? 0 : -1}
      data-theme-key={themeKey}
      onClick={onSelect}
      onKeyDown={onKeyDown}
      className={cn("flex flex-col gap-2.5 rounded-xl bg-tm-inner p-3 text-left text-dracula-fg", choiceBorder(on))}
    >
      <span className="flex h-[72px] gap-1.5 rounded-lg p-2" style={{ background: p.page, border: `1px solid ${p.line}` }} aria-hidden>
        <span className="w-3 rounded-sm" style={{ background: p.raised }} />
        <span className="flex flex-1 flex-col justify-between rounded-sm p-1.5" style={{ background: p.panel }}>
          <span className="h-1.5 w-3/5 rounded-full" style={{ background: p.fg }} />
          <span className="h-1 w-2/5 rounded-full" style={{ background: p.muted }} />
          <span className="flex items-end gap-1">
            <span className="h-3 w-6 rounded-sm" style={{ background: p.accent }} />
            <span className="ml-auto h-4 w-1.5 rounded-[1px]" style={{ background: p.up }} />
            <span className="h-2.5 w-1.5 rounded-[1px]" style={{ background: p.down }} />
          </span>
        </span>
      </span>
      <span className="flex flex-col gap-0.5">
        <span className="font-semibold">{name}</span>
        <span className="text-xs text-tm-muted">{description}</span>
      </span>
    </button>
  );
}

/** 테마 선택 — role=radiogroup, ←→↑↓로 옮기며 바로 적용한다 */
export function ThemePicker({ value, onChange }: { value: AppThemeKey; onChange: (k: AppThemeKey) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const move = (from: AppThemeKey, delta: number) => {
    const i = APP_THEME_KEYS.indexOf(from);
    const next = APP_THEME_KEYS[(i + delta + APP_THEME_KEYS.length) % APP_THEME_KEYS.length];
    onChange(next);
    ref.current?.querySelector<HTMLButtonElement>(`[data-theme-key="${next}"]`)?.focus();
  };
  return (
    <div
      ref={ref}
      role="radiogroup"
      aria-label="테마"
      className="grid gap-2"
      style={{ gridTemplateColumns: "repeat(auto-fit,minmax(180px,1fr))" }}
    >
      {APP_THEME_KEYS.map((k) => (
        <ThemeCard
          key={k}
          themeKey={k}
          on={value === k}
          onSelect={() => onChange(k)}
          onKeyDown={(e) => {
            if (e.key === "ArrowRight" || e.key === "ArrowDown") { e.preventDefault(); move(k, 1); }
            else if (e.key === "ArrowLeft" || e.key === "ArrowUp") { e.preventDefault(); move(k, -1); }
          }}
        />
      ))}
    </div>
  );
}
