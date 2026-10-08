import { describe, expect, it } from "vitest";
import { matchesFilter, ruleMeta } from "@/components/alerts/data";
import { countStat } from "@/components/quant/signalSummary";

describe("알림 이력 시그널 탭 (ADR-090)", () => {
  const signal = { ruleType: "QUANT_SIGNAL", readAt: null };
  const price = { ruleType: "PRICE_ABOVE", readAt: "2026-10-08T01:00:00Z" };

  it("퀀트 시그널 이력은 시그널 탭에만 분류된다", () => {
    expect(ruleMeta("QUANT_SIGNAL")).toMatchObject({ category: "signal", tag: "시그널" });
    expect(matchesFilter(signal, "signal")).toBe(true);
    expect(matchesFilter(signal, "event")).toBe(false);
    expect(matchesFilter(price, "signal")).toBe(false);
  });

  it("전체·읽지 않음 탭에도 시그널이 함께 보인다", () => {
    expect(matchesFilter(signal, "all")).toBe(true);
    expect(matchesFilter(signal, "unread")).toBe(true);
    expect(matchesFilter(price, "unread")).toBe(false);
  });
});

describe("퀀트랩 상단 집계 표시", () => {
  it("값이 있으면 단위를 붙인다(0도 실제 값)", () => {
    expect(countStat(3, "건")).toBe("3건");
    expect(countStat(0, "개")).toBe("0개");
  });

  it("불러오는 중·실패면 지어낸 숫자 대신 —", () => {
    expect(countStat(undefined, "건")).toBe("—");
    expect(countStat(null, "개")).toBe("—");
    expect(countStat(Number.NaN, "건")).toBe("—");
  });
});
