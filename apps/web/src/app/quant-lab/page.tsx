"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import type { RuleSet } from "@monticker/types";
import { authFetch } from "@/services/api";
import { BtnLink, Icon, Notice, SelectBox, TerminalPage } from "@/components/terminal";
import { RuleSetCard } from "@/components/quant/RuleSetCard";
import { SegOpts } from "@/components/quant/parts";

type Filter = "ALL" | "LIVE" | "RUNNING" | "BACKTESTED" | "DRAFT";
type Sort = "updated" | "created" | "name";

export default function QuantLabPage() {
  const qc = useQueryClient();
  const [filter, setFilter] = useState<Filter>("ALL");
  const [sort, setSort] = useState<Sort>("updated");

  const { data: ruleSets = [], isLoading, isError } = useQuery<RuleSet[]>({
    queryKey: ["quant", "rulesets"],
    queryFn: async () => {
      const res = await authFetch("/api/quant/rulesets");
      if (!res.ok) throw new Error("룰셋 목록 조회 실패");
      return res.json();
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async (id: string) => {
      const res = await authFetch(`/api/quant/rulesets/${id}`, { method: "DELETE" });
      if (!res.ok) throw new Error("삭제 실패");
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["quant", "rulesets"] }),
  });

  const count = (s: string) => ruleSets.filter((r) => r.status === s).length;
  const running = count("RUNNING");
  const backtested = count("BACKTESTED");
  const drafts = count("DRAFT");

  const shown = useMemo(() => {
    const list = filter === "ALL" ? ruleSets : filter === "LIVE" ? [] : ruleSets.filter((r) => r.status === filter);
    const by: Record<Sort, (a: RuleSet, b: RuleSet) => number> = {
      updated: (a, b) => b.updatedAt.localeCompare(a.updatedAt),
      created: (a, b) => b.createdAt.localeCompare(a.createdAt),
      name: (a, b) => a.name.localeCompare(b.name, "ko"),
    };
    return [...list].sort(by[sort]);
  }, [ruleSets, filter, sort]);

  return (
    <TerminalPage
      title="퀀트랩"
      crumb="내 전략 보관함"
      stats={[
        { label: "내 전략", value: `${ruleSets.length}개` },
        // 실전 자동 운용은 아직 없다 — 포워드 테스트와 구분해서 비워 둔다.
        { label: "운용 중", value: "—", tone: "text-tm-muted" },
        { label: "포워드 테스트", value: `${running}개`, tone: "text-dracula-purple" },
        { label: "오늘 신호", value: "—", tone: "text-tm-muted" },
        { label: "구독 중", value: "—", tone: "text-tm-muted" },
      ]}
    >
      <div className="flex flex-col gap-2.5 p-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <SegOpts<Filter>
            label="상태 필터"
            size="lg"
            value={filter}
            onChange={setFilter}
            options={[
              { value: "ALL", label: `전체 ${ruleSets.length}` },
              { value: "LIVE", label: "운용 중", disabled: true },
              { value: "RUNNING", label: `포워드 테스트 ${running}` },
              { value: "BACKTESTED", label: `백테스트 완료 ${backtested}` },
              { value: "DRAFT", label: `초안 ${drafts}` },
            ]}
          />
          <div className="ml-auto flex items-center gap-2">
            <SelectBox aria-label="정렬" value={sort} onChange={(e) => setSort(e.target.value as Sort)} className="min-h-[34px] w-auto py-0">
              <option value="updated">최근 수정 순</option>
              <option value="created">최근 생성 순</option>
              <option value="name">이름 순</option>
            </SelectBox>
            <BtnLink href="/quant-lab/builder" icon="plus" className="h-[34px]">새 전략</BtnLink>
          </div>
        </div>

        {isError && <Notice tone="danger">룰셋 목록을 불러오지 못했습니다. 로그인 상태를 확인하고 다시 시도해 주세요.</Notice>}

        <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(300px,1fr))" }}>
          <Link
            href="/quant-lab/builder"
            className="flex min-h-[220px] flex-col items-center justify-center gap-2.5 rounded-xl border-[1.5px] border-dashed border-tm-line2 text-tm-soft hover:border-dracula-purple hover:text-dracula-fg"
          >
            <span className="grid h-11 w-11 place-items-center rounded-full bg-tm-raised text-dracula-purple">
              <Icon name="plus" size={22} />
            </span>
            <span className="font-semibold">새 전략 만들기</span>
            <span className="text-xs text-tm-muted">코딩 없이 조건을 조합</span>
          </Link>

          {isLoading
            ? [1, 2, 3, 4, 5].map((i) => <div key={i} className="h-[220px] animate-pulse rounded-xl bg-tm-panel" />)
            : shown.map((rs) => (
                <RuleSetCard
                  key={rs.id}
                  rs={rs}
                  deleting={deleteMutation.isPending && deleteMutation.variables === rs.id}
                  onDelete={() => {
                    if (confirm(`"${rs.name}" 룰셋을 삭제할까요?`)) deleteMutation.mutate(rs.id);
                  }}
                />
              ))}
        </div>

        {!isLoading && !isError && shown.length === 0 && (
          <p className="m-0 py-6 text-center text-13 text-tm-muted">
            {filter === "LIVE"
              ? "실전 자동 운용은 준비 중입니다."
              : ruleSets.length === 0
                ? "아직 룰셋이 없습니다 — 투자 아이디어를 조건식으로 만들고 백테스트로 검증해 보세요."
                : "이 상태의 전략이 없습니다."}
          </p>
        )}

        <Notice tone="info">백테스트와 포워드 테스트 결과는 과거·모의 데이터 기반이며 미래 수익을 보장하지 않습니다.</Notice>
      </div>
    </TerminalPage>
  );
}
