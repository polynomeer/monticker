import { describe, expect, it } from "vitest";
import { expiryInstant, isValidDays, lastValidDateLabel, VALID_DAYS_OPTIONS } from "@/lib/conditionalValidity";
import { agoText, fmtLatency, latencyStat } from "@/lib/brokerApiHealth";

describe("조건부 주문 유효 기간 — 서버 expiryFor와 같은 KST 날짜 규칙", () => {
  // 2026-10-08 23:30 KST = 14:30Z. UTC 날짜로 셌다면 하루가 어긋나지 않지만 00:30 KST에는 어긋난다.
  const lateNightKst = Date.parse("2026-10-08T14:30:00Z");
  const justAfterMidnightKst = Date.parse("2026-10-08T15:30:00Z"); // 10-09 00:30 KST

  it("N일 = KST 오늘+N일까지(브라우저 시간대와 무관)", () => {
    expect(lastValidDateLabel(1, lateNightKst)).toBe("10/09");
    expect(lastValidDateLabel(1, justAfterMidnightKst)).toBe("10/10");
    expect(lastValidDateLabel(90, lateNightKst)).toBe("01/06");
  });

  it("만료 시각은 그다음 날 00:00 KST — 서버 테스트와 같은 값", () => {
    expect(expiryInstant(1, lateNightKst)).toBe("2026-10-09T15:00:00.000Z");
    expect(expiryInstant(1, justAfterMidnightKst)).toBe("2026-10-10T15:00:00.000Z");
    expect(expiryInstant(30, lateNightKst)).toBe("2026-11-07T15:00:00.000Z");
  });

  it("선택지는 모두 서버 범위(1~90일) 안", () => {
    expect(VALID_DAYS_OPTIONS.every(isValidDays)).toBe(true);
    expect([0, 91, 1.5, -1].some(isValidDays)).toBe(false);
  });
});

describe("증권사 API 지연·마지막 오류 표시", () => {
  const now = Date.parse("2026-10-08T05:00:00Z");
  const base = {
    lastLatencyMs: 120, lastCallAt: "2026-10-08T04:59:00Z", lastOperation: "GET_BALANCE" as const,
    lastSuccessAt: "2026-10-08T04:59:00Z", lastErrorCode: null, lastErrorAt: null, lastErrorOperation: null,
  };

  it("관측이 없으면 지어내지 않고 —", () => {
    expect(latencyStat(null).value).toBe("—");
    expect(latencyStat(undefined).value).toBe("—");
  });

  it("ms·초 단위, 느리거나 마지막 호출이 오류면 주황", () => {
    expect(fmtLatency(120)).toBe("120ms");
    expect(fmtLatency(2500)).toBe("2.5s");
    expect(latencyStat(base, now)).toMatchObject({ value: "120ms", tone: undefined });
    expect(latencyStat({ ...base, lastLatencyMs: 2500 }, now).tone).toBe("text-dracula-orange");
    const failed = { ...base, lastErrorCode: "TIMEOUT" as const, lastErrorAt: base.lastCallAt, lastErrorOperation: "GET_BALANCE" as const };
    expect(latencyStat(failed, now).tone).toBe("text-dracula-orange");
    expect(latencyStat(failed, now).hint).toContain("응답 시간 초과");
  });

  it("지난 오류는 표시하되 이후 성공했으면 주황이 아니다", () => {
    const recovered = { ...base, lastErrorCode: "BROKER_5XX" as const, lastErrorAt: "2026-10-08T04:00:00Z", lastErrorOperation: "GET_ORDER_STATUS" as const };
    const s = latencyStat(recovered, now);
    expect(s.tone).toBeUndefined();
    expect(s.hint).toContain("증권사 서버 오류 (1시간 전)");
  });

  it("상대 시각", () => {
    expect(agoText("2026-10-08T04:59:30Z", now)).toBe("방금");
    expect(agoText("2026-10-08T04:50:00Z", now)).toBe("10분 전");
    expect(agoText("2026-10-06T05:00:00Z", now)).toBe("2일 전");
  });
});
