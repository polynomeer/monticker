"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { MarketStrategy } from "@monticker/types";
import { authFetch } from "@/services/api";
import { Btn, BtnLink, Notice, Panel, PanelCol, PanelRow, SelectBox, TerminalPage } from "@/components/terminal";
import { SegOpts } from "@/components/quant/parts";
import { MarketStrategyCard } from "@/components/strategy-market/MarketStrategyCard";
import { SubscribedSignalsPanel } from "@/components/strategy-market/SubscribedSignalsPanel";

const PAGE_SIZE = 20;
type Filter = "popular" | "new" | "verified" | "free" | "subscribed";
type Sort = "subs" | "recent" | "forward";

export default function StrategyMarketPage() {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState<Filter>("popular");
  const [sort, setSort] = useState<Sort>("subs");

  const { data: strategies, isLoading, isError } = useQuery<MarketStrategy[]>({
    queryKey: ["quant", "market", page],
    queryFn: () => authFetch(`/api/quant/market?page=${page}&size=${PAGE_SIZE}`).then(r => r.json()),
  });

  const list = useMemo(() => strategies ?? [], [strategies]);
  const subscribed = list.filter(s => s.isSubscribed);

  const shown = useMemo(() => {
    let l = list;
    if (filter === "free") l = l.filter(s => s.price === 0);
    if (filter === "subscribed") l = l.filter(s => s.isSubscribed);
    if (filter === "verified") l = [];
    const key: Sort = filter === "popular" ? "subs" : filter === "new" ? "recent" : sort;
    const cmp: Record<Sort, (a: MarketStrategy, b: MarketStrategy) => number> = {
      subs: (a, b) => b.subscribe_count - a.subscribe_count,
      recent: (a, b) => b.created_at.localeCompare(a.created_at),
      forward: () => 0,
    };
    return [...l].sort(cmp[key]);
  }, [list, filter, sort]);

  return (
    <TerminalPage
      title="전략 마켓"
      crumb="커뮤니티 공유 전략"
      stats={[
        { label: "공유 전략", value: isLoading ? "—" : `${list.length}${list.length === PAGE_SIZE ? "+" : ""}개` },
        { label: "검증 배지", value: "—", tone: "text-tm-muted" },
        { label: "구독 중", value: `${subscribed.length}개` },
        { label: "이번 달 신호", value: "—", tone: "text-tm-muted" },
      ]}
    >
      <PanelRow>
        <PanelCol className="flex-[999_1_640px] p-1.5">
          <div className="flex flex-wrap items-center gap-2">
            <SegOpts<Filter>
              label="전략 필터"
              size="lg"
              value={filter}
              onChange={setFilter}
              options={[
                { value: "popular", label: "인기" },
                { value: "new", label: "신규" },
                { value: "verified", label: "검증 배지", disabled: true },
                { value: "free", label: "무료" },
                { value: "subscribed", label: `구독 중 ${subscribed.length}` },
              ]}
            />
            <div className="ml-auto flex items-center gap-2">
              <SelectBox aria-label="정렬" value={sort} onChange={e => setSort(e.target.value as Sort)} className="min-h-[34px] w-auto py-0" disabled={filter === "popular" || filter === "new"}>
                <option value="subs">구독자 많은 순</option>
                <option value="recent">최근 등록 순</option>
                <option value="forward" disabled>포워드 기간 긴 순 (준비 중)</option>
              </SelectBox>
              <BtnLink href="/quant-lab" kind="ghost" icon="share" className="h-[34px]">내 전략 공유</BtnLink>
            </div>
          </div>

          {isError && <Notice tone="danger">전략 목록을 불러오지 못했습니다.</Notice>}

          {isLoading ? (
            <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(290px,1fr))" }}>
              {Array.from({ length: 6 }).map((_, i) => <div key={i} className="h-[230px] animate-pulse rounded-xl bg-tm-panel" />)}
            </div>
          ) : list.length === 0 ? (
            <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-tm-line2 py-16 text-center">
              <p className="m-0 font-semibold">아직 공유된 전략이 없습니다</p>
              <p className="m-0 text-13 text-tm-muted">내 룰셋을 공유해서 커뮤니티와 함께하세요</p>
              <BtnLink href="/quant-lab/builder" icon="plus">룰셋 만들기</BtnLink>
            </div>
          ) : shown.length === 0 ? (
            <p className="m-0 py-10 text-center text-13 text-tm-muted">
              {filter === "verified" ? "검증 배지 심사는 준비 중입니다." : "조건에 맞는 전략이 없습니다."}
            </p>
          ) : (
            <div className="grid gap-2" style={{ gridTemplateColumns: "repeat(auto-fill,minmax(290px,1fr))" }}>
              {shown.map(s => <MarketStrategyCard key={s.id} strategy={s} />)}
            </div>
          )}

          {(page > 0 || list.length === PAGE_SIZE) && (
            <div className="flex justify-center gap-2 pt-2">
              {page > 0 && <Btn kind="soft" size="sm" onClick={() => setPage(p => p - 1)}>이전</Btn>}
              {list.length === PAGE_SIZE && <Btn kind="soft" size="sm" onClick={() => setPage(p => p + 1)}>다음</Btn>}
            </div>
          )}
        </PanelCol>

        <PanelCol className="flex-[1_1_320px]">
          <SubscribedSignalsPanel subscribed={subscribed} />
          <Panel tabs={["검증 배지란?"]} actions={[]} closable={false} preview>
            <p className="m-0 text-13 leading-[1.7] text-tm-soft">
              백테스트만이 아니라 <b className="text-dracula-fg">12주 이상 실시간 포워드 테스트</b>에서 신호 일치율 85% 이상을 유지한 전략에 부여할 예정입니다. 룰셋은 서버에서만 실행되어 구독자에게 조건식이 노출되지 않습니다.
            </p>
          </Panel>
          <Panel tabs={["제작자"]} actions={[]} closable={false}>
            <p className="m-0 text-13 text-tm-soft">내 전략의 구독 수익과 출금은 제작자 수익 대시보드에서 관리합니다.</p>
            <BtnLink href="/quant-lab/earnings" kind="ghost" icon="wallet" full>제작자 수익 대시보드</BtnLink>
          </Panel>
        </PanelCol>
      </PanelRow>
    </TerminalPage>
  );
}
