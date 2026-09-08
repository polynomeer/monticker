import type {
  BrokerageAccountResponse,
  BrokerageBalanceResponse,
  BrokerageOrderResponse,
  BrokerageSettlementResponse,
  ConnectBrokerageRequest,
  SubmitBrokerageOrderRequest,
  PageResponse,
} from "@monticker/types";
import { authFetch } from "./api";

/** 백엔드 GlobalExceptionHandler의 ErrorResponse({status, message, detail, timestamp}) 그대로 담는다. */
export class ApiError extends Error {
  constructor(public status: number, message: string, public detail?: string | null) {
    super(message);
  }
}

async function throwIfNotOk(res: Response): Promise<void> {
  if (res.ok) return;
  const body = await res.json().catch(() => null);
  throw new ApiError(res.status, body?.message ?? "요청을 처리하지 못했습니다.", body?.detail ?? null);
}

export async function getBrokerageAccount(): Promise<BrokerageAccountResponse | null> {
  const res = await authFetch("/api/brokerage/account");
  if (res.status === 409) return null; // 연동된 계좌 없음 — 정상 상태
  await throwIfNotOk(res);
  return res.json();
}

export async function connectBrokerage(req: ConnectBrokerageRequest): Promise<BrokerageAccountResponse> {
  const res = await authFetch("/api/brokerage/connect", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  await throwIfNotOk(res);
  return res.json();
}

export async function getBrokerageBalance(): Promise<BrokerageBalanceResponse> {
  const res = await authFetch("/api/brokerage/account/balance");
  await throwIfNotOk(res);
  return res.json();
}

export async function submitBrokerageOrder(req: SubmitBrokerageOrderRequest): Promise<BrokerageOrderResponse> {
  const res = await authFetch("/api/brokerage/orders", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  await throwIfNotOk(res);
  return res.json();
}

export async function cancelBrokerageOrder(id: number): Promise<BrokerageOrderResponse> {
  const res = await authFetch(`/api/brokerage/orders/${id}`, { method: "DELETE" });
  await throwIfNotOk(res);
  return res.json();
}

export async function syncBrokerageOrder(id: number): Promise<BrokerageOrderResponse> {
  const res = await authFetch(`/api/brokerage/orders/${id}/sync`);
  await throwIfNotOk(res);
  return res.json();
}

export async function getBrokerageOrders(page: number, size = 20): Promise<PageResponse<BrokerageOrderResponse>> {
  const res = await authFetch(`/api/brokerage/orders?page=${page}&size=${size}`);
  await throwIfNotOk(res);
  return res.json();
}

export async function getBrokerageSettlements(page: number, size = 20): Promise<PageResponse<BrokerageSettlementResponse>> {
  const res = await authFetch(`/api/brokerage/settlements?page=${page}&size=${size}`);
  await throwIfNotOk(res);
  return res.json();
}

export async function getPendingBrokerageSettlements(): Promise<BrokerageSettlementResponse[]> {
  const res = await authFetch("/api/brokerage/settlements/pending");
  await throwIfNotOk(res);
  return res.json();
}
