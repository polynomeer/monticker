"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import type { QuantBacktestResult, RuleSet } from "@monticker/types";
import { authFetch } from "@/services/api";
import { Icon, IconBtn, Pill, Sparkline, Stat, fmtPct } from "@/components/terminal";
import { rulesetStatus } from "./parts";

/** 상세 화면과 같은 queryKey — 카드에서 받은 결과를 상세 화면이 그대로 재사용한다. */
export function useRuleSetBacktests(id: string | null | undefined, enabled = true) {
  return useQuery<QuantBacktestResult[]>({
    queryKey: ["quant", "backtest", id],
    queryFn: async () => {
      const res = await authFetch(`/api/quant/rulesets/${id}/backtest`);
      if (!res.ok) throw new Error("백테스트 결과 조회 실패");
      return res.json();
    },
    enabled: !!id && enabled,
    staleTime: 60_000,
  });
}

/** 시안 strat_card — 이름·설명·상태, 최신 백테스트 자산 곡선, CAGR/MDD/포워드 일치. */
export function RuleSetCard({ rs, onDelete, deleting }: { rs: RuleSet; onDelete: () => void; deleting?: boolean }) {
  const st = rulesetStatus(rs.status);
  // 초안은 백테스트가 없으니 조회하지 않는다.
  const { data: results } = useRuleSetBacktests(rs.id, rs.status !== "DRAFT");
  const latest = results?.[0];
  const curve = latest?.equityCurve.map((p) => p.equity) ?? [];
  const cagr = latest?.annualReturn ?? null;

  return (
    <article className="relative flex min-w-0 flex-col gap-3 rounded-xl bg-tm-panel p-4 text-dracula-fg hover:bg-[#2c2e3c]">
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-[3px]">
          <Link
            href={`/quant-lab/${rs.id}`}
            className="truncate text-15 font-bold text-dracula-fg after:absolute after:inset-0 after:rounded-xl after:content-['']"
          >
            {rs.name}
          </Link>
          <span className="truncate text-xs text-tm-muted">{rs.description || "설명 없음"}</span>
        </div>
        <Pill tone={st.tone}>{st.label}</Pill>
      </div>

      {curve.length > 1 ? (
        <Sparkline values={curve} color="#bd93f9" width={300} height={64} stretch />
      ) : (
        <div className="grid h-16 place-items-center rounded-lg bg-tm-inner text-2xs text-tm-muted">
          {rs.status === "DRAFT" ? "백테스트 전" : "자산 곡선 없음"}
        </div>
      )}

      <div className="grid grid-cols-3 gap-2">
        <Stat label="CAGR" value={fmtPct(cagr, 1)} valueClassName={cagr == null ? "text-tm-muted" : cagr >= 0 ? "text-up" : "text-down"} />
        <Stat label="MDD" value={fmtPct(latest?.mdd ?? null, 1)} valueClassName={latest?.mdd == null ? "text-tm-muted" : Math.abs(latest.mdd) >= 0.05 ? "text-down" : undefined} />
        {/* 포워드 신호 일치율 — 백엔드에 지표가 없다(설계만 반영) */}
        <Stat label="포워드 일치" value="—" valueClassName="text-tm-muted" />
      </div>

      <div className="relative z-10 flex items-center justify-between gap-2 border-t border-tm-line pt-2.5">
        <span className="num text-2xs text-tm-muted">
          v{rs.version} · 수정 {new Date(rs.updatedAt).toLocaleDateString("ko-KR")}
        </span>
        <span className="flex">
          <Link
            href={`/quant-lab/builder?edit=${rs.id}`}
            aria-label={`${rs.name} 수정`}
            title="수정"
            className="grid h-7 w-7 place-items-center rounded-lg text-tm-muted hover:bg-tm-raised hover:text-dracula-fg"
          >
            <Icon name="pencil" size={14} />
          </Link>
          <IconBtn name="trash" label={`${rs.name} 삭제`} size={28} iconSize={14} disabled={deleting} onClick={onDelete} className="hover:text-[#ff8a8a]" />
        </span>
      </div>
    </article>
  );
}
