/**
 * 백테스트 기본 구간 — 종료일은 KST 기준 어제(오늘 일봉은 장 마감 전이면 아직 확정되지 않는다), 시작일은 그보다 [days]일 앞.
 * 예전엔 화면마다 날짜를 박아 두어("2026-06-01" 등) 시간이 지나면 최근 몇 달이 기본 구간에서 빠졌다.
 * 브라우저 시간대와 무관하게 KST 날짜로 계산한다.
 */
const KST_OFFSET_MS = 9 * 3_600_000;
const DAY_MS = 86_400_000;

function kstIsoDate(daysFromToday: number, now: number): string {
  const kstMidnight = Math.floor((now + KST_OFFSET_MS) / DAY_MS) * DAY_MS;
  return new Date(kstMidnight + daysFromToday * DAY_MS).toISOString().slice(0, 10);
}

export function defaultBacktestRange(days: number, now = Date.now()): { startDate: string; endDate: string } {
  return { startDate: kstIsoDate(-1 - days, now), endDate: kstIsoDate(-1, now) };
}
