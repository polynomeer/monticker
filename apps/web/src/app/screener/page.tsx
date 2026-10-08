"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import ScreenerTable, { type ColumnSet } from "@/components/screener/ScreenerTable";
import RangeSlider from "@/components/screener/RangeSlider";
import MarketCapRange from "@/components/screener/MarketCapRange";
import { useScreener } from "@/hooks/useScreener";
import { useIsLoggedIn } from "@/components/home/data";
import {
  CHANGE_STOPS, DEFAULT_CRITERIA, DOMESTIC_SEGMENTS, EVENT_OPTIONS, MARKET_GROUPS, VOL_MULT_STOPS,
  changeRangeLabel, marketGroup, changeToStops, sameCriteria, stopsToChange, volMultLabel, volMultToStop,
  type ScreenerCriteria, type ScreenerEvent,
} from "@/components/screener/criteria";
import { useSavedScreenMutations, useSavedScreens } from "@/components/screener/savedScreens";
import {
  Btn, Checkbox, Chip, Notice, Panel, PanelRow, Seg, SelectBox, TerminalPage, TitleBlock, fmtPct, dirClass,
} from "@/components/terminal";

// 결과 패널 탭 = 기존 스크리너 탭(서버 tab 파라미터) + 저장한 스크린(saved:{id}).
const BASE_TABS = [
  { key: "realtime", label: "실시간 차트" },
  { key: "movers",   label: "급등·급락" },
  { key: "foreign",  label: "외국인·기관 동향" },
];
const SORTS = [
  { key: "amount",  label: "거래대금순" },
  { key: "volume",  label: "거래량순" },
  { key: "volmult", label: "거래량 배수순" },
  { key: "rise",    label: "급상승" },
  { key: "fall",    label: "급하락" },
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
/** 접힌 상태에서 보이는 섹터 칩 수 */
const SECTOR_PREVIEW = 5;

interface SectorCount { sector: string; count: number; }

function useSectors(market: string) {
  return useQuery<SectorCount[]>({
    queryKey: ["screener", "sectors", market],
    queryFn: async () => {
      const r = await fetch(`/api/screener/sectors?market=${market}`);
      return r.ok ? r.json() : [];
    },
    staleTime: 10 * 60_000,
  });
}

export default function ScreenerPage() {
  const isLoggedIn = useIsLoggedIn();
  const [tab,       setTab]       = useState("realtime");
  const [criteria,  setCriteria]  = useState<ScreenerCriteria>(DEFAULT_CRITERIA);
  const [columnSet, setColumnSet] = useState<ColumnSet>("basic");
  const [sectorsOpen, setSectorsOpen] = useState(false);
  const [naming, setNaming] = useState<string | null>(null);   // 저장 이름 입력 중이면 값

  const patch = (p: Partial<ScreenerCriteria>) => setCriteria((c) => ({ ...c, ...p }));

  const { data: saved = [] } = useSavedScreens(isLoggedIn);
  const { save, remove } = useSavedScreenMutations();
  const activeSaved = tab.startsWith("saved:") ? saved.find((s) => `saved:${s.id}` === tab) ?? null : null;
  const savedDirty = activeSaved != null && !sameCriteria(activeSaved.criteria, criteria);

  // 저장한 스크린 탭은 서버에 "realtime" 탭 + 그 조건으로 보낸다
  const serverTab = tab.startsWith("saved:") ? "realtime" : tab;
  const { items, total, hasMore, loading, loadingMore, loadMore, wsConnected, error } = useScreener(serverTab, criteria);
  const { data: sectors = [] } = useSectors(criteria.market);

  // 저장 스크린이 지워졌으면 기본 탭으로
  useEffect(() => {
    if (tab.startsWith("saved:") && saved.length > 0 && !activeSaved) setTab("realtime");
  }, [tab, saved, activeSaved]);

  const showMarketCapTier = criteria.market !== "overseas";

  // 마지막 갱신 시각 — 클라이언트에서만 계산(SSR 하이드레이션 불일치 방지)
  const [updatedAt, setUpdatedAt] = useState<string>("—");
  useEffect(() => {
    if (loading) return;
    setUpdatedAt(new Date().toLocaleTimeString("ko-KR", { hour12: false }));
  }, [items, loading]);

  // 상단 요약은 불러온 행 기준(평균 등락과 같은 범위)
  const avgChange = items.length ? items.reduce((a, i) => a + i.changeRate, 0) / items.length : null;
  const mults = items.map((i) => i.volumeMultiple).filter((v): v is number => v != null);
  const avgMult = mults.length ? mults.reduce((a, v) => a + v, 0) / mults.length : null;
  const withEvents = items.filter((i) => (i.todayEvents?.length ?? 0) > 0).length;

  const reset = () => {
    setTab("realtime");
    setCriteria(DEFAULT_CRITERIA);
    setColumnSet("basic");
  };

  const toggleSector = (s: string) =>
    patch({ sectors: criteria.sectors.includes(s) ? criteria.sectors.filter((x) => x !== s) : [...criteria.sectors, s] });
  const toggleEvent = (e: ScreenerEvent) =>
    patch({ events: criteria.events.includes(e) ? criteria.events.filter((x) => x !== e) : [...criteria.events, e] });

  // 고른 섹터는 접혀 있어도 보이게 앞에 둔다
  const sectorList = [
    ...criteria.sectors.filter((s) => !sectors.some((x) => x.sector === s)).map((s) => ({ sector: s, count: 0 })),
    ...sectors,
  ];
  const visibleSectors = sectorsOpen
    ? sectorList
    : [...sectorList.filter((s) => criteria.sectors.includes(s.sector)), ...sectorList.filter((s) => !criteria.sectors.includes(s.sector))].slice(0, Math.max(SECTOR_PREVIEW, criteria.sectors.length));
  const hiddenSectors = sectorList.length - visibleSectors.length;

  const [ca, cb] = changeToStops(criteria.minChange, criteria.maxChange);

  const submitSave = async () => {
    const name = (naming ?? "").trim();
    if (!name) return;
    try {
      const s = await save.mutateAsync({ name, criteria });
      setNaming(null);
      setTab(`saved:${s.id}`);
    } catch { /* 메시지는 save.error로 */ }
  };

  const tabs = [...BASE_TABS, ...saved.map((s) => ({ key: `saved:${s.id}`, label: `★ ${s.name}` }))];

  return (
    <TerminalPage
      left={<TitleBlock title="실시간 스크리너" crumb="마켓" />}
      stats={[
        { label: "조건 일치", value: `${total.toLocaleString()}종목`, tone: "text-dracula-purple" },
        { label: "평균 등락", value: fmtPct(avgChange), tone: dirClass(avgChange) },
        { label: "평균 거래량 배수", value: avgMult == null ? "—" : `${avgMult.toFixed(1)}×` },
        { label: "이벤트 동반", value: items.length ? `${withEvents}종목` : "—" },
        { label: "마지막 갱신", value: updatedAt },
      ]}
    >
      <PanelRow>
        {/* ── 필터 ─────────────────────────────────────────── */}
        <Panel tabs={["필터"]} actions={["refresh"]} className="flex-[0_1_300px]">
          <Seg
            full
            options={MARKET_GROUPS}
            value={marketGroup(criteria.market)}
            onChange={(v) => patch({ market: v, ...(v === "overseas" ? { marketCapTier: "all", minCap: null, maxCap: null } : {}) })}
          />
          {marketGroup(criteria.market) === "domestic" && (
            <Seg
              full
              size="sm"
              options={DOMESTIC_SEGMENTS}
              value={criteria.market as (typeof DOMESTIC_SEGMENTS)[number]["value"]}
              onChange={(v) => patch({ market: v })}
            />
          )}

          <div className="flex flex-col gap-2">
            <span className="text-2xs text-tm-muted">섹터{criteria.sectors.length > 0 && ` · ${criteria.sectors.length}개 선택`}</span>
            {sectorList.length === 0 ? (
              <span className="text-xs text-tm-muted">섹터 정보가 있는 종목이 없습니다.</span>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {visibleSectors.map((s) => (
                  <Chip key={s.sector} active={criteria.sectors.includes(s.sector)} onClick={() => toggleSector(s.sector)}>{s.sector}</Chip>
                ))}
                {(hiddenSectors > 0 || sectorsOpen) && (
                  <Chip onClick={() => setSectorsOpen((v) => !v)}>{sectorsOpen ? "접기" : `+ ${hiddenSectors}`}</Chip>
                )}
              </div>
            )}
          </div>

          <RangeSlider
            label="등락률"
            valueLabel={changeRangeLabel(criteria.minChange, criteria.maxChange)}
            max={CHANGE_STOPS.length - 1}
            a={ca}
            b={cb}
            onChange={(a, b) => patch(stopsToChange(a, b))}
          />
          <RangeSlider
            single
            label="거래량 배수 (20일 평균 대비)"
            valueLabel={volMultLabel(criteria.minVolMult)}
            max={VOL_MULT_STOPS.length - 1}
            a={volMultToStop(criteria.minVolMult)}
            onChange={(a) => patch({ minVolMult: VOL_MULT_STOPS[a] ?? null })}
          />

          {showMarketCapTier && (
            <div className="flex flex-col gap-2">
              <span className="text-xs text-tm-soft">시가총액</span>
              <Seg full size="sm" options={MARKET_CAP_TIERS} value={criteria.marketCapTier as (typeof MARKET_CAP_TIERS)[number]["value"]} onChange={(v) => patch({ marketCapTier: v })} />
              <MarketCapRange minCap={criteria.minCap} maxCap={criteria.maxCap} onApply={patch} />
            </div>
          )}

          <div className="flex flex-col gap-2.5">
            <span className="text-2xs text-tm-muted">오늘 발생한 이벤트 (모두 만족)</span>
            {EVENT_OPTIONS.map((e) => {
              const needsLogin = e.key === "QUANT_SIGNAL" && !isLoggedIn;
              return (
                <Checkbox
                  key={e.key}
                  checked={criteria.events.includes(e.key)}
                  onChange={() => toggleEvent(e.key)}
                  label={e.label}
                  sub={needsLogin ? "로그인하면 쓸 수 있습니다" : e.sub}
                  disabled={needsLogin}
                />
              );
            })}
          </div>

          <SelectBox label="정렬" aria-label="정렬" value={criteria.sort} onChange={(e) => patch({ sort: e.target.value })}>
            {SORTS.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
          </SelectBox>

          {naming != null ? (
            <form className="flex flex-col gap-2" onSubmit={(e) => { e.preventDefault(); submitSave(); }}>
              <label className="flex flex-col gap-1 text-xs text-tm-soft">
                스크린 이름
                <input
                  autoFocus
                  maxLength={40}
                  value={naming}
                  onChange={(e) => setNaming(e.target.value)}
                  className="h-9 rounded-lg border border-tm-line2 bg-tm-inner px-3 text-13 text-dracula-fg outline-none focus:border-dracula-purple"
                />
              </label>
              {save.error && <span className="text-xs text-[#ff8a8a]">{(save.error as Error).message}</span>}
              <div className="flex gap-2">
                <Btn kind="ghost" className="flex-1" onClick={() => { setNaming(null); save.reset(); }}>취소</Btn>
                <Btn kind="primary" className="flex-1" type="submit" disabled={!naming.trim() || save.isPending}>저장</Btn>
              </div>
            </form>
          ) : (
            <div className="flex gap-2">
              <Btn kind="ghost" className="flex-1" onClick={reset}>초기화</Btn>
              {activeSaved && savedDirty ? (
                <Btn
                  kind="primary"
                  className="flex-1"
                  disabled={save.isPending}
                  onClick={() => save.mutate({ id: activeSaved.id, name: activeSaved.name, criteria })}
                >
                  변경 사항 저장
                </Btn>
              ) : (
                <Btn
                  kind="primary"
                  className="flex-1"
                  disabled={!isLoggedIn}
                  title={isLoggedIn ? undefined : "로그인하면 조건을 저장할 수 있습니다"}
                  onClick={() => { save.reset(); setNaming(""); }}
                >
                  이 조건 저장
                </Btn>
              )}
            </div>
          )}
          {activeSaved && naming == null && (
            <button
              type="button"
              className="self-start text-xs text-tm-muted hover:text-[#ff8a8a]"
              onClick={() => { if (window.confirm(`'${activeSaved.name}' 스크린을 삭제할까요?`)) remove.mutate(activeSaved.id); }}
            >
              ‘{activeSaved.name}’ 삭제
            </button>
          )}
        </Panel>

        {/* ── 결과 ─────────────────────────────────────────── */}
        <Panel
          tabs={tabs}
          active={tab}
          closable={false}
          onTabChange={(k) => {
            setTab(k);
            const s = saved.find((x) => `saved:${x.id}` === k);
            if (s) setCriteria(s.criteria);
            else setCriteria((c) => ({ ...c, sort: "amount" }));
          }}
          actions={["plus", "download", "expand"]}
          onAction={(a) => { if (a === "plus" && isLoggedIn) { save.reset(); setNaming(""); } }}
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
          {error && (
            <Notice tone="warn" className="m-2">
              {error.status === 401 ? "퀀트 시그널 조건은 로그인 후 쓸 수 있습니다." : error.message}
            </Notice>
          )}
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
