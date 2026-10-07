"use client";

import { PanelCol, PanelRow, SearchPill, TerminalPage } from "@/components/terminal";
import IndexCards from "@/components/home/IndexCards";
import MoversFeed from "@/components/home/MoversFeed";
import HomeWatchlist from "@/components/home/HomeWatchlist";
import QuantSignals from "@/components/home/QuantSignals";
import SectorHeatmap from "@/components/home/SectorHeatmap";
import { useKrxSession } from "@/components/home/data";

/**
 * 홈 · 마켓 개요 (Home.dc.html). 실시간 스크리너는 /screener 로 옮겼다.
 * 지수·환율·오늘 이벤트 수·급등락 건수는 집계 API가 없어 "—"로 둔다(docs/design-rollout-plan.md).
 */
export default function Home() {
  const session = useKrxSession();
  return (
    <TerminalPage
      left={<SearchPill />}
      stats={[
        { label: "KOSPI", value: "—" },
        { label: "KOSDAQ", value: "—" },
        { label: "USD/KRW", value: "—" },
        { label: "장 상태", value: session?.text ?? "—", tone: session?.open ? "text-dracula-green" : "text-tm-soft" },
        { label: "오늘 이벤트", value: "—", tone: "text-dracula-purple" },
        { label: "급등·급락", value: "—" },
      ]}
    >
      <h1 className="sr-only">홈 · 마켓 개요</h1>
      <PanelRow>
        <PanelCol className="flex-[999_1_640px]">
          <IndexCards />
          <MoversFeed />
        </PanelCol>
        <PanelCol className="flex-[1_1_320px]">
          <HomeWatchlist />
          <QuantSignals />
        </PanelCol>
      </PanelRow>
      <SectorHeatmap />
    </TerminalPage>
  );
}
