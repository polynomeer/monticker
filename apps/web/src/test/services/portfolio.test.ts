import { beforeEach, describe, expect, it, vi } from "vitest";

const authFetch = vi.fn();
vi.mock("@/services/api", () => ({ authFetch: (...a: unknown[]) => authFetch(...a) }));

import { commitImport, createAccount, PortfolioApiError, previewImport } from "@/services/portfolio";

const okJson = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("통합 포트폴리오 서비스", () => {
  beforeEach(() => authFetch.mockReset());

  it("미리보기는 파일과 application/json options 파트를 multipart로 보낸다 — Content-Type은 브라우저에 맡긴다", async () => {
    authFetch.mockResolvedValue(okJson({ fileSha256: "abc" }));
    const file = new File(["date,type\n"], "t.csv", { type: "text/csv" });

    await previewImport(7, file, { format: "MONTICKER_V1" });

    const [url, init] = authFetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/accounts/7/imports/preview");
    expect(init.method).toBe("POST");
    expect(init.headers).toBeUndefined();
    const form = init.body as FormData;
    expect((form.get("file") as File).name).toBe("t.csv");
    const options = form.get("options") as Blob;
    expect(options.type).toBe("application/json");
    expect(JSON.parse(await options.text())).toEqual({ format: "MONTICKER_V1" });
  });

  it("확정은 미리보기 해시와 오류 행 건너뛰기를 쿼리로 보낸다", async () => {
    authFetch.mockResolvedValue(okJson({ batchId: 1, imported: 3, duplicates: 0, skipped: 1 }, 201));
    const file = new File(["x"], "t.csv");

    const r = await commitImport(7, file, { format: "MONTICKER_V1" }, true, "deadbeef");

    expect(authFetch.mock.calls[0][0]).toBe("/api/portfolio/accounts/7/imports?skipErrors=true&expectedSha256=deadbeef");
    expect(r.imported).toBe(3);
  });

  it("서버 거부 사유를 그대로 올리고, 413은 크기 안내로 바꾼다", async () => {
    authFetch.mockResolvedValueOnce(okJson({ message: "필수 동의 항목이 빠졌습니다: 계좌 정보 수집·이용" }, 400));
    await expect(createAccount({ name: "a", institution: "KIS", source: "CSV" })).rejects.toThrow("필수 동의 항목");

    authFetch.mockResolvedValueOnce(new Response("", { status: 413 }));
    const err = await previewImport(7, new File(["x"], "t.csv"), {}).catch((e) => e);
    expect(err).toBeInstanceOf(PortfolioApiError);
    expect(err.status).toBe(413);
    expect(err.message).toContain("5MB");
  });
});
