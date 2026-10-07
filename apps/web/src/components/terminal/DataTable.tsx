import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface Column<T> {
  key: string;
  header: ReactNode;
  align?: "left" | "right" | "center";
  cell: (row: T, index: number) => ReactNode;
  className?: string;
}

/** 시안의 table() — 11px 헤더, 행 구분선, 넓으면 가로 스크롤. */
export function DataTable<T>({
  columns, rows, rowKey, minWidth, dense = true, selectedIndex, onRowClick, empty,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T, index: number) => string | number;
  minWidth?: number;
  dense?: boolean;
  selectedIndex?: number;
  onRowClick?: (row: T, index: number) => void;
  empty?: ReactNode;
}) {
  const pad = dense ? "px-2.5 py-[7px]" : "px-3 py-2.5";
  const align = (a?: string) => (a === "right" ? "text-right" : a === "center" ? "text-center" : "text-left");
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-13 text-dracula-fg" style={minWidth ? { minWidth } : undefined}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} scope="col" className={cn("whitespace-nowrap border-b border-tm-line px-3 py-2 text-2xs font-medium text-tm-muted", align(c.align))}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && empty ? (
            <tr>
              <td colSpan={columns.length} className="px-3 py-10 text-center text-13 text-tm-muted">
                {empty}
              </td>
            </tr>
          ) : (
            rows.map((r, i) => (
              <tr
                key={rowKey(r, i)}
                onClick={onRowClick ? () => onRowClick(r, i) : undefined}
                className={cn(selectedIndex === i && "bg-tm-raised", onRowClick && "cursor-pointer hover:bg-tm-raised/60")}
              >
                {columns.map((c) => (
                  <td key={c.key} className={cn("whitespace-nowrap border-b border-tm-line", pad, align(c.align), c.className)}>
                    {c.cell(r, i)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
