import type {
  AddPortfolioTransactionRequest,
  CreatePortfolioAccountRequest,
  HoldingEventFeed,
  HoldingsScope,
  ImportBatch,
  ImportOptions,
  ImportPreview,
  ImportResult,
  PortfolioAccount,
  PortfolioAnalysis,
  PortfolioConsent,
  PortfolioHoldings,
  PortfolioTransaction,
} from "@monticker/types";
import { authFetch } from "./api";

/** 통합 포트폴리오(ADR-106 1단계) — 손 입력·CSV 계좌, 보유, 보유 종목 소식(ADR-108). */

export class PortfolioApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function ok(res: Response): Promise<Response> {
  if (res.ok) return res;
  const body = await res.json().catch(() => null);
  const fallback = res.status === 413 ? "파일은 5MB까지 올릴 수 있습니다." : "요청을 처리하지 못했습니다.";
  throw new PortfolioApiError(res.status, body?.message ?? fallback);
}

const json = (body: unknown): RequestInit => ({ headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export async function getHoldings(scope: HoldingsScope): Promise<PortfolioHoldings> {
  return (await ok(await authFetch(`/api/portfolio/holdings?scope=${scope}`))).json();
}

/** 원화 요약·배분·배당·돈 가중 수익률(ADR-111) */
export async function getAnalysis(): Promise<PortfolioAnalysis> {
  return (await ok(await authFetch("/api/portfolio/analysis"))).json();
}

export async function getHoldingEvents(cursor: string | null, limit = 20): Promise<HoldingEventFeed> {
  const q = new URLSearchParams({ limit: String(limit) });
  if (cursor) q.set("cursor", cursor);
  return (await ok(await authFetch(`/api/portfolio/events?${q}`))).json();
}

export async function getAccounts(): Promise<PortfolioAccount[]> {
  return (await ok(await authFetch("/api/portfolio/accounts"))).json();
}

export async function createAccount(req: CreatePortfolioAccountRequest): Promise<PortfolioAccount> {
  return (await ok(await authFetch("/api/portfolio/accounts", { method: "POST", ...json(req) }))).json();
}

export async function deleteAccount(accountId: number): Promise<void> {
  await ok(await authFetch(`/api/portfolio/accounts/${accountId}`, { method: "DELETE" }));
}

/** 동의 문서가 바뀐 뒤 다시 동의 — 처음 동의는 계좌를 만들 때 함께 보낸다 */
export async function agreePortfolioConsent(consents: PortfolioConsent[]): Promise<void> {
  await ok(await authFetch("/api/portfolio/consent", { method: "POST", ...json({ consents }) }));
}

/** 계좌·거래·가져오기 기록을 모두 지우고 동의를 철회한다(되돌릴 수 없다) */
export async function deleteAllPortfolioData(): Promise<{ deletedAccounts: number }> {
  return (await ok(await authFetch("/api/portfolio/data", { method: "DELETE" }))).json();
}

export async function getTransactions(accountId: number, page = 0, size = 50): Promise<PortfolioTransaction[]> {
  return (await ok(await authFetch(`/api/portfolio/accounts/${accountId}/transactions?page=${page}&size=${size}`))).json();
}

export async function addTransaction(accountId: number, req: AddPortfolioTransactionRequest): Promise<{ id: number }> {
  return (await ok(await authFetch(`/api/portfolio/accounts/${accountId}/transactions`, { method: "POST", ...json(req) }))).json();
}

export async function deleteTransaction(accountId: number, transactionId: number): Promise<void> {
  await ok(await authFetch(`/api/portfolio/accounts/${accountId}/transactions/${transactionId}`, { method: "DELETE" }));
}

/**
 * 파일 + options(JSON 파트). Content-Type은 브라우저가 경계(boundary)와 함께 정하게 둔다.
 * options 파트는 application/json이어야 서버(@RequestPart)가 읽는다.
 */
function importForm(file: File, options: ImportOptions): FormData {
  const form = new FormData();
  form.append("file", file);
  form.append("options", new Blob([JSON.stringify(options)], { type: "application/json" }));
  return form;
}

/** 미리보기 — 서버는 저장하지 않는다. 확정은 같은 파일·options로 다시 보낸다 */
export async function previewImport(accountId: number, file: File, options: ImportOptions): Promise<ImportPreview> {
  return (await ok(await authFetch(`/api/portfolio/accounts/${accountId}/imports/preview`, { method: "POST", body: importForm(file, options) }))).json();
}

/** expectedSha256 = 미리보기의 fileSha256 — 그 사이 파일이 바뀌면 서버가 거부한다 */
export async function commitImport(
  accountId: number, file: File, options: ImportOptions, skipErrors: boolean, expectedSha256: string,
): Promise<ImportResult> {
  const q = new URLSearchParams({ skipErrors: String(skipErrors), expectedSha256 });
  return (await ok(await authFetch(`/api/portfolio/accounts/${accountId}/imports?${q}`, { method: "POST", body: importForm(file, options) }))).json();
}

export async function getImports(accountId: number): Promise<ImportBatch[]> {
  return (await ok(await authFetch(`/api/portfolio/accounts/${accountId}/imports`))).json();
}

export async function revertImport(accountId: number, batchId: number): Promise<void> {
  await ok(await authFetch(`/api/portfolio/accounts/${accountId}/imports/${batchId}`, { method: "DELETE" }));
}

/** 표준 템플릿(UTF-8 BOM CSV)을 받아 저장한다 — 인증이 필요해 링크 대신 받아서 내려준다 */
export async function downloadImportTemplate(): Promise<void> {
  const blob = await (await ok(await authFetch("/api/portfolio/imports/template"))).blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "monticker-portfolio-template.csv";
  a.click();
  URL.revokeObjectURL(url);
}
