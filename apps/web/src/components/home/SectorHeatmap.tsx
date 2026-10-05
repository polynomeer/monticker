"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Panel, PreviewTag } from "@/components/terminal";

interface SectorEventSummary { sector: string; eventType: string; count: number; maxScore: number; }

/**
 * 시안의 섹터 히트맵. 섹터 등락률 API가 없어 색(상승/하락)은 칠하지 않고,
 * 실제로 있는 데이터 — 최근 24시간 섹터별 이벤트 수(/api/sectors/events) — 를 진하기로 보여준다.
 */
export default function SectorHeatmap() {
  const [tab, setTab] = useState("sector");
  const { data = [], isLoading } = useQuery<SectorEventSummary[]>({
    queryKey: ["sectors", "events", 24],
    queryFn: async () => {
      const r = await fetch("/api/sectors/events?hours=24");
      return r.ok ? r.json() : [];
    },
    refetchInterval: 60_000,
    staleTime: 60_000,
  });

  const bySector = new Map<string, number>();
  data.forEach((d) => bySector.set(d.sector, (bySector.get(d.sector) ?? 0) + d.count));
  const sectors = [...bySector.entries()].sort((a, b) => b[1] - a[1]).slice(0, 24);
  const max = Math.max(1, ...sectors.map(([, c]) => c));

  return (
    <Panel
      tabs={[{ key: "sector", label: "섹터 히트맵" }, { key: "theme", label: "테마" }]}
      active={tab}
      onTabChange={setTab}
    >
      {tab === "theme" ? (
        <div className="flex items-center gap-2 py-6 text-13 text-tm-muted">
          테마 분류 데이터가 아직 없습니다. <PreviewTag />
        </div>
      ) : isLoading ? (
        <div className="h-[130px] animate-pulse rounded-lg bg-tm-inner" />
      ) : sectors.length === 0 ? (
        <p className="py-6 text-center text-13 text-tm-muted">최근 24시간 섹터 이벤트가 없습니다.</p>
      ) : (
        <div className="grid gap-1.5" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(96px,1fr))" }}>
          {sectors.map(([name, count]) => (
            <div
              key={name}
              className="flex min-h-[58px] flex-col justify-end gap-0.5 rounded-lg p-2.5"
              style={{ background: `rgba(189,147,249,${(0.12 + (count / max) * 0.6).toFixed(2)})` }}
            >
              <span className="text-xs font-semibold text-dracula-fg">{name}</span>
              <span className="num text-13 text-dracula-fg">이벤트 {count}건</span>
            </div>
          ))}
        </div>
      )}
      <span className="flex items-center gap-1.5 text-2xs text-tm-muted">
        진하기는 최근 24시간 이벤트 수 · 섹터 등락률(상승/하락 색)은 준비 중 <PreviewTag />
      </span>
    </Panel>
  );
}
