"use client";

import type { ImportBatch } from "@monticker/types";
import { Btn, DataTable, type Column } from "@/components/terminal";
import { fmtDateTime } from "@/components/portfolio/format";
import { useImports, useRevertImport } from "@/hooks/usePortfolio";

/** 가져오기 기록 — 한 번을 통째로 되돌리면 그때 들어온 거래만 지워진다 */
export function ImportHistory({ accountId }: { accountId: number }) {
  const { data = [] } = useImports(accountId, true);
  const revert = useRevertImport(accountId);
  const columns: Column<ImportBatch>[] = [
    { key: "at", header: "가져온 시각", cell: (b) => <span className="num">{fmtDateTime(b.createdAt)}</span> },
    { key: "format", header: "형식", cell: (b) => (b.format === "CUSTOM" ? "열 직접 지정" : "표준 템플릿") },
    { key: "rows", header: "파일 행", align: "right", cell: (b) => <span className="num">{b.rowCount}</span> },
    { key: "imported", header: "가져옴", align: "right", cell: (b) => <span className="num text-up">{b.imported}</span> },
    { key: "dup", header: "이미 있음", align: "right", cell: (b) => <span className="num text-tm-muted">{b.duplicates}</span> },
    { key: "skip", header: "뺀 오류", align: "right", cell: (b) => <span className="num text-tm-muted">{b.skipped}</span> },
    {
      key: "revert", header: <span className="sr-only">되돌리기</span>, align: "right",
      cell: (b) => (
        <Btn
          kind="ghost" size="sm" disabled={revert.isPending}
          onClick={() => { if (confirm(`이때 가져온 거래 ${b.imported}건을 지울까요?`)) revert.mutate(b.id); }}
        >
          되돌리기
        </Btn>
      ),
    },
  ];
  return <DataTable columns={columns} rows={data} rowKey={(b) => b.id} minWidth={640} empty="아직 가져온 파일이 없습니다." />;
}
