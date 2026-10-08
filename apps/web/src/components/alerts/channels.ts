"use client";

import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";

/** ADR-093 — GET /api/users/me/notification-preferences/channels 의 종류별 항목 */
export interface CategoryChannels {
  category: string;
  /** 끌 수 없음 — 설정·방해 금지 시간과 무관하게 즉시 푸시(닿지 않으면 이메일) */
  alwaysOn: boolean;
  push: boolean;
  email: boolean;
  /** 푸시가 어느 기기에도 닿지 않으면 이메일로 대신 */
  emailFallback: boolean;
  /** 알림 이력(/alerts)에 남는다 */
  inApp: boolean;
  /** 방해 금지 시간에도 푸시한다 */
  pushDuringQuietHours: boolean;
}

export interface DeliveryChannels {
  quietHours: { enabled: boolean; start: string; end: string; activeNow: boolean };
  marketingAgreed: boolean;
  kakaoAvailable: boolean;
  categories: CategoryChannels[];
}

/** 화면에 보이는 순서와 이름. 서버가 모르는 종류를 보내면 이름 그대로 뒤에 붙인다. */
export const CATEGORY_LABEL: Record<string, string> = {
  PRICE_ALERT: "가격 알림",
  VOLUME_SURGE: "거래량 급증",
  NEWS: "뉴스·공시",
  QUANT_SIGNAL: "퀀트 시그널",
  FILLS: "체결·정산",
  STRATEGY_MARKET: "전략 마켓 소식",
  RISK_WARNING: "리스크 경고",
  ORDER_OUTCOME: "‘결과 확인 중’ 주문",
  CONDITIONAL_ORDER: "조건부 주문 실패",
};
const ORDER = Object.keys(CATEGORY_LABEL);

export function sortCategories(list: CategoryChannels[]): CategoryChannels[] {
  const rank = (c: string) => (ORDER.indexOf(c) === -1 ? ORDER.length : ORDER.indexOf(c));
  return [...list].sort((a, b) => rank(a.category) - rank(b.category));
}

/** 한 종류가 어디로 가는지 — 칩에 그대로 쓴다. 아무 데도 안 가면 빈 배열. */
export function channelLabels(c: CategoryChannels): string[] {
  const out: string[] = [];
  if (c.push) out.push("푸시");
  if (c.email) out.push("이메일");
  else if (c.emailFallback) out.push("푸시 실패 시 이메일");
  if (c.inApp) out.push("알림 이력");
  return out;
}

/** 상단 통계 "전달 채널" — 실제로 쓰이는 채널 묶음. 방해 금지 중이면 표시한다. */
export function channelsSummary(d: DeliveryChannels | null | undefined): string {
  if (!d) return "—";
  const optional = d.categories.filter((c) => !c.alwaysOn);
  const used = [
    optional.some((c) => c.push) && "푸시",
    optional.some((c) => c.email || c.emailFallback) && "이메일",
  ].filter(Boolean) as string[];
  const base = used.length ? used.join(" · ") : "필수 알림만";
  return d.quietHours.activeNow ? `${base} (방해 금지 중)` : base;
}

export function quietHoursLabel(q: DeliveryChannels["quietHours"]): string {
  if (!q.enabled) return "꺼짐";
  return `${q.start}–${q.end}${q.activeNow ? " · 지금 적용 중" : ""}`;
}

export function useDeliveryChannels(enabled: boolean) {
  return useQuery<DeliveryChannels | null>({
    queryKey: ["notification", "channels"],
    queryFn: async () => {
      const r = await authFetch("/api/users/me/notification-preferences/channels");
      return r.ok ? r.json() : null;
    },
    enabled,
    // 방해 금지 "지금 적용 중"이 시간에 따라 바뀐다
    refetchInterval: 60_000,
  });
}
