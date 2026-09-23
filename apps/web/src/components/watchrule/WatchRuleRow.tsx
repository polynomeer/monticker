"use client";

import { useState } from "react";
import { Trash, TrendUp, TrendDown, ChartBar } from "@phosphor-icons/react";
import type { WatchRuleEventType, WatchRuleResponse } from "@monticker/types";
import { Badge } from "@/components/ui/Badge";

export const EVENT_LABEL: Record<WatchRuleEventType, string> = {
  PRICE_SPIKE: "가격 급등",
  PRICE_DROP: "가격 급락",
  VOLUME_SURGE: "거래량 급증",
};

const EVENT_ICON: Record<WatchRuleEventType, typeof TrendUp> = {
  PRICE_SPIKE: TrendUp,
  PRICE_DROP: TrendDown,
  VOLUME_SURGE: ChartBar,
};

function cooldownLabel(sec: number): string {
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
}

export function WatchRuleRow({ rule, stockLabel, onToggle, onDelete, pending }: Props) {
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const Icon = EVENT_ICON[rule.eventType];

  return (
    <li className="flex items-start justify-between gap-3 px-4 py-3 hover:bg-gray-50 dark:hover:bg-dracula-line/10 transition-colors">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <Icon size={16} weight="duotone" className="text-gray-500 dark:text-dracula-comment shrink-0" aria-hidden />
          <span className="text-sm font-semibold text-gray-900 dark:text-dracula-fg">{stockLabel}</span>
          <Badge variant="info">{EVENT_LABEL[rule.eventType]}</Badge>
          <Badge variant={rule.side === "BUY" ? "up" : "down"}>
            {rule.side === "BUY" ? "매수" : "매도"} {rule.quantity}주
          </Badge>
          {!rule.isActive && <Badge variant="neutral">중지됨</Badge>}
        </div>
        <p className="mt-1 text-xs text-gray-500 dark:text-dracula-comment">
          중요도 {rule.minImportanceScore} 이상 · {cooldownLabel(rule.cooldownSec)}
        </p>
      </div>

      <div className="flex shrink-0 items-center gap-1.5">
        <button
          type="button"
          onClick={() => onToggle(rule)}
          disabled={pending}
          className="rounded-md border border-gray-300 px-2 py-1 text-xs font-semibold text-gray-700 transition-colors hover:border-gray-400 disabled:opacity-50 dark:border-dracula-line dark:text-dracula-fg dark:hover:border-dracula-comment"
        >
          {rule.isActive ? "중지" : "재개"}
        </button>
        {confirmingDelete ? (
          <>
            <button
              type="button"
              onClick={() => onDelete(rule)}
              disabled={pending}
              className="rounded-md border border-market-down/40 px-2 py-1 text-xs font-semibold text-market-down transition-colors hover:bg-market-down/10 disabled:opacity-50"
            >
              삭제
            </button>
            <button
              type="button"
              onClick={() => setConfirmingDelete(false)}
              className="rounded-md px-2 py-1 text-xs text-gray-500 dark:text-dracula-comment"
            >
              취소
            </button>
          </>
        ) : (
          <button
            type="button"
            onClick={() => setConfirmingDelete(true)}
            aria-label={`${stockLabel} 규칙 삭제`}
            className="rounded-md p-1.5 text-gray-400 transition-colors hover:text-market-down dark:text-dracula-comment"
          >
            <Trash size={16} aria-hidden />
          </button>
        )}
      </div>
    </li>
  );
}
