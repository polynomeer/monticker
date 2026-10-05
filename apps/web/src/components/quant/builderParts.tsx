// 룰셋 빌더 캔버스의 조건 블록 — 시안 builder()의 cond()/join() 칩 모양.
"use client";

import { useEffect, useState, type ComponentProps, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { Field, Icon, IconBtn } from "@/components/terminal";

/** 칩 모양 네이티브 select */
export function ChipSelect({ className, colorClass = "text-dracula-fg", children, ...rest }: { colorClass?: string } & ComponentProps<"select">) {
  return (
    <span className={cn("relative inline-flex h-9 items-center rounded-lg border border-tm-line2 bg-tm-inner", className)}>
      <select className={cn("h-full appearance-none bg-transparent pl-3 pr-8 text-13 outline-none [&>option]:bg-tm-panel [&>option]:text-dracula-fg", colorClass)} {...rest}>
        {children}
      </select>
      <Icon name="chev" size={13} className="pointer-events-none absolute right-2.5 text-tm-muted" />
    </span>
  );
}

/** 칩 모양 숫자 입력 */
export function ChipNumber({ suffix, className, ...rest }: { suffix?: string } & ComponentProps<"input">) {
  return (
    <span className={cn("inline-flex h-9 items-center gap-1 rounded-lg border border-tm-line2 bg-tm-inner px-3 focus-within:border-dracula-purple", className)}>
      <input type="number" className="num w-14 bg-transparent text-13 text-dracula-fg outline-none" {...rest} />
      {suffix && <span className="text-xs text-tm-muted">{suffix}</span>}
    </span>
  );
}

export function CondShell({ children, onRemove }: { children: ReactNode; onRemove: () => void }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span aria-hidden className="grid text-tm-muted">
        <Icon name="dots" size={16} />
      </span>
      {children}
      <IconBtn name="x" label="조건 삭제" size={32} iconSize={14} onClick={onRemove} className="ml-auto" />
    </div>
  );
}

export function JoinTag({ op }: { op: string }) {
  return <span className="ml-[22px] self-start rounded-md bg-tm-raised px-2 py-0.5 text-2xs font-bold tracking-[0.08em] text-tm-muted">{op}</span>;
}

/**
 * 숫자 Field — 입력 중 "-" 같은 중간 상태를 지우지 않도록 문자열 상태를 따로 들고,
 * 숫자로 읽히는 순간에만 올린다. 빈칸이면 null.
 */
export function NumField({
  value, onCommit, ...rest
}: { value: number | null | undefined; onCommit: (v: number | null) => void } & Omit<ComponentProps<typeof Field>, "value" | "onChange">) {
  const [s, setS] = useState(value == null ? "" : String(value));
  useEffect(() => {
    setS((cur) => (cur.trim() !== "" && parseFloat(cur) === value ? cur : value == null ? "" : String(value)));
  }, [value]);
  return (
    <Field
      inputMode="decimal"
      value={s}
      onChange={(e) => {
        const t = e.target.value;
        setS(t);
        if (t.trim() === "") onCommit(null);
        else if (!Number.isNaN(parseFloat(t))) onCommit(parseFloat(t));
      }}
      {...rest}
    />
  );
}
