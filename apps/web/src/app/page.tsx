"use client";

import { PanelCol, PanelRow, SearchPill, TerminalPage, dirClass, fmtPct } from "@/components/terminal";
import IndexCards from "@/components/home/IndexCards";
import MoversFeed from "@/components/home/MoversFeed";
import HomeWatchlist from "@/components/home/HomeWatchlist";
import QuantSignals from "@/components/home/QuantSignals";
import SectorHeatmap from "@/components/home/SectorHeatmap";
import { fmtIndexValue, useEventSummary, useKrxSession, useMarketIndices, type MarketIndex } from "@/components/home/data";

/** 상단 지수 스탯 값 — 모의 값이면 "(모의)"를 붙여 실시세로 읽히지 않게 한다. */
function indexStat(label: string, ix: MarketIndex | undefined) {
  if (!ix) return { label, value: "—" };
  return {
    label: ix.isMocked ? `${label} (모의)` : label,
    value: `${fmtIndexValue(ix.value)} ${fmtPct(ix.changeRate)}`,
    tone: dirClass(ix.changeRate),
  };
}

/**
 * 홈 · 마켓 개요 (Home.dc.html). 실시간 스크리너는 /screener 로 옮겼다.
 * 오늘 이벤트 수·급등락 종목 수는 GET /api/events/summary(KST 하루, ADR-087). 불러오지 못하면 "—".
 */
export default function Home() {
  const session = useKrxSession();
  const { data: indices = [] } = useMarketIndices();
  const ix = new Map(indices.map((i) => [i.code, i]));
  const { data: summary } = useEventSummary();
  return (
    <TerminalPage
      left={<SearchPill />}
      stats={[
        indexStat("KOSPI", ix.get("KOSPI")),
        indexStat("KOSDAQ", ix.get("KOSDAQ")),
        indexStat("USD/KRW", ix.get("USDKRW")),
        { label: "장 상태", value: session?.text ?? "—", tone: session?.open ? "text-dracula-green" : "text-tm-soft" },
        { label: "오늘 이벤트", value: summary ? summary.total.toLocaleString("ko-KR") : "—", tone: "text-dracula-purple" },
        {
          label: "급등·급락 종목",
          value: summary ? (
            <>
              <span className="text-up">{summary.surgeStocks.toLocaleString("ko-KR")}</span>
              <span className="text-tm-muted"> · </span>
              <span className="text-down">{summary.plungeStocks.toLocaleString("ko-KR")}</span>
            </>
          ) : "—",
        },
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
