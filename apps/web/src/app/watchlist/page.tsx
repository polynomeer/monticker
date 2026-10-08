"use client";

import { useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import {
  Btn, BtnLink, DataTable, EventBadge, Icon, IconBtn, Panel, PanelRow, Seg, SelectBox, Sparkline, TerminalPage, TitleBlock,
  dirClass, fmtNum, fmtPct, fmtSigned, type Column,
} from "@/components/terminal";
import {
  eventLabel, useIsLoggedIn, useQuotes, useRecentEvents, useWatchlistGroups,
  type RecentEvent, type WatchlistGroup, type WatchlistItem,
} from "@/components/home/data";
import SelectedStockPanel from "@/components/watchlist/SelectedStockPanel";
import RowMenu from "@/components/watchlist/RowMenu";
import GroupDeleteConfirm from "@/components/watchlist/GroupDeleteConfirm";
import { useWatchRules } from "@/hooks/useWatchRules";
import { applyMove, moveTarget, type MoveDir } from "@/components/watchlist/order";
import { useIntradaySeriesChunked } from "@/hooks/useIntradaySeries";
import { useThemeStore, CHART_THEMES } from "@/stores/themeStore";
import { downloadCsv, kstDateStamp } from "@/components/portfolio/csv";

interface AlertRule { id: number; stockId: number | null; ruleType: string; }

const MARKET_TABS = [
  { value: "all",      label: "전체" },
  { value: "domestic", label: "국내" },
  { value: "overseas", label: "해외" },
] as const;
type MarketTab = (typeof MARKET_TABS)[number]["value"];

const SORT_OPTIONS = [
  { key: "custom", label: "내 순서" },
  { key: "name",   label: "이름순" },
  { key: "change", label: "등락률순" },
  { key: "price",  label: "현재가순" },
] as const;
type Sort = (typeof SORT_OPTIONS)[number]["key"];

function isDomestic(market: string) { return market === "KOSPI" || market === "KOSDAQ"; }

function isTodayKst(iso: string) {
  const f = (d: Date) => d.toLocaleDateString("ko-KR", { timeZone: "Asia/Seoul" });
  return f(new Date(iso)) === f(new Date());
}

export default function WatchlistPage() {
  const isLoggedIn = useIsLoggedIn();

  const [newGroupName, setNewGroupName] = useState("");
  const [showNewGroup, setShowNewGroup] = useState(false);
  const [activeGroupId, setActiveGroupId] = useState<number | null>(null);
  const [market, setMarket] = useState<MarketTab>("all");
  const [sort, setSort] = useState<Sort>("custom");
  const [selectedStockId, setSelectedStockId] = useState<number | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const { toast } = useToast();
  const qc = useQueryClient();

  const { data: groups = [], isLoading } = useWatchlistGroups(isLoggedIn);
  const activeGroup: WatchlistGroup | undefined = groups.find((g) => g.id === activeGroupId) ?? groups[0];

  const stockIds = Array.from(new Set(groups.flatMap(g => g.items.map(i => i.stockId))));
  const quoteByStockId = useQuotes(stockIds, "watchlist-page", 15_000);
  // 오늘 열 — 장중 10분 간격 종가(/api/market/intraday, 50개씩 나눠 요청)
  const intradayByStockId = useIntradaySeriesChunked(stockIds, isLoggedIn);
  const chartTheme = useThemeStore((s) => CHART_THEMES[s.chartTheme]);
  const { data: events = [] } = useRecentEvents();

  const { data: alertRules = [] } = useQuery<AlertRule[]>({
    queryKey: ["alerts", "rules"],
    queryFn: async () => {
      const r = await authFetch("/api/alerts/rules");
      return r.ok ? r.json() : [];
    },
    enabled: isLoggedIn,
  });
  const stocksWithAlert = new Set(alertRules.filter(r => r.stockId != null).map(r => r.stockId));
  const stocksWithVolumeAlert = new Set(
    alertRules.filter(r => r.ruleType === "VOLUME_SURGE" && r.stockId != null).map(r => r.stockId)
  );

  const createGroup = useMutation({
    mutationFn: async (name: string) => {
      const r = await authFetch("/api/watchlists/groups", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name }),
      });
      if (!r.ok) throw new Error("그룹 생성에 실패했습니다.");
    },
    onSuccess: () => {
      setNewGroupName("");
      setShowNewGroup(false);
      qc.invalidateQueries({ queryKey: ["watchlist"] });
    },
    onError: (e: Error) => toast({ type: "error", title: "그룹 생성 실패", message: e.message }),
  });

  // 그룹 삭제 확인에 "꺼질 규칙 수"를 보인다 — 확인 창을 열 때만 읽는다.
  const { data: watchRules } = useWatchRules(isLoggedIn && confirmDelete);
  const activeRuleCount = watchRules && activeGroup
    ? watchRules.filter((r) => r.targetType === "GROUP" && r.targetGroupId === activeGroup.id && r.isActive).length
    : null;

  // DELETE /api/watchlists/groups/{id} — 항목은 함께 지워지고, 이 그룹을 대상으로 한 Watch Rule은 서버(DB 트리거)가 끈다(ADR-095).
  const deleteGroup = useMutation({
    mutationFn: async (groupId: number) => {
      const r = await authFetch(`/api/watchlists/groups/${groupId}`, { method: "DELETE" });
      if (!r.ok) throw new Error(r.status === 404 ? "이미 삭제됐거나 찾을 수 없는 그룹입니다. 목록을 새로고침합니다." : "그룹을 삭제하지 못했습니다.");
    },
    onSuccess: () => {
      setConfirmDelete(false);
      setActiveGroupId(null);
      setSelectedStockId(null);
      toast({ type: "success", title: "그룹을 삭제했습니다" });
    },
    onError: (e: Error) => toast({ type: "error", title: "그룹 삭제 실패", message: e.message }),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ["watchlist"] });
      qc.invalidateQueries({ queryKey: ["watch-rules"] });
    },
  });

  const removeItem = useMutation({
    mutationFn: async (itemId: number) => {
      const r = await authFetch(`/api/watchlists/items/${itemId}`, { method: "DELETE" });
      if (!r.ok) throw new Error("삭제에 실패했습니다.");
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["watchlist"] }),
    onError: (e: Error) => toast({ type: "error", title: "삭제 실패", message: e.message }),
  });

  // 순서 이동 — PATCH /api/watchlists/items/{id}/sort-order. 화면은 먼저 옮기고(낙관적) 실패하면 되돌린다.
  const moveItem = useMutation({
    mutationFn: async ({ itemId, target }: { groupId: number; itemId: number; target: number }) => {
      const r = await authFetch(`/api/watchlists/items/${itemId}/sort-order`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sortOrder: target }),
      });
      if (!r.ok) throw new Error(r.status === 404 ? "종목을 찾을 수 없습니다. 목록을 새로고침합니다." : "순서를 바꾸지 못했습니다.");
    },
    onMutate: async ({ groupId, itemId, target }) => {
      await qc.cancelQueries({ queryKey: ["watchlist", "groups"] });
      const prev = qc.getQueryData<WatchlistGroup[]>(["watchlist", "groups"]);
      qc.setQueryData<WatchlistGroup[]>(["watchlist", "groups"], (gs) =>
        gs?.map((g) => (g.id === groupId ? { ...g, items: applyMove(g.items, itemId, target) } : g)));
      return { prev };
    },
    onError: (e: Error, _v, ctx) => {
      if (ctx?.prev) qc.setQueryData(["watchlist", "groups"], ctx.prev);
      toast({ type: "error", title: "순서 이동 실패", message: e.message });
    },
    onSettled: () => qc.invalidateQueries({ queryKey: ["watchlist"] }),
  });

  // 개별 종목 알림(가격/거래량)은 종목 상세 페이지에서 설정하는 구조라, 여기서는 그룹
  // 전체에 한 번에 적용 가능한 유일한 조건(거래량 급증 — 종목마다 스스로의 평소 거래량
  // 대비라 그룹 공통 기준값이 필요 없음)만 일괄 등록으로 제공한다. 가격 이상/이하는
  // 종목마다 기준 가격이 달라 그룹 일괄 적용 자체가 성립하지 않는다.
  const bulkVolumeAlert = useMutation({
    mutationFn: async (group: WatchlistGroup) => {
      const targets = group.items.filter(i => !stocksWithVolumeAlert.has(i.stockId));
      let success = 0;
      let failed = 0;
      for (const item of targets) {
        const r = await authFetch("/api/alerts/rules", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ stockId: item.stockId, ruleType: "VOLUME_SURGE", condition: {} }),
        });
        if (r.ok) success++; else failed++;
      }
      return { success, failed, skipped: group.items.length - targets.length, groupName: group.name };
    },
    onSuccess: (result) => {
      qc.invalidateQueries({ queryKey: ["alerts", "rules"] });
      const parts = [`${result.success}건 설정 완료`];
      if (result.skipped > 0) parts.push(`${result.skipped}건 이미 설정됨`);
      if (result.failed > 0) parts.push(`${result.failed}건 실패(요청 한도 초과 가능)`);
      toast({
        type: result.failed > 0 ? "error" : "success",
        title: `${result.groupName} 알림 설정`,
        message: parts.join(" · "),
      });
    },
  });

  const processItems = (items: WatchlistItem[]) => {
    let filtered = items;
    if (market !== "all") {
      filtered = filtered.filter(i => {
        const q = quoteByStockId.get(i.stockId);
        if (!q) return true; // 시세를 아직 못 가져왔으면 필터로 숨기지 않는다
        return market === "domestic" ? isDomestic(q.market) : !isDomestic(q.market);
      });
    }
    if (sort === "custom") return filtered; // 서버가 준 그룹 순서 그대로
    return [...filtered].sort((a, b) => {
      if (sort === "name") return a.name.localeCompare(b.name, "ko");
      const qa = quoteByStockId.get(a.stockId);
      const qb = quoteByStockId.get(b.stockId);
      if (sort === "change") return (qb?.changeRate ?? -Infinity) - (qa?.changeRate ?? -Infinity);
      return (qb?.price ?? -Infinity) - (qa?.price ?? -Infinity);
    });
  };

  // 종목별 오늘의 최신 이벤트(최근 이벤트 50건 안에서)
  const latestEventByStock = new Map<number, RecentEvent>();
  for (const e of events) {
    if (!isTodayKst(e.eventTime)) continue;
    const prev = latestEventByStock.get(e.stockId);
    if (!prev || prev.eventTime < e.eventTime) latestEventByStock.set(e.stockId, e);
  }

  const groupItems = activeGroup?.items ?? [];
  const rows = processItems(groupItems);
  const visibleIds = rows.map((r) => r.id);
  const selected = rows.find((r) => r.stockId === selectedStockId) ?? rows[0] ?? null;
  const selectedIndex = selected ? rows.indexOf(selected) : -1;

  const quotesOfGroup = groupItems.map((i) => quoteByStockId.get(i.stockId)).filter(Boolean);
  const upCount = quotesOfGroup.filter((q) => q!.changeRate > 0).length;
  const downCount = quotesOfGroup.filter((q) => q!.changeRate < 0).length;
  const todayEvents = events.filter((e) => isTodayKst(e.eventTime) && groupItems.some((i) => i.stockId === e.stockId)).length;
  const alertOn = groupItems.filter((i) => stocksWithAlert.has(i.stockId)).length;
  const alertableCount = groupItems.filter(i => !stocksWithVolumeAlert.has(i.stockId)).length;

  const shell = (children: React.ReactNode, ready = false) => (
    <TerminalPage
      left={<TitleBlock title="관심종목" crumb="마켓" />}
      stats={ready ? [
        { label: "종목", value: String(groupItems.length) },
        { label: "상승 / 하락", value: `${upCount} / ${downCount}` },
        { label: "오늘 이벤트", value: `${todayEvents}건`, tone: "text-dracula-purple" },
        { label: "알림 켜짐", value: `${alertOn}종목` },
      ] : []}
    >
      {children}
    </TerminalPage>
  );

  if (!isLoggedIn) return shell(
    <Panel tabs={["관심종목"]} actions={[]} closable={false}>
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p className="text-13 text-tm-muted">관심종목을 이용하려면 로그인이 필요합니다.</p>
        <BtnLink href="/login">로그인</BtnLink>
      </div>
    </Panel>
  );

  if (isLoading) return shell(<div className="p-6 text-tm-muted">불러오는 중...</div>);

  const columns: Column<WatchlistItem>[] = [
    {
      key: "name",
      header: "종목",
      cell: (it) => (
        <Link href={`/stocks/${it.symbol}`} onClick={(e) => e.stopPropagation()} className="flex items-center gap-2.5 text-dracula-fg hover:text-dracula-fg">
          <span className="grid h-7 w-7 flex-none place-items-center rounded-lg bg-tm-raised text-xs font-bold text-tm-soft">{it.name.slice(0, 1)}</span>
          <span className="flex flex-col gap-px">
            <span className="font-semibold">{it.name}</span>
            <span className="num text-2xs text-tm-muted">{it.symbol}{it.memo ? ` · ${it.memo}` : ""}</span>
          </span>
        </Link>
      ),
    },
    { key: "price", header: "현재가", align: "right", cell: (it) => { const q = quoteByStockId.get(it.stockId); return <span className="num">{q ? `${isDomestic(q.market) ? "" : "$"}${fmtNum(q.price, isDomestic(q.market) ? 0 : 2)}` : "—"}</span>; } },
    { key: "rate", header: "등락률", align: "right", cell: (it) => { const r = quoteByStockId.get(it.stockId)?.changeRate ?? null; return <span className={`num ${dirClass(r)}`}>{fmtPct(r)}</span>; } },
    { key: "diff", header: "전일 대비", align: "right", cell: (it) => { const q = quoteByStockId.get(it.stockId); return <span className={`num ${dirClass(q?.changeAmount)}`}>{fmtSigned(q?.changeAmount)}</span>; } },
    {
      key: "today",
      header: "오늘",
      cell: (it) => {
        const pts = intradayByStockId.get(it.stockId);
        const r = quoteByStockId.get(it.stockId)?.changeRate ?? 0;
        return pts && pts.length > 1
          ? <Sparkline values={pts} color={r >= 0 ? chartTheme.upColor : chartTheme.downColor} width={88} height={24} />
          : <span className="text-tm-muted">—</span>;
      },
    },
    {
      key: "vol",
      header: <span title="최신 일봉 거래량 ÷ 직전 20거래일 평균">거래량 배수</span>,
      align: "right",
      cell: (it) => {
        const m = quoteByStockId.get(it.stockId)?.volumeMultiple ?? null;
        return m == null
          ? <span className="text-tm-muted">—</span>
          : <span className={`num ${m >= 2 ? "font-semibold text-dracula-purple" : ""}`}>{m.toFixed(1)}×</span>;
      },
    },
    { key: "event", header: "이벤트", cell: (it) => { const e = latestEventByStock.get(it.stockId); return e ? <span title={e.title}><EventBadge type={eventLabel(e.eventType)} /></span> : <EventBadge type={null} />; } },
    {
      key: "alert",
      header: "알림",
      align: "center",
      cell: (it) => (
        <Link
          href={`/stocks/${it.symbol}?openAlert=1`}
          onClick={(e) => e.stopPropagation()}
          aria-label={`${it.name} 알림 설정${stocksWithAlert.has(it.stockId) ? " (켜짐)" : ""}`}
          className={`inline-grid place-items-center ${stocksWithAlert.has(it.stockId) ? "text-dracula-purple" : "text-tm-muted hover:text-dracula-fg"}`}
        >
          <Icon name="bell" size={16} />
        </Link>
      ),
    },
    {
      key: "menu",
      header: <span className="sr-only">더 보기</span>,
      align: "center",
      cell: (it) => {
        const move = (dir: MoveDir) => {
          if (sort !== "custom" || !activeGroup) return undefined;
          const target = moveTarget(groupItems, visibleIds, it.id, dir);
          return target == null ? undefined : () => moveItem.mutate({ groupId: activeGroup.id, itemId: it.id, target });
        };
        return (
          <RowMenu
            name={it.name}
            onRemove={() => removeItem.mutate(it.id)}
            onMoveUp={move("up")}
            onMoveDown={move("down")}
            moveHint={sort !== "custom" ? "정렬을 '내 순서'로 바꾸면 옮길 수 있습니다" : undefined}
            disabled={removeItem.isPending || moveItem.isPending}
          />
        );
      },
    },
  ];

  return shell(
    <PanelRow>
      <Panel
        tabs={["관심종목"]}
        actions={["sliders", "download", "expand"]}
        onAction={(a) => {
          if (a !== "download" || rows.length === 0) return;
          // 화면에 보이는 순서·필터 그대로
          downloadCsv(`watchlist-${kstDateStamp()}.csv`, ["종목코드", "종목명", "현재가", "등락률(%)", "메모"],
            rows.map((i) => {
              const q = quoteByStockId.get(i.stockId);
              return [i.symbol, i.name, q?.price ?? "", q?.changeRate ?? "", i.memo ?? ""];
            }));
        }}
        className="flex-[999_1_640px]"
        bodyClassName="px-1.5 pb-1.5 pt-2.5"
        right={activeGroup && activeGroup.items.length > 0 ? (
          <Btn
            kind="ghost"
            size="sm"
            icon="bell"
            onClick={() => bulkVolumeAlert.mutate(activeGroup)}
            disabled={bulkVolumeAlert.isPending || alertableCount === 0}
            title="그룹 전체 종목에 거래량 급증 알림을 설정합니다"
          >
            {alertableCount === 0 ? "전체 알림 설정됨" : `그룹 알림 설정 (${alertableCount})`}
          </Btn>
        ) : undefined}
      >
        <div className="flex flex-wrap items-center gap-1.5 px-2">
          {groups.length > 0 && (
            <Seg
              options={groups.map((g) => ({ value: String(g.id), label: `${g.name} ${g.items.length}` }))}
              value={String(activeGroup?.id ?? "")}
              onChange={(v) => { setActiveGroupId(Number(v)); setSelectedStockId(null); setConfirmDelete(false); }}
            />
          )}
          <IconBtn name="plus" label="그룹 추가" size={34} aria-expanded={showNewGroup || groups.length === 0} onClick={() => setShowNewGroup((v) => !v)} />
          {activeGroup && (
            <IconBtn name="trash" label="그룹 삭제" size={34} aria-expanded={confirmDelete} onClick={() => setConfirmDelete((v) => !v)} />
          )}
          <div className="ml-auto flex flex-wrap items-center gap-1.5">
            <Seg options={MARKET_TABS} value={market} onChange={setMarket} />
            <SelectBox aria-label="정렬 기준" value={sort} onChange={(e) => setSort(e.target.value as Sort)} className="min-h-[34px] w-[120px] py-1">
              {SORT_OPTIONS.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
            </SelectBox>
          </div>
        </div>

        {confirmDelete && activeGroup && (
          <GroupDeleteConfirm
            groupName={activeGroup.name}
            itemCount={activeGroup.items.length}
            activeRuleCount={activeRuleCount}
            pending={deleteGroup.isPending}
            onConfirm={() => deleteGroup.mutate(activeGroup.id)}
            onCancel={() => setConfirmDelete(false)}
          />
        )}

        {(showNewGroup || groups.length === 0) && (
          <form
            onSubmit={e => { e.preventDefault(); if (newGroupName.trim()) createGroup.mutate(newGroupName.trim()); }}
            className="flex gap-2 px-2"
          >
            <input
              type="text"
              value={newGroupName}
              onChange={(e) => setNewGroupName(e.target.value)}
              aria-label="새 관심종목 그룹 이름"
              placeholder="새 그룹 이름"
              className="h-10 min-w-0 flex-1 rounded-lg border border-tm-line bg-tm-inner px-3 text-sm text-dracula-fg outline-none placeholder:text-[#8b92b8] focus:border-dracula-purple"
            />
            <Btn type="submit" disabled={createGroup.isPending}>그룹 추가</Btn>
          </form>
        )}

        {groups.length === 0 ? (
          <p className="py-8 text-center text-tm-muted">관심종목 그룹이 없습니다.</p>
        ) : groupItems.length === 0 ? (
          <p className="py-8 text-center text-13 text-tm-muted">종목이 없습니다.</p>
        ) : rows.length === 0 ? (
          <p className="py-8 text-center text-13 text-tm-muted">선택한 시장에 해당하는 종목이 없습니다.</p>
        ) : (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(it) => it.id}
            minWidth={860}
            selectedIndex={selectedIndex}
            onRowClick={(it) => setSelectedStockId(it.stockId)}
          />
        )}
      </Panel>
      <SelectedStockPanel item={selected} quote={selected ? quoteByStockId.get(selected.stockId) : undefined} />
    </PanelRow>,
    true,
  );
}
