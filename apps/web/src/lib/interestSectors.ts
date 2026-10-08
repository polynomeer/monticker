// ADR-099 — 관심 분야(온보딩·설정에서 고른 enum)를 화면의 "정렬·강조 신호"로 쓸 때의 유일한 매핑.
//
// 원칙
//  - 정보를 숨기지 않는다: 정렬 함수는 입력과 같은 원소를 같은 개수만큼 돌려준다(순서만 바꾼다).
//  - 안정 정렬: 관심 분야끼리, 나머지끼리는 원래 순서를 그대로 둔다.
//  - 관심 분야가 없거나 스위치("관심 분야 순")가 꺼져 있으면 입력 순서를 그대로 돌려준다.
//  - 종목 추천이 아니다: 사용자가 고른 분야의 정보를 먼저 보여 줄 뿐, 어떤 종목을 사라/팔라는 뜻이 없다.
//
// 업종 문자열(stocks.sector)은 KRX 업종 대분류(예: "전기전자", "의약품", "금융업", "운수장비")와
// 시드 데이터의 세부 업종(예: "반도체", "바이오")이 섞여 있다. 그래서 정확히 같은 값이 아니라
// 정규화(공백·가운뎃점·하이픈 제거, 소문자) 후 "키워드를 포함하는지"로 맞춘다.
// 대분류는 여러 관심 분야에 겹친다(전기전자 = 반도체·2차전지). 정렬 신호일 뿐이라 겹침을 허용한다.

/** 서버 enum(UserPreferenceService.InterestSector, V86 CHECK)과 같은 값. */
export type InterestSector =
  | "SEMICONDUCTOR" | "SECONDARY_BATTERY" | "INTERNET_PLATFORM" | "BIO" | "FINANCE"
  | "AUTOMOTIVE" | "DIVIDEND" | "ETF" | "SHIPBUILDING_DEFENSE";

/** 화면 라벨 — 온보딩·설정이 같이 쓴다. 순서가 화면의 버튼 순서다. */
export const INTEREST_SECTORS: readonly { key: InterestSector; label: string }[] = [
  { key: "SEMICONDUCTOR", label: "반도체" },
  { key: "SECONDARY_BATTERY", label: "2차전지" },
  { key: "INTERNET_PLATFORM", label: "인터넷·플랫폼" },
  { key: "BIO", label: "바이오" },
  { key: "FINANCE", label: "금융" },
  { key: "AUTOMOTIVE", label: "자동차" },
  { key: "DIVIDEND", label: "배당주" },
  { key: "ETF", label: "ETF" },
  { key: "SHIPBUILDING_DEFENSE", label: "조선·방산" },
];

/**
 * 관심 분야 → 업종 키워드(정규화된 업종 문자열에 포함되면 해당).
 * DIVIDEND·ETF는 업종이 아니라(배당 성향·상품 유형) 업종 문자열로 알 수 없다 — 빈 목록이라 정렬에 영향이 없다.
 */
export const INTEREST_SECTOR_KEYWORDS: Readonly<Record<InterestSector, readonly string[]>> = {
  SEMICONDUCTOR: ["반도체", "전기전자"],
  SECONDARY_BATTERY: ["2차전지", "이차전지", "배터리", "전기전자", "화학"],
  INTERNET_PLATFORM: ["인터넷", "플랫폼", "소프트웨어", "it서비스", "서비스업"],
  BIO: ["바이오", "제약", "의약품", "의료"],
  FINANCE: ["금융", "은행", "증권", "보험"],
  AUTOMOTIVE: ["자동차", "운수장비", "운송장비"],
  DIVIDEND: [],
  ETF: [],
  SHIPBUILDING_DEFENSE: ["조선", "방산", "방위", "항공", "기계", "운수장비", "운송장비"],
};

export function interestLabel(key: InterestSector): string {
  return INTEREST_SECTORS.find((s) => s.key === key)?.label ?? key;
}

function normalizeSector(s: string): string {
  return s.replace(/[\s·・.\-_/]/g, "").toLowerCase();
}

/** 업종 문자열이 고른 관심 분야 중 어디에 해당하는지(관심 분야 순서대로). 업종이 없으면 빈 목록. */
export function matchingInterests(sector: string | null | undefined, interests: readonly InterestSector[]): InterestSector[] {
  if (!sector || interests.length === 0) return [];
  const n = normalizeSector(sector);
  if (!n) return [];
  return interests.filter((k) => (INTEREST_SECTOR_KEYWORDS[k] ?? []).some((kw) => n.includes(kw)));
}

export function isInterestSector(sector: string | null | undefined, interests: readonly InterestSector[]): boolean {
  return matchingInterests(sector, interests).length > 0;
}

/**
 * 관심 분야 순 정렬 — 관심 분야에 해당하는 원소를 앞으로, 나머지는 뒤로. 각 그룹 안의 순서는 그대로(안정).
 * 원소를 빼거나 더하지 않는다. 관심 분야가 없거나 enabled=false면 입력 순서 그대로(새 배열).
 */
export function interestFirst<T>(
  items: readonly T[],
  sectorOf: (item: T) => string | null | undefined,
  interests: readonly InterestSector[],
  enabled = true,
): T[] {
  if (!enabled || interests.length === 0) return [...items];
  const hit: T[] = [];
  const rest: T[] = [];
  for (const it of items) (isInterestSector(sectorOf(it), interests) ? hit : rest).push(it);
  return [...hit, ...rest];
}

/** "관심 분야 순"이 실제로 적용되는지 — 관심 분야가 하나라도 있고 스위치가 켜져 있을 때만. */
export function interestOrderingActive(prefs: { interestSectors?: readonly InterestSector[] | null; interestOrdering?: boolean | null } | null | undefined): boolean {
  return !!prefs && (prefs.interestSectors?.length ?? 0) > 0 && prefs.interestOrdering !== false;
}
