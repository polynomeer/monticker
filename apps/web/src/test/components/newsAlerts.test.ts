import { describe, expect, it } from "vitest";
import { matchesFilter, ruleMeta } from "@/components/alerts/data";
import { CATEGORY_LABEL, sortCategories, type CategoryChannels } from "@/components/alerts/channels";

describe("알림 이력 뉴스·공시 탭 (ADR-100)", () => {
  const news = { ruleType: "NEWS", readAt: null };
  const volume = { ruleType: "VOLUME_SURGE", readAt: null };

  it("관심종목 뉴스·공시 이력은 뉴스·공시 탭에 분류된다", () => {
    expect(ruleMeta("NEWS")).toMatchObject({ category: "news", tag: "뉴스·공시" });
    expect(matchesFilter(news, "news")).toBe(true);
    expect(matchesFilter(news, "event")).toBe(false);
    expect(matchesFilter(news, "signal")).toBe(false);
    expect(matchesFilter(volume, "news")).toBe(false);
  });

  it("예전 뉴스·공시 규칙 이력도 같은 탭으로 모인다", () => {
    expect(matchesFilter({ ruleType: "NEWS_PUBLISHED", readAt: null }, "news")).toBe(true);
    expect(matchesFilter({ ruleType: "DISCLOSURE_PUBLISHED", readAt: null }, "news")).toBe(true);
  });

  it("전체·읽지 않음 탭에도 함께 보인다", () => {
    expect(matchesFilter(news, "all")).toBe(true);
    expect(matchesFilter(news, "unread")).toBe(true);
  });
});

describe("전달 채널 — 뉴스·공시", () => {
  const cat = (category: string): CategoryChannels => ({
    category, alwaysOn: false, push: true, email: false, emailFallback: true, inApp: true, pushDuringQuietHours: false,
  });

  it("이름이 있고 거래량 급증 다음에 보인다", () => {
    expect(CATEGORY_LABEL.NEWS).toBe("뉴스·공시");
    const sorted = sortCategories([cat("QUANT_SIGNAL"), cat("NEWS"), cat("VOLUME_SURGE")]).map((c) => c.category);
    expect(sorted).toEqual(["VOLUME_SURGE", "NEWS", "QUANT_SIGNAL"]);
  });
});
