import type { IconName } from "./Icon";

/**
 * ADR-112 — 라벨 레일과 섹션 탭. 레일은 8개(홈 · 관심종목 · 탐색 · 내 자산 · 실전투자 | 퀀트랩 · 전략 마켓 | 설정),
 * 묶인 화면은 본문 첫 줄의 섹션 탭으로 오간다. 활성 판정은 가장 긴 경로 일치다(/quant-lab/market은 퀀트랩이 아니라 전략 마켓).
 * 알림은 레일에 두지 않는다 — 상단 바의 종으로 간다.
 */

export interface NavTab { label: string; href: string; match?: string[] }

export interface RailItem {
  label: string;
  icon: IconName;
  href: string;
  /** 이 경로들 아래에 있으면 활성 */
  match: string[];
  /** 본문 맨 위 섹션 탭. 없으면 탭을 그리지 않는다 */
  tabs?: NavTab[];
  /** 레일 아래쪽(설정)에 붙인다 */
  bottom?: boolean;
  /** 이 항목 앞에 구분선 */
  divider?: boolean;
}

export const RAIL: RailItem[] = [
  { label: "홈", icon: "home", href: "/", match: ["/"] },
  { label: "관심종목", icon: "star", href: "/watchlist", match: ["/watchlist"] },
  {
    label: "탐색", icon: "filter", href: "/screener", match: ["/screener", "/compare", "/stocks"],
    tabs: [{ label: "스크리너", href: "/screener" }, { label: "종목 비교", href: "/compare" }],
  },
  {
    // ADR-115 — 모의 포트폴리오는 내 자산의 모의투자 탭(화면 안 탭). 지갑·리스크·체결 엔진·자동 주문 규칙도 여기 묶는다
    label: "내 자산", icon: "pie", href: "/assets",
    match: ["/assets", "/portfolio", "/wallet", "/settlement", "/risk", "/matching", "/watch-rules"],
    tabs: [
      { label: "자산", href: "/assets", match: ["/assets", "/portfolio"] },
      { label: "지갑", href: "/wallet", match: ["/wallet", "/settlement"] },
      { label: "리스크 한도", href: "/risk" },
      { label: "체결 엔진", href: "/matching" },
      { label: "자동 주문 규칙", href: "/watch-rules" },
    ],
  },
  {
    label: "실전투자", icon: "bank", href: "/brokerage", match: ["/brokerage"],
    tabs: [
      { label: "계좌", href: "/brokerage", match: [] },
      { label: "주문", href: "/brokerage/orders" },
      { label: "조건부 주문", href: "/brokerage/conditional-orders" },
      { label: "리밸런싱", href: "/brokerage/rebalance" },
    ],
  },
  {
    label: "퀀트랩", icon: "flask", href: "/quant-lab", match: ["/quant-lab", "/backtest", "/analytics"], divider: true,
    tabs: [
      { label: "내 전략", href: "/quant-lab" },
      { label: "룰셋 빌더", href: "/quant-lab/builder" },
      { label: "백테스트", href: "/backtest" },
      { label: "포트폴리오 분석", href: "/analytics" },
    ],
  },
  {
    label: "전략 마켓", icon: "store", href: "/quant-lab/market", match: ["/quant-lab/market", "/quant-lab/earnings"],
    tabs: [{ label: "마켓", href: "/quant-lab/market" }, { label: "제작자 수익", href: "/quant-lab/earnings" }],
  },
  {
    label: "설정", icon: "sliders", href: "/settings/appearance", match: ["/settings", "/subscription", "/brokerage/connect"], bottom: true,
    tabs: [
      { label: "화면", href: "/settings/appearance" },
      { label: "알림", href: "/settings/notifications" },
      { label: "관심 분야", href: "/settings/interests" },
      { label: "구독", href: "/subscription" },
      { label: "증권사 연동", href: "/brokerage/connect" },
    ],
  },
];

function hits(pathname: string, m: string): boolean {
  return m === "/" ? pathname === "/" : pathname === m || pathname.startsWith(m + "/");
}

/** 가장 구체적인(긴) 일치를 고른다 */
function best<T>(pathname: string, items: T[], matchOf: (t: T) => string[]): T | undefined {
  let found: { item: T; len: number } | undefined;
  for (const item of items) {
    for (const m of matchOf(item)) {
      if (hits(pathname, m) && (!found || m.length > found.len)) found = { item, len: m.length };
    }
  }
  return found?.item;
}

export function activeRail(pathname: string): RailItem | undefined {
  return best(pathname, RAIL, (r) => r.match);
}

/** 이 화면의 섹션 탭과 활성 탭. 묶음에 속하지만 탭에 없는 화면(종목 상세 등)은 탭을 그리지 않는다 */
export function sectionTabs(pathname: string): { item: RailItem; active: NavTab } | null {
  const item = activeRail(pathname);
  if (!item?.tabs) return null;
  // 탭 자체 주소는 정확히 일치할 때만(계좌 = /brokerage), 하위 경로는 match로
  const active = best(pathname, item.tabs, (t) => [...(t.match ?? [t.href])]) ?? item.tabs.find((t) => t.href === pathname);
  return active ? { item, active } : null;
}
