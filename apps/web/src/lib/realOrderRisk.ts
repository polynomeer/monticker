import type { BrokerageBalanceResponse, BrokerageOrderSide } from "@monticker/types";

/** 실전 주문 화면의 주문 전 리스크 행. `server`는 화면이 판정하지 못하고 주문할 때 서버가 판정하는 규칙이다. */
export interface RealRiskRow {
  rule: string;
  label: string;
  state: "ok" | "fail" | "server" | "wait";
  /** 실패 이유 — 제출 버튼 위에 그대로 보여 준다 */
  reason?: string;
}

export interface RealRiskLimits {
  concentrationLimitPct: number;
  dailyLossLimitPct: number;
  varLimitPct: number;
  maxPositionCount: number;
  maxHourlyOrders: number;
  sectorConcentrationLimitPct: number | null;
}

const pct = (v: number) => `${Number(v.toFixed(1))}%`;

/**
 * 실전(BYOK) 주문의 주문 전 리스크 행.
 *
 * 왜 `/api/risk/preview`(useRiskPreview)를 쓰지 않는가: 미리보기는 **모의계좌** 상태(paper_accounts·paper_trades)로 판정하고,
 * 모의투자 "리스크 체크 끄기"(isActive)도 따른다(RiskCheckerService.preview). 실주문 게이트는 증권사 잔고로 만든
 * 스냅샷을 쓰고 isActive를 보지 않는다(checkBrokerageOrder, ADR-025·ADR-069). 미리보기 결과를 여기 보이면 모의계좌 기준
 * "통과"가 실계좌 통과처럼 읽힌다 — 실거래 판정에 모의 데이터를 쓰지 않는다는 ADR-025의 원칙을 화면이 깨게 된다.
 * 실계좌 스냅샷을 받는 미리보기 API가 생기기 전까지는:
 *
 * - **종목 집중도**만 화면이 판정한다. 서버와 같은 데이터(증권사 잔고의 총평가액·이 종목 보유 수량)와 같은 식이고, 서버는
 *   여기에 진행 중 매수(ADR-058)를 더하므로 화면 판정은 서버보다 느슨할 수는 있어도 엄하지는 않다 — 서버가 통과시킬 주문을
 *   화면이 막지 않는다.
 * - 일간 손실·VaR·보유 종목 수·시간당 주문 수·섹터 비중은 서버만 아는 값(주문 원장·종목 매핑·일봉)이 필요해 "서버 검증"으로
 *   한도만 보여 준다. 최종 방어선은 그대로 제출 시 서버 리스크 게이트(422)다.
 * - 한도(limits)는 사용자 단위 유효 한도(GET /api/risk/exposure의 limits)라 모의·실전이 같다.
 */
export function realOrderRiskRows(input: {
  side: BrokerageOrderSide;
  quantity: number;
  /** 지정가 또는 시장가 추정가(현재가). 0이면 모른다. */
  price: number;
  symbol: string;
  balance: BrokerageBalanceResponse | undefined;
  limits: RealRiskLimits | undefined;
}): RealRiskRow[] {
  const { side, quantity, price, symbol, balance, limits } = input;
  const rows: RealRiskRow[] = [];
  const isBuy = side === "BUY";

  if (isBuy) {
    if (!limits || !balance) {
      rows.push({ rule: "ConcentrationRule", label: "종목 집중도", state: "wait" });
    } else if (!(price > 0)) {
      // 서버는 최근가로 판정하고, 그것도 없으면 보수적으로 거부한다.
      rows.push({ rule: "ConcentrationRule", label: `종목 집중도 한도 ${pct(limits.concentrationLimitPct)}`, state: "server" });
    } else if (!(balance.totalEvaluated > 0)) {
      const reason = "증권사 총평가액을 확인할 수 없어 집중도를 판정할 수 없습니다.";
      rows.push({ rule: "ConcentrationRule", label: "종목 집중도", state: "fail", reason });
    } else {
      // 서버와 같이 수량 0 행은 빼고, 한 종목이 여러 행으로 오면 합친다.
      const held = balance.holdings.filter((h) => h.symbol === symbol && h.quantity > 0).reduce((a, h) => a + h.quantity, 0);
      const after = ((held + quantity) * price) / balance.totalEvaluated * 100;
      const limit = limits.concentrationLimitPct;
      const passed = after <= limit;
      rows.push({
        rule: "ConcentrationRule",
        label: `종목 집중도 ${pct(after)} / 한도 ${pct(limit)}`,
        state: passed ? "ok" : "fail",
        reason: passed ? undefined : `주문 후 이 종목 비중이 ${pct(after)}로 종목 집중도 한도 ${pct(limit)}를 넘습니다.`,
      });
    }
  }

  if (limits) {
    if (isBuy) {
      rows.push({ rule: "DailyLossRule", label: `일간 손실 한도 ${pct(limits.dailyLossLimitPct)}`, state: "server" });
      rows.push({ rule: "VaRRule", label: `1일 VaR 95% 한도 ${pct(limits.varLimitPct)}`, state: "server" });
      rows.push({ rule: "PositionCountRule", label: `보유 종목 수 한도 ${limits.maxPositionCount}개`, state: "server" });
      if (limits.sectorConcentrationLimitPct != null) {
        rows.push({ rule: "SectorConcentrationRule", label: `섹터 비중 한도 ${pct(limits.sectorConcentrationLimitPct)}`, state: "server" });
      }
    }
    rows.push({ rule: "TradingFrequencyRule", label: `시간당 주문 한도 ${limits.maxHourlyOrders}건`, state: "server" });
  }
  return rows;
}
