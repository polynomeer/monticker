"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

export interface RiskRuleResult { rule: string; passed: boolean; detail: string; current: number; limit: number; }
export interface RiskCheckResult { approved: boolean; blockedBy: string | null; severity: string; checks: RiskRuleResult[]; }

export interface RiskPreviewInput {
  stockId: number;
  side: "BUY" | "SELL";
  quantity: number;
  /** 지정가. 0이면 시장가 — 서버가 최근가로 판정한다. */
  estimatedPrice: number;
}

/** 입력이 멈춘 뒤 이만큼 기다렸다가 서버에 묻는다. 서버 한도(분당 60회)는 이 간격의 타이핑을 넉넉히 받는다. */
export const RISK_PREVIEW_DEBOUNCE_MS = 400;

/** 시장가 미리보기 기준가가 이만큼 넘게 움직이면 다시 판정한다. 집중도·손실 한도 판정은 이 안의 변동으로 거의 바뀌지 않는다. */
export const RISK_PREVIEW_PRICE_TOLERANCE = 0.01;

/** 기준가를 새로 잡을지 — 기준이 없거나 실시간가가 허용 폭을 넘게 벗어나면 실시간가로 옮긴다. */
export function nextAnchorPrice(anchor: number, live: number, tolerance = RISK_PREVIEW_PRICE_TOLERANCE): number {
  if (!(live > 0)) return anchor;
  if (!(anchor > 0)) return live;
  return Math.abs(live - anchor) / anchor > tolerance ? live : anchor;
}

/**
 * 시장가 주문의 미리보기용 가격. 실시간 현재가를 그대로 쓰면 틱마다(약 1초) 입력 키가 바뀌어 미리보기가 계속 나갔고,
 * 서버 한도(분당 60회)에 1분이면 걸렸다. [resetKey](종목·방향·수량·주문 유형 등 사용자 입력)가 바뀌면 그때의 현재가로 다시 잡는다.
 */
export function useAnchoredPrice(live: number, resetKey: string, tolerance = RISK_PREVIEW_PRICE_TOLERANCE): number {
  const [anchor, setAnchor] = useState(live);
  const lastKey = useRef(resetKey);
  useEffect(() => {
    if (lastKey.current !== resetKey) {
      lastKey.current = resetKey;
      if (live > 0) setAnchor(live);
      return;
    }
    const next = nextAnchorPrice(anchor, live, tolerance);
    if (next !== anchor) setAnchor(next);
  }, [live, resetKey, anchor, tolerance]);
  return anchor > 0 ? anchor : live;
}

/** 서버(RiskPreviewRequest)와 같은 검증 — 통과하지 못하면 요청하지 않는다. */
export function isPreviewable(i: RiskPreviewInput | null | undefined): i is RiskPreviewInput {
  return !!i
    && Number.isInteger(i.stockId) && i.stockId > 0
    && (i.side === "BUY" || i.side === "SELL")
    && Number.isInteger(i.quantity) && i.quantity > 0 && i.quantity <= 1_000_000
    && Number.isFinite(i.estimatedPrice) && i.estimatedPrice >= 0;
}

/** 값이 [delayMs] 동안 바뀌지 않으면 그 값을 돌려준다. 첫 값도 [delayMs] 뒤에 반영된다(그전엔 [initial]). */
export function useDebouncedValue<T>(value: T, delayMs: number, initial: T): T {
  const [debounced, setDebounced] = useState(initial);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(t);
  }, [value, delayMs]);
  return debounced;
}

export class RiskPreviewError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

export async function fetchRiskPreview(i: RiskPreviewInput): Promise<RiskCheckResult> {
  const res = await authFetch("/api/risk/preview", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(i),
  });
  if (!res.ok) {
    const e = await res.json().catch(() => null);
    const msg = res.status === 429
      ? "점검 요청이 많습니다. 잠시 뒤 입력을 바꾸면 다시 점검합니다."
      : e?.message ?? "리스크 점검 실패";
    throw new RiskPreviewError(res.status, msg);
  }
  return res.json();
}

/**
 * ADR-092 — 주문 입력이 바뀔 때마다(400ms 디바운스) 리스크 판정을 미리 본다. 서버는 감사 기록·메트릭을 남기지 않고,
 * 이 결과로는 아무것도 주문·예약되지 않는다(실제 주문은 제출할 때 게이트를 다시 돈다).
 *
 * [pending]은 화면의 입력이 아직 서버 결과에 반영되지 않았다는 뜻이다 — 디바운스 대기 중이거나 요청 중. 그동안 보이는 결과는
 * 직전 입력의 것이므로 흐리게 보여 준다.
 */
export function useRiskPreview(input: RiskPreviewInput | null, enabled: boolean) {
  const key = enabled && isPreviewable(input) ? JSON.stringify(input) : null;
  const debouncedKey = useDebouncedValue<string | null>(key, RISK_PREVIEW_DEBOUNCE_MS, null);
  const query = useQuery({
    queryKey: ["risk-preview", debouncedKey],
    queryFn: () => fetchRiskPreview(JSON.parse(debouncedKey!) as RiskPreviewInput),
    enabled: debouncedKey != null && debouncedKey === key,
    retry: false,
    staleTime: 15_000,
    gcTime: 60_000,
  });
  return {
    data: key != null ? query.data : undefined,
    error: key != null ? (query.error as Error | null) : null,
    pending: key != null && (key !== debouncedKey || query.isFetching),
    idle: key == null,
  };
}
