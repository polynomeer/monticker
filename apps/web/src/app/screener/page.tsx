"use client";

import { useEffect, useState } from "react";
import ScreenerTable, { type ColumnSet } from "@/components/screener/ScreenerTable";
import RangeSliderPreview from "@/components/screener/RangeSliderPreview";
import { useScreener } from "@/hooks/useScreener";
import {
  Btn, Checkbox, Chip, Panel, PanelRow, PreviewTag, Seg, SelectBox, TerminalPage, TitleBlock, fmtPct, dirClass,
} from "@/components/terminal";

// 결과 패널 탭 = 기존 스크리너 탭(서버 tab 파라미터). 시안의 "저장한 스크린" 탭은 저장 기능이 없어 + 버튼만 남겼다.
const TABS = [
  { key: "realtime", label: "실시간 차트" },
  { key: "movers",   label: "급등·급락" },
  { key: "foreign",  label: "외국인·기관 동향" },
];
const MARKETS = [
  { value: "all",      label: "전체" },
  { value: "domestic", label: "국내" },
  { value: "overseas", label: "해외" },
] as const;
const SORTS = [
  { key: "amount", label: "거래대금순" },
  { key: "volume", label: "거래량순" },
  { key: "rise",   label: "급상승" },
  { key: "fall",   label: "급하락" },
];
const MARKET_CAP_TIERS = [
  { value: "all",   label: "전체" },
  { value: "large", label: "대형주" },
  { value: "mid",   label: "중형주" },
  { value: "small", label: "소형주" },
] as const;
const COLUMN_SETS = [
  { value: "basic",     label: "기본" },
  { value: "trade",     label: "거래" },
  { value: "valuation", label: "밸류에이션" },
] as const;

// 시안의 섹터 칩 — 섹터 필터 API가 없어 표시만 한다
const SECTOR_PREVIEW = ["반도체", "2차전지", "인터넷", "바이오", "금융"];
const EVENT_PREVIEW = ["뉴스 동반", "공시 동반", "퀀트 시그널 발생", "감성 급변"];

export default function ScreenerPage() {
  const [tab,           setTab]           = useState("realtime");
  const [market,        setMarket]        = useState<string>("all");
  const [sort,          setSort]          = useState("amount");
  const [marketCapTier, setMarketCapTier] = useState<string>("all");
  const [columnSet,     setColumnSet]     = useState<ColumnSet>("basic");

  const { items, total, hasMore, loading, loadingMore, loadMore, wsConnected } =
    useScreener(tab, market, sort, marketCapTier);

  const showMarketCapTier = market !== "overseas";

  // 마지막 갱신 시각 — 클라이언트에서만 계산(SSR 하이드레이션 불일치 방지)
  const [updatedAt, setUpdatedAt] = useState<string>("—");
  useEffect(() => {
    if (loading) return;
    setUpdatedAt(new Date().toLocaleTimeString("ko-KR", { hour12: false }));
  }, [items, loading]);

  const avgChange = items.length ? items.reduce((a, i) => a + i.changeRate, 0) / items.length : null;

  const reset = () => {
    setTab("realtime");
    setMarket("all");
    setSort("amount");
    setMarketCapTier("all");
    setColumnSet("basic");
  };

  return (
    <TerminalPage
      left={<TitleBlock title="실시간 스크리너" crumb="마켓" />}
      stats={[
        { label: "조건 일치", value: `${total.toLocaleString()}종목`, tone: "text-dracula-purple" },
        { label: "평균 등락", value: fmtPct(avgChange), tone: dirClass(avgChange) },
        { label: "평균 거래량 배수", value: "—" },
        { label: "이벤트 동반", value: "—" },
        { label: "마지막 갱신", value: updatedAt },
      ]}
    >
      <PanelRow>
        {/* ── 필터 ─────────────────────────────────────────── */}
        <Panel tabs={["필터"]} actions={["refresh"]} className="flex-[0_1_300px]">
          <Seg
            full
            options={MARKETS}
            value={market as (typeof MARKETS)[number]["value"]}
            onChange={(v) => {
              setMarket(v);
              if (v === "overseas") setMarketCapTier("all");
            }}
          />

          <div className="flex flex-col gap-2">
            <span className="flex items-center gap-1.5 text-2xs text-tm-muted">섹터 <PreviewTag /></span>
            <div className="flex flex-wrap gap-1.5" aria-disabled="true">
              {SECTOR_PREVIEW.map((s) => <Chip key={s}>{s}</Chip>)}
              <Chip>+ 6</Chip>
            </div>
          </div>

          <RangeSliderPreview label="등락률" from="+1.0%" to="+30%" pa={55} pb={100} />
          <RangeSliderPreview label="거래량 배수 (5분 평균 대비)" from="2.0×" to="10×" pa={20} pb={100} />

          {showMarketCapTier && (
            <div className="flex flex-col gap-2">
              <span className="text-xs text-tm-soft">시가총액</span>
              <Seg full size="sm" options={MARKET_CAP_TIERS} value={marketCapTier as (typeof MARKET_CAP_TIERS)[number]["value"]} onChange={setMarketCapTier} />
            </div>
          )}

          <div className="flex flex-col gap-2.5">
            <span className="flex items-center gap-1.5 text-2xs text-tm-muted">오늘 발생한 이벤트 <PreviewTag /></span>
            {EVENT_PREVIEW.map((e) => <Checkbox key={e} checked={false} label={e} disabled />)}
          </div>

          <SelectBox label="정렬" aria-label="정렬" value={sort} onChange={(e) => setSort(e.target.value)}>
            {SORTS.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
          </SelectBox>

          <div className="flex gap-2">
            <Btn kind="ghost" className="flex-1" onClick={reset}>초기화</Btn>
            <Btn kind="primary" className="flex-1" disabled title="준비 중 — 조건 저장은 아직 지원하지 않습니다">이 조건 저장</Btn>
          </div>
        </Panel>

        {/* ── 결과 ─────────────────────────────────────────── */}
        <Panel
          tabs={TABS}
          active={tab}
          onTabChange={(k) => { setTab(k); setSort("amount"); }}
          actions={["plus", "download", "expand"]}
          className="flex-[999_1_640px]"
          bodyClassName="gap-0 px-1.5 pb-1.5 pt-1"
          right={
            <div className="flex flex-wrap items-center gap-2.5">
              <span className="text-xs text-tm-muted">
                조건 일치 <b className="num text-dracula-fg">{total.toLocaleString()}</b>개 종목 ·{" "}
                <span className={wsConnected ? "text-dracula-green" : undefined}>{wsConnected ? "실시간" : "연결 중..."}</span>
              </span>
              <Seg size="sm" options={COLUMN_SETS} value={columnSet} onChange={setColumnSet} />
            </div>
          }
        >
          <ScreenerTable
            items={items}
            loading={loading}
            loadingMore={loadingMore}
            hasMore={hasMore}
            onLoadMore={loadMore}
            columnSet={columnSet}
          />
        </Panel>
      </PanelRow>
    </TerminalPage>
  );
}
