/** ADR-093 — 방해 금지 시간 입력 검사. 서버(NotificationPreferenceService·V90 CHECK)와 같은 규칙: "HH:mm", 시작 ≠ 종료. */
const HH_MM = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

export function quietHoursError(start: string, end: string): string | null {
  if (!HH_MM.test(start) || !HH_MM.test(end)) return "시각을 HH:mm 형식으로 입력해 주세요";
  if (start === end) return "시작과 종료가 같을 수 없습니다";
  return null;
}

/** 자정을 넘는 구간인지 — 안내 문구용 */
export function crossesMidnight(start: string, end: string): boolean {
  return HH_MM.test(start) && HH_MM.test(end) && start > end;
}

export function quietHoursStat(enabled: boolean, start: string, end: string): string {
  return enabled ? `${start}–${end}` : "꺼짐";
}
