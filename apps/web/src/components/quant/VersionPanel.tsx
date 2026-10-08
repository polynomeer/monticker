// 룰셋 빌더 "버전" 탭 — 지난 버전을 읽기 전용으로 보고, 원하면 빌더로 불러온다.
// 불러오기는 화면 상태만 바꾼다. 서버 반영은 평소처럼 "룰셋 업데이트"(PUT)로 해서 새 버전으로 남는다.
"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { RuleSet } from "@monticker/types";
import { authFetch } from "@/services/api";
import { cn } from "@/lib/utils";
import { Btn, Notice, Panel } from "@/components/terminal";
import { condToText } from "./ruleDefinition";
import { buildVersionRows, type RuleVersionEntry, type VersionRow } from "./ruleVersions";

export function useRuleSetVersions(id: string | null) {
  return useQuery<RuleVersionEntry[]>({
    queryKey: ["quant", "ruleset", id, "versions"],
    queryFn: async () => {
      const res = await authFetch(`/api/quant/rulesets/${id}/versions`);
      if (!res.ok) throw new Error("버전 이력 조회 실패");
      return res.json();
    },
    enabled: !!id,
  });
}

function fmtAt(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}.${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function VersionDetail({ row }: { row: VersionRow }) {
  const entry = row.def.entryRules?.conditions ?? [];
  const exit = row.def.exitRules?.conditions ?? [];
  const hard = row.def.hardExits;
  return (
    <dl aria-label={`v${row.version} 룰 (읽기 전용)`} className="m-0 grid gap-2 text-13">
      <div>
        <dt className="text-2xs text-tm-muted">매수 · {(row.def.entryRules?.operator ?? "AND") === "AND" ? "모두 충족" : "하나라도 충족"}</dt>
        {entry.length === 0 ? <dd className="m-0 text-tm-muted">없음</dd> : entry.map((c, i) => <dd key={i} className="m-0 text-tm-soft">{condToText(c)}</dd>)}
      </div>
      <div>
        <dt className="text-2xs text-tm-muted">매도 · {(row.def.exitRules?.operator ?? "OR") === "AND" ? "모두 충족" : "하나라도 충족"}</dt>
        {exit.length === 0 ? <dd className="m-0 text-tm-muted">없음</dd> : exit.map((c, i) => <dd key={i} className="m-0 text-tm-soft">{condToText(c)}</dd>)}
      </div>
      <div>
        <dt className="text-2xs text-tm-muted">사이징 · 강제 청산</dt>
        <dd className="m-0 text-tm-soft">
          1회 투입 {row.def.positionSizing?.value ?? 10}%
          {hard?.maxHoldDays != null && ` · 최대 보유 ${hard.maxHoldDays}거래일`}
          {hard?.trailingStopPct != null && ` · 트레일링 ${hard.trailingStopPct}%`}
        </dd>
      </div>
    </dl>
  );
}

export function VersionPanel({ ruleset, onRestore, restoreBlockedReason }: {
  ruleset: RuleSet;
  onRestore: (row: VersionRow) => void;
  /** 운용 중 등 저장할 수 없는 상태면 이유를 준다 — 불러오기 버튼을 막는다. */
  restoreBlockedReason?: string | null;
}) {
  const { data, isLoading, isError } = useRuleSetVersions(ruleset.id);
  const rows = useMemo(() => buildVersionRows(ruleset, data ?? []), [ruleset, data]);
  const [selected, setSelected] = useState<number | null>(null);
  const sel = rows.find(r => r.version === selected) ?? null;

  return (
    <Panel tabs={["버전"]} actions={[]} closable={false} right={<span className="num text-2xs text-tm-muted">{rows.length}개</span>}>
      {isError && <Notice tone="danger">버전 이력을 불러오지 못했습니다.</Notice>}
      {isLoading ? (
        <p className="m-0 py-2 text-center text-13 text-tm-muted">불러오는 중…</p>
      ) : (
        <ul className="m-0 flex max-h-[260px] list-none flex-col gap-1 overflow-y-auto p-0">
          {rows.map(r => (
            <li key={r.version}>
              <button
                type="button"
                aria-pressed={r.version === selected}
                onClick={() => setSelected(s => (s === r.version ? null : r.version))}
                className={cn(
                  "flex w-full flex-col gap-0.5 rounded-lg border px-2.5 py-2 text-left",
                  r.version === selected ? "border-dracula-purple bg-tm-raised" : "border-tm-line bg-tm-inner hover:border-tm-line2",
                )}
              >
                <span className="flex items-center gap-2 text-13">
                  <b className="num text-dracula-purple">v{r.version}</b>
                  {r.isCurrent && <span className="rounded bg-tm-raised px-1.5 text-2xs text-dracula-green">현재</span>}
                  <span className="num ml-auto text-2xs text-tm-muted">{fmtAt(r.at)}</span>
                </span>
                <span className="text-2xs text-tm-soft">
                  {r.changes == null ? "최초 버전" : r.changes.length === 0 ? "룰 변경 없음 (이름·설명·유니버스만)" : r.changes.join(" · ")}
                </span>
                {r.memo && <span className="text-2xs text-tm-muted">메모: {r.memo}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
      {sel && (
        <div className="flex flex-col gap-2 rounded-lg border border-tm-line p-2.5">
          <VersionDetail row={sel} />
          {!sel.isCurrent && (
            <>
              <Btn kind="ghost" full icon="refresh" disabled={!!restoreBlockedReason} onClick={() => onRestore(sel)}>
                v{sel.version} 룰을 빌더로 불러오기
              </Btn>
              <span className="text-2xs text-tm-muted">
                {restoreBlockedReason ?? "불러온 뒤 \"룰셋 업데이트\"를 눌러야 저장되며, 새 버전으로 기록됩니다. 이름·설명·유니버스는 바뀌지 않습니다."}
              </span>
            </>
          )}
        </div>
      )}
    </Panel>
  );
}
