"use client";

import { useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";
import { IconBtn, PreviewTag } from "./ui";
import type { IconName } from "./Icon";

export interface PanelTab {
  key: string;
  label: ReactNode;
}

type PanelAction = "plus" | "sliders" | "expand" | "download" | "dots" | "refresh";

const ACTION_LABEL: Record<PanelAction, string> = {
  plus: "패널 탭 추가",
  sliders: "패널 설정",
  expand: "패널 확대",
  download: "내보내기",
  dots: "더 보기",
  refresh: "새로고침",
};

interface PanelProps {
  /** 탭 하나면 제목처럼 보인다. 배열을 주면 탭으로 전환된다. */
  tabs: (string | PanelTab)[];
  /** 제어 모드: 현재 탭 key */
  active?: string;
  onTabChange?: (key: string) => void;
  /** 비제어 모드에서 탭별 내용 — 없으면 children을 그대로 그린다 */
  render?: (activeKey: string) => ReactNode;
  children?: ReactNode;
  actions?: PanelAction[];
  onAction?: (a: PanelAction) => void;
  /** 헤더 오른쪽(액션 아이콘 왼쪽)에 붙는 요소 */
  right?: ReactNode;
  /** 시안에만 있고 기능이 아직 없는 패널 */
  preview?: boolean;
  closable?: boolean;
  className?: string;
  bodyClassName?: string;
}

function norm(t: string | PanelTab): PanelTab {
  return typeof t === "string" ? { key: t, label: t } : t;
}

/**
 * 탭 헤더가 달린 터미널 패널 — 시안의 panel(). 활성 탭은 raised 배경 + 닫기(×),
 * 오른쪽에 설정/확대 아이콘. 패널 사이 간격은 부모가 gap-2(8px)로 준다.
 */
export function Panel({
  tabs, active, onTabChange, render, children, actions = ["sliders", "expand"], onAction, right, preview, closable = true, className, bodyClassName,
}: PanelProps) {
  const list = tabs.map(norm);
  const [inner, setInner] = useState(list[0]?.key ?? "");
  const current = active ?? inner;
  const select = (k: string) => {
    if (onTabChange) onTabChange(k);
    else setInner(k);
  };
  const multi = list.length > 1;

  return (
    <section className={cn("flex min-w-0 flex-col rounded-[10px] bg-tm-panel", className)}>
      <div className="flex items-center gap-1.5 border-b border-tm-line px-2 py-1.5">
        <div role={multi ? "tablist" : undefined} className="flex min-w-0 flex-wrap items-center gap-0.5">
          {list.map((t) => {
            const on = t.key === current;
            if (!multi) {
              return (
                <span key={t.key} className="inline-flex h-[30px] items-center gap-0.5 rounded-md bg-tm-raised pl-2.5 pr-2.5 text-13 font-semibold">
                  {t.label}
                </span>
              );
            }
            return on ? (
              <span key={t.key} className={cn("inline-flex h-[30px] items-center gap-0.5 rounded-md bg-tm-raised pl-2.5", closable ? "pr-1.5" : "pr-2.5")}>
                <button type="button" role="tab" aria-selected className="whitespace-nowrap text-13 font-semibold text-dracula-fg">
                  {t.label}
                </button>
                {closable && <span aria-hidden className="grid h-5 w-5 place-items-center text-tm-muted">×</span>}
              </span>
            ) : (
              <button
                key={t.key}
                type="button"
                role="tab"
                aria-selected={false}
                onClick={() => select(t.key)}
                className="h-[30px] whitespace-nowrap rounded-md px-2.5 text-13 text-tm-muted hover:text-dracula-fg"
              >
                {t.label}
              </button>
            );
          })}
          {actions.includes("plus") && <IconBtn name="plus" label={ACTION_LABEL.plus} size={28} iconSize={14} onClick={() => onAction?.("plus")} />}
          {preview && <PreviewTag className="ml-1" />}
        </div>
        <div className="ml-auto flex items-center gap-2">
          {right}
          <div className="flex">
            {actions
              .filter((a) => a !== "plus")
              .map((a) => (
                <IconBtn key={a} name={a as IconName} label={ACTION_LABEL[a]} size={28} iconSize={15} onClick={() => onAction?.(a)} />
              ))}
          </div>
        </div>
      </div>
      <div className={cn("flex min-h-0 min-w-0 flex-1 flex-col gap-3 p-3.5", bodyClassName)}>{render ? render(current) : children}</div>
    </section>
  );
}

/** 패널 가로 배치 — 좁아지면 줄바꿈. 자식에 flex 기준값(basis)을 className으로 준다. */
export function PanelRow({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("flex flex-wrap items-stretch gap-2", className)}>{children}</div>;
}

export function PanelCol({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("flex min-w-0 flex-col gap-2", className)}>{children}</div>;
}
