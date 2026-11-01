"use client";

import { useRef, useState } from "react";
import type { ImportPreview, ImportResult, ImportRowResult } from "@monticker/types";
import { Btn, Checkbox, DataTable, Field, Notice, Pill, Seg, type Column } from "@/components/terminal";
import { useCommitImport, usePreviewImport } from "@/hooks/usePortfolio";
import { downloadImportTemplate } from "@/services/portfolio";
import { CSV_FIELDS, customFormProblem, toImportOptions, type ImportForm } from "./importOptions";
import { TX_TYPE_LABEL, fmtQty } from "./labels";

const MAX_BYTES = 5 * 1024 * 1024;

const MODES = [
  { value: "TEMPLATE", label: "표준 템플릿" },
  { value: "CUSTOM", label: "열 직접 지정" },
] as const;

const STATUS: Record<ImportRowResult["status"], { label: string; tone: "green" | "muted" | "red" }> = {
  NEW: { label: "새 거래", tone: "green" },
  DUPLICATE: { label: "이미 있음", tone: "muted" },
  ERROR: { label: "오류", tone: "red" },
};

const ROW_COLUMNS: Column<ImportRowResult>[] = [
  { key: "line", header: "줄", align: "right", cell: (r) => <span className="num text-tm-muted">{r.line}</span> },
  { key: "status", header: "상태", cell: (r) => <Pill tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Pill> },
  { key: "date", header: "거래일", cell: (r) => <span className="num">{r.tradeDate ?? "—"}</span> },
  { key: "type", header: "구분", cell: (r) => (r.type ? TX_TYPE_LABEL[r.type] : "—") },
  {
    key: "inst", header: "종목",
    cell: (r) => (
      <span className="flex items-center gap-1.5">
        {r.name ?? r.symbol ?? "—"}
        {r.status !== "ERROR" && r.symbol && r.stockId == null && <Pill tone="caution">시세 없음</Pill>}
      </span>
    ),
  },
  { key: "qty", header: "수량", align: "right", cell: (r) => <span className="num">{fmtQty(r.quantity)}</span> },
  { key: "price", header: "단가", align: "right", cell: (r) => <span className="num">{fmtQty(r.price)}</span> },
  { key: "amount", header: "금액", align: "right", cell: (r) => <span className="num">{fmtQty(r.amount)}</span> },
  { key: "cur", header: "통화", cell: (r) => <span className="num text-tm-soft">{r.currency ?? "—"}</span> },
  { key: "msg", header: "사유", cell: (r) => <span className="whitespace-normal text-xs text-danger">{r.message ?? ""}</span> },
];

/**
 * CSV 가져오기 — 파일 고르기 → 미리보기(저장 안 함) → 확정. 서버는 미리보기 상태를 갖지 않으므로 확정할 때 같은 파일을 다시 보내고,
 * 미리보기의 해시(fileSha256)로 그 사이 파일이 바뀌지 않았는지 서버가 확인한다. 오류 행이 있으면 이용자가 "빼고 가져오기"를
 * 골라야만 넣는다(행이 조용히 빠지면 보유가 틀어진다).
 */
export function ImportPanel({ accountId }: { accountId: number }) {
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [form, setForm] = useState<ImportForm>({ mode: "TEMPLATE", columns: {}, typeValuesText: "", defaultCurrency: "" });
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [skipErrors, setSkipErrors] = useState(false);
  const [done, setDone] = useState<ImportResult | null>(null);
  const [templateError, setTemplateError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const previewM = usePreviewImport(accountId);
  const commitM = useCommitImport(accountId);

  // 파일이나 설정이 바뀌면 지난 미리보기는 더 이상 맞지 않는다
  const invalidate = () => { setPreview(null); setSkipErrors(false); previewM.reset(); commitM.reset(); };
  const update = (patch: Partial<ImportForm>) => { setForm((f) => ({ ...f, ...patch })); invalidate(); };

  const pick = (f: File | null) => {
    setDone(null);
    invalidate();
    if (f && f.size > MAX_BYTES) { setFile(null); setFileError("파일은 5MB까지 올릴 수 있습니다."); return; }
    setFileError(null);
    setFile(f);
  };

  const problem = customFormProblem(form);
  const runPreview = async () => {
    if (!file || problem) return;
    try {
      setPreview(await previewM.mutateAsync({ file, options: toImportOptions(form) }));
    } catch {
      // 아래 Notice
    }
  };
  const runCommit = async () => {
    if (!file || !preview) return;
    try {
      const r = await commitM.mutateAsync({ file, options: toImportOptions(form), skipErrors, sha256: preview.fileSha256 });
      setDone(r);
      setPreview(null);
      setFile(null);
      if (inputRef.current) inputRef.current.value = "";
    } catch {
      // 아래 Notice
    }
  };
  const getTemplate = async () => {
    setTemplateError(null);
    try { await downloadImportTemplate(); } catch (e) { setTemplateError(e instanceof Error ? e.message : "템플릿을 받지 못했습니다."); }
  };

  const blocked = preview != null && preview.errorCount > 0 && !skipErrors;
  const canCommit = preview != null && preview.newCount > 0 && !blocked && !commitM.isPending;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <Seg options={MODES} value={form.mode} onChange={(mode) => update({ mode })} size="sm" />
        <Btn kind="ghost" size="sm" icon="download" onClick={getTemplate}>표준 템플릿 받기</Btn>
        <span className="text-xs text-tm-muted">CSV(쉼표·탭), UTF-8 또는 엑셀 저장(CP949), 5MB·2만 행까지</span>
      </div>
      {templateError && <Notice tone="danger">{templateError}</Notice>}

      {form.mode === "CUSTOM" && (
        <div className="flex flex-col gap-2 rounded-lg border border-tm-line p-3">
          <p className="m-0 text-xs text-tm-muted">파일 첫 줄(헤더)에 적힌 열 이름을 그대로 적습니다. 없는 열은 비워 둡니다.</p>
          <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] gap-2">
            {CSV_FIELDS.map((c) => (
              <Field
                key={c.field}
                label={`${c.label}${c.required ? " (필수)" : ""}`}
                mono={false}
                placeholder={c.placeholder}
                value={form.columns[c.field] ?? ""}
                onChange={(e) => update({ columns: { ...form.columns, [c.field]: e.target.value } })}
              />
            ))}
          </div>
          <label className="flex flex-col gap-1 text-2xs text-tm-muted">
            거래 구분 값 (선택) — 한 줄에 하나, &quot;파일의 값=매수&quot;처럼. 매수·현금매수·배당금 같은 흔한 값은 이미 압니다.
            <textarea
              rows={3}
              value={form.typeValuesText}
              onChange={(e) => update({ typeValuesText: e.target.value })}
              placeholder={"주식매수=매수\n주식매도=매도"}
              className="rounded-lg border border-tm-line bg-tm-inner px-3 py-2 text-13 text-dracula-fg outline-hidden placeholder:text-tm-muted"
            />
          </label>
        </div>
      )}

      <div className="flex flex-wrap items-end gap-2">
        <label className="flex min-w-[240px] flex-1 flex-col gap-1 text-2xs text-tm-muted">
          거래내역 파일
          <input
            ref={inputRef}
            type="file"
            accept=".csv,.txt,text/csv"
            onChange={(e) => pick(e.target.files?.[0] ?? null)}
            className="rounded-lg border border-tm-line bg-tm-inner px-3 py-2 text-13 text-dracula-fg file:mr-3 file:rounded-md file:border-0 file:bg-tm-raised file:px-2.5 file:py-1 file:text-xs file:text-dracula-fg"
          />
        </label>
        <Field label="기본 통화 (선택)" placeholder="KRW" maxLength={3} className="max-w-[140px]" value={form.defaultCurrency} onChange={(e) => update({ defaultCurrency: e.target.value.toUpperCase() })} />
        <Btn onClick={runPreview} disabled={!file || !!problem || previewM.isPending}>{previewM.isPending ? "읽는 중…" : "미리보기"}</Btn>
      </div>
      {fileError && <Notice tone="danger">{fileError}</Notice>}
      {problem && file && <Notice tone="warn">{problem}</Notice>}
      {previewM.isError && <Notice tone="danger">{previewM.error instanceof Error ? previewM.error.message : "파일을 읽지 못했습니다."}</Notice>}
      {done && (
        <Notice tone="ok">
          {done.imported}건을 가져왔습니다{done.duplicates > 0 && ` · 이미 있던 ${done.duplicates}건은 건너뛰었습니다`}{done.skipped > 0 && ` · 오류 ${done.skipped}행은 뺐습니다`}.
          잘못 가져왔으면 「가져오기 기록」 탭에서 되돌릴 수 있습니다.
        </Notice>
      )}

      {preview && (
        <div className="flex flex-col gap-2.5">
          <div className="flex flex-wrap gap-x-5 gap-y-1 text-13" aria-label="미리보기 요약">
            <span>전체 <b className="num">{preview.rowCount}</b>행</span>
            <span className="text-up">새 거래 <b className="num">{preview.newCount}</b></span>
            <span className="text-tm-muted">이미 있음 <b className="num">{preview.duplicateCount}</b></span>
            <span className={preview.errorCount ? "text-danger" : "text-tm-muted"}>오류 <b className="num">{preview.errorCount}</b></span>
            {preview.unmatchedCount > 0 && <span className="text-caution">시세 없음 <b className="num">{preview.unmatchedCount}</b></span>}
            <span className="text-2xs text-tm-muted">인코딩 {preview.charset}</span>
          </div>
          {preview.unmatchedCount > 0 && (
            <Notice tone="info">종목 목록에서 찾지 못한 {preview.unmatchedCount}행은 코드 그대로 들어갑니다. 보유에는 나오지만 시세와 평가금액이 없습니다.</Notice>
          )}
          {preview.errorCount > 0 && (
            <Notice tone="warn">
              읽지 못한 행이 {preview.errorCount}개 있습니다. 그대로는 가져오지 않습니다 — 행이 빠지면 보유가 틀어지기 때문입니다.
              파일을 고쳐 다시 올리거나, 그 행을 빼고 가져오기를 고르세요.
              <div className="mt-2">
                <Checkbox checked={skipErrors} onChange={setSkipErrors} label={`오류 ${preview.errorCount}행을 빼고 가져오기`} />
              </div>
            </Notice>
          )}
          <div className="max-h-[360px] overflow-y-auto rounded-lg border border-tm-line">
            <DataTable columns={ROW_COLUMNS} rows={preview.rows} rowKey={(r) => r.line} minWidth={900} />
          </div>
          {preview.rows.length < preview.rowCount && (
            <p className="m-0 text-2xs text-tm-muted">오류·시세 없음 행을 먼저, {preview.rows.length}행까지 보여 줍니다.</p>
          )}
          {commitM.isError && <Notice tone="danger">{commitM.error instanceof Error ? commitM.error.message : "가져오지 못했습니다."}</Notice>}
          <div className="flex justify-end gap-2">
            <Btn kind="ghost" onClick={invalidate}>취소</Btn>
            <Btn onClick={runCommit} disabled={!canCommit}>
              {commitM.isPending ? "가져오는 중…" : preview.newCount === 0 ? "새 거래 없음" : `${preview.newCount}건 가져오기`}
            </Btn>
          </div>
        </div>
      )}
    </div>
  );
}
