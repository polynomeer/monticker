import { describe, expect, it } from "vitest";
import {
  addDays, isBusinessDay, kstWeek, kstYmd, nextBusinessDays, previousBusinessDays, sessionLabel, shiftBusinessDays, type MarketStatus,
} from "@/components/market/krxCalendar";

const CHUSEOK = new Set(["2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09"]);

function status(over: Partial<MarketStatus>): MarketStatus {
  return {
    market: "KRX", status: "OPEN", date: "2026-10-07", isTradingDay: true, holidayName: null,
    openAt: "2026-10-07T09:00:00+09:00", closeAt: "2026-10-07T15:30:00+09:00",
    nextOpen: "2026-10-08T09:00:00+09:00", nextClose: "2026-10-07T15:30:00+09:00",
    calendarCovered: true, calendarCoverageUntil: "2027-12-31", ...over,
  };
}

describe("krxCalendar 날짜 계산", () => {
  it("KST 날짜는 브라우저 시간대와 무관하다", () => {
    expect(kstYmd(new Date("2026-10-06T15:30:00Z"))).toBe("2026-10-07");
    expect(addDays("2026-12-31", 1)).toBe("2027-01-01");
  });

  it("휴장일과 주말을 건너뛴다", () => {
    expect(isBusinessDay("2026-09-25", CHUSEOK)).toBe(false);
    expect(isBusinessDay("2026-09-25")).toBe(true); // 캘린더 없으면 주말만
    expect(nextBusinessDays(3, "2026-09-23", CHUSEOK)).toEqual(["2026-09-23", "2026-09-28", "2026-09-29"]);
    expect(previousBusinessDays(3, "2026-10-06", CHUSEOK)).toEqual(["2026-10-01", "2026-10-02", "2026-10-06"]);
    expect(shiftBusinessDays("2026-10-02", 2, CHUSEOK)).toBe("2026-10-07");
    expect(shiftBusinessDays("2026-10-07", -2, CHUSEOK)).toBe("2026-10-02");
  });

  it("이번 주는 KST 월~일", () => {
    expect(kstWeek("2026-10-07")).toEqual({ from: "2026-10-05", to: "2026-10-11" });
    expect(kstWeek("2026-10-11")).toEqual({ from: "2026-10-05", to: "2026-10-11" });
  });
});

describe("sessionLabel", () => {
  it("서버 상태가 있으면 오늘의 개장 시각으로 단계를 다시 계산한다", () => {
    expect(sessionLabel(status({}), new Date("2026-10-07T01:00:00Z")).text).toBe("정규장 · 10:00");
    expect(sessionLabel(status({}), new Date("2026-10-06T23:40:00Z")).text).toBe("장 시작 전 · 08:40");
    expect(sessionLabel(status({}), new Date("2026-10-07T07:00:00Z")).open).toBe(false);
    // 연초 첫 거래일 10:00 개장 — 09:30은 아직 장 시작 전
    const firstDay = status({ date: "2027-01-04", openAt: "2027-01-04T10:00:00+09:00" });
    expect(sessionLabel(firstDay, new Date("2027-01-04T00:30:00Z")).phase).toBe("PRE");
  });

  it("휴장일은 이름을 보여준다", () => {
    const s = sessionLabel(status({ date: "2026-10-09", isTradingDay: false, holidayName: "한글날", status: "CLOSED" }), new Date("2026-10-09T01:00:00Z"));
    expect(s.text).toBe("휴장 · 한글날");
    expect(s.open).toBe(false);
  });

  it("캘린더가 없는 해는 표시한다", () => {
    expect(sessionLabel(status({ calendarCovered: false }), new Date("2026-10-07T01:00:00Z")).text).toBe("정규장 · 10:00 · 휴장일 미확인");
  });

  it("서버 실패·지난 날짜면 평일 09:00–15:30 규칙으로 대신한다", () => {
    expect(sessionLabel(null, new Date("2026-10-09T01:00:00Z")).text).toBe("정규장 · 10:00"); // 한글날이지만 모른다
    expect(sessionLabel(status({ date: "2026-10-06" }), new Date("2026-10-10T01:00:00Z")).text).toBe("장 마감 · 10:00"); // 토요일
  });
});
