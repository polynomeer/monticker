"use client";

import { useState } from "react";
import type { WatchRuleEventType, WatchRuleResponse } from "@monticker/types";
import { Btn, IconBtn, Pill, Stat, Toggle } from "@/components/terminal";
import { cn } from "@/lib/utils";

export const EVENT_LABEL: Record<WatchRuleEventType, string> = {
  PRICE_SPIKE: "가격 급등",
  PRICE_DROP: "가격 급락",
  VOLUME_SURGE: "거래량 급증",
  QUANT_SIGNAL: "전략 신호",
};

function windowLabel(sec: number | null | undefined): string {
  const s = sec ?? 1800;
  if (s % 3600 === 0) return `${s / 3600}시간`;
  return `${Math.round(s / 60)}분`;
}

/** 감지 조건 한 줄 — 이벤트(+복합 조건) 또는 전략 신호 */
export function triggerLabel(rule: WatchRuleResponse): string {
  if (rule.eventType === "QUANT_SIGNAL") {
    return `${rule.ruleSetName ?? "전략"} ${rule.signalDirection === "SELL" ? "매도" : "매수"} 신호`;
  }
  const base = `${EVENT_LABEL[rule.eventType]} · 중요도 ${rule.minImportanceScore} 이상`;
  const req = rule.requiredEventTypes ?? [];
  return req.length ? `${base} + ${req.map((t) => EVENT_LABEL[t]).join(" + ")} (${windowLabel(rule.conditionWindowSec)} 내)` : base;
}

export function cooldownLabel(sec: number): string {
  if (sec <= 0) return "쿨다운 없음";
  if (sec % 3600 === 0) return `쿨다운 ${sec / 3600}시간`;
  if (sec % 60 === 0) return `쿨다운 ${sec / 60}분`;
  return `쿨다운 ${sec}초`;
}

interface Props {
  rule: WatchRuleResponse;
  stockLabel: string;
  onToggle: (rule: WatchRuleResponse) => void;
  onDelete: (rule: WatchRuleResponse) => void;
  pending: boolean;
  /** 이 규칙의 마지막 발동 시각(표시용 문자열) */
  lastFired?: string | null;
  /** 오늘 발동 횟수 — 서버 카운터(rule.todayExecutions)가 우선이다 */
  todayCount?: number;
  highlight?: boolean;
}

/** 시안 WatchRules 규칙 카드 — 감지 → 모의 주문 흐름, 마지막 발동, 켜기 토글. */
export function WatchRuleRow({ rule, stockLabel, onToggle, onDelete, pending, lastFired, todayCount = 0, highlight }: Props) {
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const title = rule.name ? `${rule.name} · ${stockLabel}` : `${stockLabel} · ${EVENT_LABEL[rule.eventType]}`;
  const today = rule.todayExecutions ?? todayCount;
  const limitReached = rule.dailyLimit != null && today >= rule.dailyLimit;

  return (
    <li className={cn("flex flex-wrap items-center gap-3.5 rounded-[10px] bg-tm-inner p-3.5", highlight && "outline outline-1 outline-dracula-purple", !rule.isActive && "opacity-70")}>
      <div className="flex min-w-0 flex-[1_1_280px] flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-bold">{title}</span>
          <Pill tone="muted">{rule.eventType === "QUANT_SIGNAL" ? "퀀트랩 전략" : (rule.requiredEventTypes?.length ? "복합 조건" : "직접 만든 규칙")}</Pill>
          {limitReached && rule.isActive && <Pill tone="orange">오늘 한도 도달</Pill>}
          {!rule.isActive && <Pill tone="yellow">중지됨</Pill>}
        </div>
        <div className="flex flex-wrap items-center gap-1.5 text-xs">
          <span className="rounded-md bg-tm-panel px-2 py-[3px] text-dracula-cyan">감지</span>
          <span className="text-tm-soft">{triggerLabel(rule)}</span>
          <span className="text-tm-muted" aria-hidden>→</span>
          <span className="rounded-md bg-tm-panel px-2 py-[3px] text-dracula-green">모의 주문</span>
          <span className="text-tm-soft">
            시장가 <span className={rule.side === "BUY" ? "text-up" : "text-down"}>{rule.side === "BUY" ? "매수" : "매도"}</span> · <span className="num">{rule.quantity}주</span> · {cooldownLabel(rule.cooldownSec)}
          </span>
        </div>
      </div>
      <div className="flex gap-[22px]">
        <Stat label="마지막 발동" value={lastFired ?? "—"} />
        <Stat label="오늘 발동 / 한도" value={`${today} / ${rule.dailyLimit ?? "∞"}`} />
      </div>
      <div className="flex items-center gap-1.5">
        <Toggle checked={rule.isActive} label={`${title} 켜기`} disabled={pending} onChange={() => onToggle(rule)} />
        {confirmingDelete ? (
          <>
            <Btn kind="danger" size="sm" onClick={() => onDelete(rule)} disabled={pending}>삭제</Btn>
            <Btn kind="ghost" size="sm" onClick={() => setConfirmingDelete(false)}>취소</Btn>
          </>
        ) : (
          <IconBtn name="trash" label={`${stockLabel} 규칙 삭제`} size={30} onClick={() => setConfirmingDelete(true)} className="hover:text-[#ff8a8a]" />
        )}
      </div>
    </li>
  );
}
