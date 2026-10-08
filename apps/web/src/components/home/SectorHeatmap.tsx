"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Panel, PreviewTag, fmtPct } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { heatBackground, heatmapSectors, type SectorPerformanceResponse } from "./sectorHeat";
import { useIsLoggedIn } from "./data";
import { useInterestOrdering } from "@/hooks/useUserPreferences";
import { interestFirst, isInterestSector } from "@/lib/interestSectors";
import { InterestMark, InterestOrderingToggle } from "@/components/interest/InterestOrderingToggle";

/**
 * 시안의 섹터 히트맵. 색은 섹터 등락률(GET /api/screener/sectors/performance, ADR-087):
 * 종목 등락률은 스크리너와 같은 식(최신 1분봉 종가 vs 직전 일봉 종가), 섹터 값은 그 단순 평균(동일가중).
 * 오늘(KST) 이벤트 수는 보조 정보로 함께 보여준다.
 * ADR-099 — 관심 분야가 있고 "관심 분야 순"이 켜져 있으면, 그릴 섹터(같은 24개)를 관심 분야 먼저로 다시 줄 세우고 표시한다.
 * 그릴 섹터 집합은 스위치와 상관없이 같다(자르기 → 정렬 순서).
 */
export default function SectorHeatmap() {
  const [tab, setTab] = useState("sector");
  const { data, isLoading, isError } = useQuery<SectorPerformanceResponse>({
    queryKey: ["sectors", "performance", "all"],
    queryFn: async () => {
      const r = await fetch("/api/screener/sectors/performance?market=all");
      if (!r.ok) throw new Error(`sector performance ${r.status}`);
      return r.json();
    },
    refetchInterval: 60_000,
    staleTime: 30_000,
  });

  const isLoggedIn = useIsLoggedIn();
  const interest = useInterestOrdering(isLoggedIn);
  const sectors = interestFirst(heatmapSectors(data?.sectors ?? []), (s) => s.sector, interest.interests, interest.active);

  return (
    <Panel
      tabs={[{ key: "sector", label: "섹터 히트맵" }, { key: "theme", label: "테마" }]}
      active={tab}
      onTabChange={setTab}
      right={interest.available && tab === "sector" ? <InterestOrderingToggle active={interest.active} onChange={interest.setEnabled} /> : undefined}
    >
      {tab === "theme" ? (
        <div className="flex items-center gap-2 py-6 text-13 text-tm-muted">
          테마 분류 데이터가 아직 없습니다. <PreviewTag />
        </div>
      ) : isLoading ? (
        <div className="h-[130px] animate-pulse rounded-lg bg-tm-inner" />
      ) : isError ? (
        <p className="py-6 text-center text-13 text-tm-muted">섹터 등락률을 불러오지 못했습니다.</p>
      ) : sectors.length === 0 ? (
        <p className="py-6 text-center text-13 text-tm-muted">섹터 정보가 있는 종목이 없습니다.</p>
      ) : (
        <div className="grid gap-1.5" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(96px,1fr))" }}>
          {sectors.map((s) => {
            const bg = heatBackground(s.avgChangeRate);
            const mine = interest.active && isInterestSector(s.sector, interest.interests);
            return (
              <div
                key={s.sector}
                data-interest={mine || undefined}
                title={`${s.sector}${mine ? " · 관심 분야" : ""} · 상승 ${s.advancers} / 하락 ${s.decliners} / 보합 ${s.unchanged} · 시세 ${s.pricedCount}/${s.stockCount}종목`}
                className={cn("flex min-h-[58px] flex-col justify-end gap-0.5 rounded-lg p-2.5", !bg && "bg-tm-inner", mine && "ring-1 ring-dracula-purple")}
                style={bg ? { background: bg } : undefined}
              >
                <span className="flex min-w-0 items-center gap-1">
                  <span className="truncate text-xs font-semibold text-dracula-fg">{s.sector}</span>
                  {mine && <InterestMark />}
                </span>
                <span className={s.avgChangeRate == null ? "num text-13 text-tm-muted" : "num text-13 text-dracula-fg"}>
                  {fmtPct(s.avgChangeRate)}
                </span>
                {s.eventCount > 0 && <span className="num text-2xs text-dracula-fg/80">이벤트 {s.eventCount}건</span>}
              </div>
            );
          })}
        </div>
      )}
      <span className="text-2xs text-tm-muted">
        섹터 등락률 = 종목 등락률(전일 종가 대비) 단순 평균 · 이벤트는 오늘(KST) 건수 · 시세가 없는 섹터는 —
        {interest.active && " · 관심 분야 섹터를 앞에 표시(숨기는 섹터 없음)"}
      </span>
    </Panel>
  );
}
