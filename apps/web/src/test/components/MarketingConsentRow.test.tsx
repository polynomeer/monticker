import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const mutate = vi.fn();
let consent: { agreed: boolean; documentVersion: string | null; currentVersion: string; recordedAt: string | null } = {
  agreed: true, documentVersion: "v1", currentVersion: "v1", recordedAt: "2026-10-01T00:00:00Z",
};

vi.mock("@/hooks/useConsents", () => ({
  useConsentStatus: () => ({ data: { states: [{ type: "MARKETING", ...consent }], missingRequired: [] }, isLoading: false, isError: false }),
  useSetOptionalConsent: () => ({ mutate, isPending: false, isError: false, error: null }),
}));

import { MarketingConsentRow } from "@/components/settings/MarketingConsentRow";

describe("MarketingConsentRow", () => {
  beforeEach(() => mutate.mockReset());

  it("동의 상태면 켜져 있고, 끄면 즉시 철회를 요청한다", async () => {
    render(<MarketingConsentRow />);
    const toggle = screen.getByRole("switch", { name: "마케팅 정보 수신 동의" });
    expect(toggle).toHaveAttribute("aria-checked", "true");

    await userEvent.click(toggle);
    expect(mutate).toHaveBeenCalledWith({ type: "MARKETING", agreed: false });
  });

  it("옛 문서 버전에만 동의했으면 꺼진 것으로 보고, 켜면 다시 동의를 요청한다", async () => {
    consent = { agreed: true, documentVersion: "v0", currentVersion: "v1", recordedAt: "2026-01-01T00:00:00Z" };
    render(<MarketingConsentRow />);
    const toggle = screen.getByRole("switch", { name: "마케팅 정보 수신 동의" });
    expect(toggle).toHaveAttribute("aria-checked", "false");

    await userEvent.click(toggle);
    expect(mutate).toHaveBeenCalledWith({ type: "MARKETING", agreed: true });
  });
});
