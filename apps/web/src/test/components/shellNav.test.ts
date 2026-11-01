import { describe, expect, it } from "vitest";
import { activeRail, RAIL, sectionTabs } from "@/components/terminal/nav";

describe("셸 레일·섹션 탭(ADR-112)", () => {
  it("레일은 8개, 알림·트레이딩은 없다", () => {
    expect(RAIL.map((r) => r.label)).toEqual(["홈", "관심종목", "탐색", "내 자산", "실전투자", "퀀트랩", "전략 마켓", "설정"]);
  });

  it("가장 긴 경로가 이긴다", () => {
    expect(activeRail("/quant-lab/market")?.label).toBe("전략 마켓");
    expect(activeRail("/quant-lab/abc")?.label).toBe("퀀트랩");
    expect(activeRail("/brokerage/connect")?.label).toBe("설정");
    expect(activeRail("/brokerage/orders")?.label).toBe("실전투자");
    expect(activeRail("/")?.label).toBe("홈");
    expect(activeRail("/alerts")).toBeUndefined();
  });

  it("종목 상세는 탐색에 속하지만 섹션 탭은 없다", () => {
    expect(activeRail("/stocks/005930")?.label).toBe("탐색");
    expect(sectionTabs("/stocks/005930")).toBeNull();
  });

  it("묶인 화면의 활성 탭", () => {
    expect(sectionTabs("/screener")?.active.label).toBe("스크리너");
    expect(sectionTabs("/brokerage")?.active.label).toBe("계좌");
    expect(sectionTabs("/brokerage/rebalance")?.active.label).toBe("리밸런싱");
    expect(sectionTabs("/settlement")?.active.label).toBe("지갑");
    expect(sectionTabs("/portfolio")?.active.label).toBe("자산");
    expect(sectionTabs("/quant-lab/abc")?.active.label).toBe("내 전략");
    expect(sectionTabs("/subscription")?.active.label).toBe("구독");
  });

  it("탭이 없는 묶음은 null", () => {
    expect(sectionTabs("/watchlist")).toBeNull();
    expect(sectionTabs("/")).toBeNull();
  });
});

describe("상단 바 지표 접기", () => {
  it("다 들어가면 모두 보인다", async () => {
    const { fitCount } = await import("@/components/terminal/Shell");
    expect(fitCount([80, 80, 80], 400)).toBe(3);
  });
  it("넘치면 '+N 지표' 자리를 남기고 앞에서부터", async () => {
    const { fitCount } = await import("@/components/terminal/Shell");
    // 80+26+80 = 186, 다음 칸을 넣으면 292 + 26 + 72 > 300
    expect(fitCount([80, 80, 80, 80], 300)).toBe(2);
    expect(fitCount([500], 300)).toBe(0);
  });
});
