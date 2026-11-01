"use client";

import { useQuery } from "@tanstack/react-query";
import type { BrokerageOrderResponse, RuleSet } from "@monticker/types";
import { authFetch } from "@/services/api";
import { getBrokerageAccount, getBrokerageOrders } from "@/services/brokerage";
import { useRiskExposure } from "@/components/risk/useRiskExposure";

const OPEN_LIVE: BrokerageOrderResponse["status"][] = ["PENDING_SUBMIT", "SUBMITTED", "PARTIALLY_FILLED", "UNKNOWN"];

export interface StatusSummary {
  /** 실행 중(포워드 테스트) 전략 수. 모르면 null */
  runningStrategies: number | null;
  /** 미체결 주문 수 — 모의는 매칭 엔진, 실전은 최근 실계좌 주문 50건 기준. 모르면 null */
  openOrders: number | null;
  /** 실전: 주문용 증권사 연동이 살아 있는지 */
  brokerLinked?: boolean;
  brokerProvider?: string;
}

/**
 * ADR-112 상태 알약 — 잔액 대신 "지금 진행 중인 것"(실행 중 전략·미체결)만 센다. 합친 API가 없어 쿼리 두 개를 쓴다.
 * 실계좌 주문은 증권사를 부르지 않는 저장 주문 목록이라 실전 화면에서만 읽는다.
 */
export function useStatusSummary(kind: "paper" | "live", isLoggedIn: boolean): StatusSummary {
  const live = kind === "live";
  // 퀀트랩 목록과 같은 키·같은 응답 처리 — 전략을 시작·중지하면 목록 무효화가 이 숫자도 갱신한다
  const rules = useQuery<RuleSet[]>({
    queryKey: ["quant", "rulesets"],
    queryFn: async () => {
      const res = await authFetch("/api/quant/rulesets");
      if (!res.ok) throw new Error("룰셋 목록 조회 실패");
      return res.json();
    },
    enabled: isLoggedIn,
    staleTime: 60_000,
  });
  const exposure = useRiskExposure(isLoggedIn && !live);
  const account = useQuery({ queryKey: ["brokerage", "account"], queryFn: getBrokerageAccount, enabled: isLoggedIn && live, staleTime: 60_000 });
  const orders = useQuery({
    queryKey: ["brokerage", "orders", "status-pill"],
    queryFn: () => getBrokerageOrders(0, 50),
    enabled: isLoggedIn && live,
    staleTime: 30_000,
    refetchInterval: 30_000,
  });

  const runningStrategies = rules.data ? rules.data.filter((r) => r.status === "RUNNING").length : null;
  if (live) {
    return {
      runningStrategies,
      openOrders: orders.data ? orders.data.content.filter((o) => OPEN_LIVE.includes(o.status)).length : null,
      brokerLinked: !!account.data?.isActive && !!account.data?.tokenValid,
      brokerProvider: account.data?.provider,
    };
  }
  return { runningStrategies, openOrders: exposure.data ? exposure.data.activeOrderCount : null };
}
