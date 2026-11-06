import { useEffect, useState } from "react";
import { authFetch } from "./auth";
import type { RiskCheckResult } from "@/lib/risk";

/** 입력이 멈춘 뒤 이만큼 기다렸다가 묻는다(웹과 같은 값 — 서버 한도 분당 60회) */
const DEBOUNCE_MS = 400;

export interface RiskPreviewInput { stockId: number; side: "BUY" | "SELL"; quantity: number; estimatedPrice: number }

/**
 * 모의 주문 전 리스크 미리보기(POST /api/risk/preview, ADR-092). 아무것도 주문·예약하지 않는다.
 * 실전 주문에는 쓰지 않는다 — 모의 데이터로 실전 리스크를 판정하지 않는다(ADR-025).
 */
export function useRiskPreview(input: RiskPreviewInput | null) {
  return usePreview("/api/risk/preview", input);
}

export interface LiveRiskPreviewInput { symbol: string; side: "BUY" | "SELL"; quantity: number; limitPrice: number | null }

/**
 * 실전 주문 전 리스크 미리보기(POST /api/brokerage/risk-preview, ADR-116). 증권사 잔고로 판정하고 아무것도 기록하지 않는다.
 * 결과가 제출 때 판정보다 엄할 수 있어 제출을 막는 데 쓰지 않는다.
 */
export function useLiveRiskPreview(input: LiveRiskPreviewInput | null) {
  return usePreview("/api/brokerage/risk-preview", input);
}

function usePreview(path: string, input: object | null) {
  const [data, setData] = useState<RiskCheckResult | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const key = input ? JSON.stringify(input) : "";

  useEffect(() => {
    if (!input) { setData(null); setPending(false); setError(null); return; }
    setPending(true);
    let alive = true;
    const t = setTimeout(async () => {
      try {
        const r = await authFetch(path, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(input),
        });
        if (!alive) return;
        if (r.ok) { setData((await r.json()) as RiskCheckResult); setError(null); }
        else setError(r.status === 429 ? "잠시 뒤 다시 점검합니다." : "리스크를 점검하지 못했어요. 주문할 때 서버가 다시 판정합니다.");
      } catch {
        if (alive) setError("리스크를 점검하지 못했어요. 주문할 때 서버가 다시 판정합니다.");
      } finally {
        if (alive) setPending(false);
      }
    }, DEBOUNCE_MS);
    return () => { alive = false; clearTimeout(t); };
    // input 객체 대신 내용(key)이 바뀔 때만
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, key]);

  return { data, pending, error };
}
