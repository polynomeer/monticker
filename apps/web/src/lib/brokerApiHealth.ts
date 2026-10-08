import type { BrokerCallOperation, BrokerErrorCode, BrokerageApiHealth } from "@monticker/types";

/** 서버가 주는 고정 오류 코드 → 화면 문구. 증권사 원문 메시지는 서버가 내보내지 않는다. */
export const BROKER_ERROR_LABEL: Record<BrokerErrorCode, string> = {
  TIMEOUT: "응답 시간 초과",
  NETWORK: "네트워크 오류",
  CIRCUIT_OPEN: "증권사 장애로 호출 일시 중단",
  BROKER_UNAVAILABLE: "증권사 응답 없음",
  AUTH_FAILED: "인증 실패",
  RATE_LIMITED: "호출 한도 초과",
  BROKER_4XX: "요청 거부",
  BROKER_5XX: "증권사 서버 오류",
  SUBMIT_INDETERMINATE: "주문 결과 확인 중",
  ORDER_REJECTED: "주문 거부",
  LOOKUP_FAILED: "조회 실패",
  UNKNOWN: "알 수 없는 오류",
};

export const BROKER_OPERATION_LABEL: Record<BrokerCallOperation, string> = {
  SUBMIT_ORDER: "주문",
  CANCEL_ORDER: "취소",
  GET_ORDER_STATUS: "주문 상태",
  GET_SETTLEMENTS: "정산",
  FIND_ORDERS: "주문 조회",
  GET_BALANCE: "잔고",
};

/** 지연시간 표기 — 1초 미만은 ms, 이상은 초(소수 첫째 자리). */
export function fmtLatency(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return "—";
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
}

/** 이만큼 넘게 걸린 호출은 주황으로 — 증권사 읽기 타임아웃(5s)의 절반쯤. */
export const SLOW_LATENCY_MS = 2_000;

export interface LatencyStat {
  label: string;
  value: string;
  tone?: string;
  hint?: string;
}

/**
 * 상단 바의 "API 지연" 칸. 관측이 없으면 `—`(재시작 직후거나 다른 서버가 처리했다). 값은 이 서버가 마지막으로 본 한 번의
 * 호출이다 — 평균이 아니다.
 */
export function latencyStat(health: BrokerageApiHealth | null | undefined, now = Date.now()): LatencyStat {
  if (!health) return { label: "API 지연", value: "—", hint: "아직 관측한 증권사 호출이 없습니다." };
  const recentError = health.lastErrorAt != null && Date.parse(health.lastErrorAt) >= Date.parse(health.lastCallAt);
  const hint = [
    `마지막 호출: ${BROKER_OPERATION_LABEL[health.lastOperation] ?? health.lastOperation} · ${fmtLatency(health.lastLatencyMs)}`,
    health.lastErrorCode && health.lastErrorAt
      ? `마지막 오류: ${BROKER_ERROR_LABEL[health.lastErrorCode] ?? health.lastErrorCode} (${agoText(health.lastErrorAt, now)})`
      : "오류 없음",
  ].join("\n");
  return {
    label: "API 지연",
    value: fmtLatency(health.lastLatencyMs),
    tone: recentError ? "text-dracula-orange" : health.lastLatencyMs >= SLOW_LATENCY_MS ? "text-dracula-orange" : undefined,
    hint,
  };
}

/** "3분 전" 같은 상대 시각. 미래(시계 차이)는 "방금". */
export function agoText(iso: string, now = Date.now()): string {
  const s = Math.floor((now - Date.parse(iso)) / 1000);
  if (!Number.isFinite(s) || s < 60) return "방금";
  if (s < 3600) return `${Math.floor(s / 60)}분 전`;
  if (s < 86_400) return `${Math.floor(s / 3600)}시간 전`;
  return `${Math.floor(s / 86_400)}일 전`;
}
