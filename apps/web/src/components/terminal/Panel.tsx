"use client";

import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
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
  // 패널별 설정은 패널마다 내용이 달라 키트에서 일반화할 수 없다 — 아이콘은 비활성(준비 중)으로 둔다
  sliders: "패널 설정 (준비 중)",
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

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/**
 * 패널 확대 — 패널 자체를 화면 위 고정 레이어로 띄운다(내용을 다시 마운트하지 않아 차트·입력 상태가 그대로).
 * Esc·닫기 버튼·배경 클릭으로 닫고, 열려 있는 동안 Tab 포커스를 패널 안에 가두며, 닫으면 확대 버튼으로 포커스를 돌려준다.
 */
function usePanelExpand() {
  const [expanded, setExpanded] = useState(false);
  const sectionRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  const open = () => {
    returnFocusRef.current = (document.activeElement as HTMLElement | null) ?? null;
    setExpanded(true);
  };
  const close = () => setExpanded(false);

  useEffect(() => {
    if (!expanded) return;
    closeRef.current?.focus();
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    // 차트 어댑터는 window resize로 폭을 다시 잰다
    window.dispatchEvent(new Event("resize"));
    // 포커스가 패널 밖(body)으로 빠진 경우에도 Esc로 닫히게 문서 단위로 듣는다
    const onEsc = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape") setExpanded(false);
    };
    document.addEventListener("keydown", onEsc);
    return () => {
      document.removeEventListener("keydown", onEsc);
      document.body.style.overflow = prevOverflow;
      window.dispatchEvent(new Event("resize"));
      const back = returnFocusRef.current;
      returnFocusRef.current = null;
      if (back && document.contains(back)) back.focus();
    };
  }, [expanded]);

  const onKeyDown = (e: KeyboardEvent<HTMLElement>) => {
    if (!expanded) return;
    if (e.key !== "Tab" || !sectionRef.current) return;
    const items = Array.from(sectionRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => el.getAttribute("aria-disabled") !== "true");
    if (items.length === 0) return;
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (e.shiftKey && (active === first || !sectionRef.current.contains(active))) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && (active === last || !sectionRef.current.contains(active))) {
      e.preventDefault();
      first.focus();
    }
  };

  return { expanded, open, close, sectionRef, closeRef, onKeyDown };
}

function plainLabel(label: ReactNode): string | undefined {
  return typeof label === "string" || typeof label === "number" ? String(label) : undefined;
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
  const { expanded, open, close, sectionRef, closeRef, onKeyDown } = usePanelExpand();
  const titleText = plainLabel(list.find((t) => t.key === current)?.label) ?? plainLabel(list[0]?.label);

  return (
    <>
    {expanded && (
      // 배경 — 클릭하면 닫힌다. 확대된 패널은 원래 자리를 비우므로 자리 표시를 남겨 레이아웃이 튀지 않게 한다
      <div aria-hidden className="fixed inset-0 z-[60] bg-black/60" onClick={close} />
    )}
    {expanded && <div aria-hidden className={cn("min-h-[120px] rounded-[10px] border border-dashed border-tm-line", className)} />}
    <section
      ref={sectionRef}
      role={expanded ? "dialog" : undefined}
      aria-modal={expanded ? true : undefined}
      aria-label={expanded ? `${titleText ?? "패널"} 확대` : undefined}
      onKeyDown={onKeyDown}
      className={cn(
        "flex min-w-0 flex-col rounded-[10px] bg-tm-panel",
        expanded
          ? "fixed inset-2 z-[61] overflow-auto shadow-glow-line sm:inset-6"
          : className,
      )}
    >
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
              .map((a) => {
                if (a === "expand") {
                  return expanded ? (
                    <IconBtn key={a} ref={closeRef} name="x" label="확대 닫기" size={28} iconSize={15} onClick={close} />
                  ) : (
                    <IconBtn key={a} name="expand" label={ACTION_LABEL.expand} aria-haspopup="dialog" size={28} iconSize={15} onClick={open} />
                  );
                }
                if (a === "sliders") {
                  return (
                    <IconBtn
                      key={a}
                      name="sliders"
                      label={ACTION_LABEL.sliders}
                      aria-disabled="true"
                      size={28}
                      iconSize={15}
                      className="cursor-not-allowed opacity-50 hover:bg-transparent hover:text-tm-muted"
                    />
                  );
                }
                return <IconBtn key={a} name={a as IconName} label={ACTION_LABEL[a]} size={28} iconSize={15} onClick={() => onAction?.(a)} />;
              })}
          </div>
        </div>
      </div>
      <div className={cn("flex min-h-0 min-w-0 flex-1 flex-col gap-3 p-3.5", bodyClassName)}>{render ? render(current) : children}</div>
    </section>
    </>
  );
}

/** 패널 가로 배치 — 좁아지면 줄바꿈. 자식에 flex 기준값(basis)을 className으로 준다. */
export function PanelRow({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("flex flex-wrap items-stretch gap-2", className)}>{children}</div>;
}

export function PanelCol({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("flex min-w-0 flex-col gap-2", className)}>{children}</div>;
}
