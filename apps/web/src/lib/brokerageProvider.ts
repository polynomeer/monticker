import type { BrokerageProviderId } from "@monticker/types";

export const BROKERAGE_PROVIDER_LABELS: Record<BrokerageProviderId, string> = {
  KIS: "한국투자증권",
  TOSS: "토스증권",
};

export function brokerageProviderLabel(provider: string): string {
  return BROKERAGE_PROVIDER_LABELS[provider as BrokerageProviderId] ?? provider;
}
