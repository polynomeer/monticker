import { authFetch } from "./api";

export interface BillingStatus {
  registered: boolean;
  cardCompany: string | null;
  cardLast4: string | null;
}

export async function getBillingStatus(): Promise<BillingStatus> {
  const res = await authFetch("/api/subscription/billing");
  if (!res.ok) throw new Error("자동결제 카드 상태를 불러오지 못했습니다.");
  return res.json();
}

export async function getOrCreateCustomerKey(): Promise<string> {
  const res = await authFetch("/api/subscription/billing/customer-key");
  if (!res.ok) throw new Error("customerKey 발급에 실패했습니다.");
  const data = await res.json();
  return data.customerKey;
}

export async function registerBillingKey(authKey: string, customerKey: string): Promise<BillingStatus> {
  const res = await authFetch("/api/subscription/billing/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ authKey, customerKey }),
  });
  if (!res.ok) throw new Error("자동결제 카드 등록에 실패했습니다.");
  return res.json();
}

export async function deregisterBillingKey(): Promise<void> {
  const res = await authFetch("/api/subscription/billing", { method: "DELETE" });
  if (!res.ok) throw new Error("자동결제 카드 해지에 실패했습니다.");
}
