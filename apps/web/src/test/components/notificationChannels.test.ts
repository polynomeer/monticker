import { describe, expect, it } from "vitest";
import { channelLabels, channelsSummary, quietHoursLabel, sortCategories, type CategoryChannels, type DeliveryChannels } from "@/components/alerts/channels";
import { crossesMidnight, quietHoursError, quietHoursStat } from "@/components/settings/quietHours";

const cat = (category: string, over: Partial<CategoryChannels> = {}): CategoryChannels => ({
  category, alwaysOn: false, push: false, email: false, emailFallback: false, inApp: false, pushDuringQuietHours: false, ...over,
});

const channels = (categories: CategoryChannels[], activeNow = false): DeliveryChannels => ({
  quietHours: { enabled: activeNow, start: "22:00", end: "07:00", activeNow },
  marketingAgreed: false,
  kakaoAvailable: false,
  categories,
});

describe("channelLabels", () => {
  it("lists push, chosen email or the fallback, and in-app history", () => {
    expect(channelLabels(cat("PRICE_ALERT", { push: true, emailFallback: true, inApp: true }))).toEqual(["푸시", "푸시 실패 시 이메일", "알림 이력"]);
    expect(channelLabels(cat("FILLS", { push: true, email: true, emailFallback: false }))).toEqual(["푸시", "이메일"]);
    expect(channelLabels(cat("STRATEGY_MARKET"))).toEqual([]);
  });
});

describe("channelsSummary", () => {
  it("summarises only opt-out categories and flags active quiet hours", () => {
    const always = cat("ORDER_OUTCOME", { alwaysOn: true, push: true, emailFallback: true, pushDuringQuietHours: true });
    expect(channelsSummary(channels([always]))).toBe("필수 알림만");
    expect(channelsSummary(channels([always, cat("PRICE_ALERT", { push: true, emailFallback: true })]))).toBe("푸시 · 이메일");
    expect(channelsSummary(channels([cat("FILLS", { push: true })], true))).toBe("푸시 (방해 금지 중)");
    expect(channelsSummary(null)).toBe("—");
  });
});

describe("sortCategories", () => {
  it("keeps the screen order and puts unknown categories last", () => {
    const sorted = sortCategories([cat("NEW_KIND"), cat("ORDER_OUTCOME"), cat("PRICE_ALERT")]).map((c) => c.category);
    expect(sorted).toEqual(["PRICE_ALERT", "ORDER_OUTCOME", "NEW_KIND"]);
  });
});

describe("quiet hours input", () => {
  it("matches the server rule: HH:mm, start ≠ end", () => {
    expect(quietHoursError("22:00", "07:00")).toBeNull();
    expect(quietHoursError("13:00", "14:00")).toBeNull();
    expect(quietHoursError("22:00", "22:00")).toBeTruthy();
    expect(quietHoursError("24:00", "07:00")).toBeTruthy();
    expect(quietHoursError("7:00", "08:00")).toBeTruthy();
    expect(quietHoursError("", "07:00")).toBeTruthy();
  });

  it("knows when the window crosses midnight", () => {
    expect(crossesMidnight("22:00", "07:00")).toBe(true);
    expect(crossesMidnight("13:00", "14:00")).toBe(false);
  });

  it("labels the state", () => {
    expect(quietHoursStat(false, "22:00", "07:00")).toBe("꺼짐");
    expect(quietHoursStat(true, "22:00", "07:00")).toBe("22:00–07:00");
    expect(quietHoursLabel({ enabled: true, start: "22:00", end: "07:00", activeNow: true })).toBe("22:00–07:00 · 지금 적용 중");
  });
});
