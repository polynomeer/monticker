"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Bell, X } from "@phosphor-icons/react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";

interface WatchlistItem { id: number; stockId: number; symbol: string; name: string; memo: string | null; }
interface WatchlistGroup { id: number; name: string; sortOrder: number; items: WatchlistItem[]; }
interface QuoteItem { stockId: number; symbol: string; market: string; price: number; changeRate: number; volume: number; }
interface AlertRule { id: number; stockId: number | null; ruleType: string; }

const SESSION_TABS = [
  { key: "all",      label: "전체" },
  { key: "domestic", label: "국내" },
  { key: "overseas", label: "해외" },
] as const;
type Session = (typeof SESSION_TABS)[number]["key"];

const SORT_OPTIONS = [
  { key: "name",   label: "이름순" },
  { key: "change", label: "등락률순" },
  { key: "price",  label: "현재가순" },
] as const;
type Sort = (typeof SORT_OPTIONS)[number]["key"];

const COLUMN_SETS = [
  { key: "basic",    label: "기본" },
  { key: "detailed", label: "시세" },
] as const;
type ColumnSet = (typeof COLUMN_SETS)[number]["key"];

function isDomestic(market: string) { return market === "KOSPI" || market === "KOSDAQ"; }
function fmt(n: number) { return n.toLocaleString("ko-KR", { maximumFractionDigits: 0 }); }

function Pill({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={`px-2.5 py-1 rounded-full text-xs font-medium whitespace-nowrap transition-all duration-200
        ${active
          ? "bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg"
          : "bg-gray-100 dark:bg-dracula-line/50 text-gray-500 dark:text-dracula-comment hover:bg-gray-200 dark:hover:bg-dracula-line"
        }`}
    >
      {children}
    </button>
  );
}

export default function WatchlistPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const [newGroupName, setNewGroupName] = useState("");
  const [session, setSession] = useState<Session>("all");
  const [sort, setSort] = useState<Sort>("name");
  const [columnSet, setColumnSet] = useState<ColumnSet>("basic");
  const { toast } = useToast();
  const qc = useQueryClient();

  const { data: groups = [], isLoading } = useQuery<WatchlistGroup[]>({
    queryKey: ["watchlist", "groups", "page"],
    queryFn: async () => {
      const r = await authFetch("/api/watchlists");
      return r.ok ? r.json() : [];
    },
    enabled: isLoggedIn,
  });

  const stockIds = Array.from(new Set(groups.flatMap(g => g.items.map(i => i.stockId))));

  const { data: quotesData } = useQuery<{ items: QuoteItem[] }>({
    queryKey: ["screener", "quotes", "watchlist-page", stockIds],
    queryFn: async () => {
      const r = await fetch(`/api/screener/quotes?ids=${stockIds.join(",")}`);
      return r.ok ? r.json() : { items: [] };
    },
    enabled: stockIds.length > 0,
    refetchInterval: 15_000,
    staleTime: 15_000,
  });
  const quoteByStockId = Object.fromEntries((quotesData?.items ?? []).map(q => [q.stockId, q]));

  const { data: alertRules = [] } = useQuery<AlertRule[]>({
    queryKey: ["alerts", "rules"],
    queryFn: async () => {
      const r = await authFetch("/api/alerts/rules");
      return r.ok ? r.json() : [];
    },
    enabled: isLoggedIn,
  });
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
      qc.invalidateQueries({ queryKey: ["watchlist"] });
    },
    onError: (e: Error) => toast({ type: "error", title: "그룹 생성 실패", message: e.message }),
  });

  const removeItem = useMutation({
    mutationFn: async (itemId: number) => {
      const r = await authFetch(`/api/watchlists/items/${itemId}`, { method: "DELETE" });
      if (!r.ok) throw new Error("삭제에 실패했습니다.");
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["watchlist"] }),
    onError: (e: Error) => toast({ type: "error", title: "삭제 실패", message: e.message }),
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
    if (session !== "all") {
      filtered = filtered.filter(i => {
        const q = quoteByStockId[i.stockId];
        if (!q) return true; // 시세를 아직 못 가져왔으면 필터로 숨기지 않는다
        return session === "domestic" ? isDomestic(q.market) : !isDomestic(q.market);
      });
    }
    return [...filtered].sort((a, b) => {
      if (sort === "name") return a.name.localeCompare(b.name, "ko");
      const qa = quoteByStockId[a.stockId];
      const qb = quoteByStockId[b.stockId];
      if (sort === "change") return (qb?.changeRate ?? -Infinity) - (qa?.changeRate ?? -Infinity);
      return (qb?.price ?? -Infinity) - (qa?.price ?? -Infinity);
    });
  };

  if (!isLoggedIn) return (
    <div className="max-w-2xl mx-auto p-6 text-center py-20">
      <p className="text-gray-500 dark:text-dracula-comment mb-4">관심종목을 이용하려면 로그인이 필요합니다.</p>
      <Link href="/login" className="inline-block bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg text-white px-6 py-2 rounded-lg font-medium hover:opacity-90 active:scale-[0.98] transition-all duration-150">로그인</Link>
    </div>
  );

  if (isLoading) return <div className="p-6 text-gray-500 dark:text-dracula-comment">불러오는 중...</div>;

  return (
    <div className="max-w-2xl mx-auto p-4 sm:p-6 animate-fade-up">
      <h1 className="text-2xl font-bold tracking-tight mb-6 text-gray-900 dark:text-dracula-fg">관심종목</h1>

      <form
        onSubmit={e => { e.preventDefault(); if (newGroupName.trim()) createGroup.mutate(newGroupName.trim()); }}
        className="flex gap-2 mb-6"
      >
        <input
          type="text"
          value={newGroupName}
          onChange={(e) => setNewGroupName(e.target.value)}
          aria-label="새 관심종목 그룹 이름"
          placeholder="새 그룹 이름"
          className="flex-1 border border-gray-300 rounded-lg px-4 py-2 transition-colors hover:border-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500/50 dark:bg-dracula-bg dark:border-dracula-line dark:text-dracula-fg dark:placeholder-dracula-comment dark:hover:border-dracula-comment dark:focus:ring-dracula-purple/50"
        />
        <button
          type="submit"
          disabled={createGroup.isPending}
          className="bg-blue-600 text-white px-6 py-2 rounded-lg font-medium hover:bg-blue-700 active:scale-[0.98] transition-all duration-150 dark:bg-dracula-purple dark:text-dracula-bg dark:hover:opacity-90 disabled:opacity-50"
        >
          그룹 추가
        </button>
      </form>

      {groups.length > 0 && (
        <div className="flex flex-wrap items-center justify-between gap-2 mb-6">
          <div className="flex items-center gap-1">
            {SESSION_TABS.map(t => (
              <Pill key={t.key} active={session === t.key} onClick={() => setSession(t.key)}>{t.label}</Pill>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <select
              value={sort}
              onChange={e => setSort(e.target.value as Sort)}
              aria-label="정렬 기준"
              className="text-xs rounded-lg border border-gray-300 dark:border-dracula-line bg-white dark:bg-dracula-surface px-2 py-1 text-gray-700 dark:text-dracula-fg focus:outline-none focus:ring-2 focus:ring-dracula-purple/50"
            >
              {SORT_OPTIONS.map(o => <option key={o.key} value={o.key}>{o.label}</option>)}
            </select>
            <div className="inline-flex gap-1 p-0.5 rounded-lg bg-gray-100 dark:bg-dracula-line/30">
              {COLUMN_SETS.map(c => (
                <button key={c.key} onClick={() => setColumnSet(c.key)}
                  className={`px-2 py-1 rounded-md text-[11px] font-medium transition-all duration-200
                    ${columnSet === c.key ? "bg-white dark:bg-dracula-bg text-gray-900 dark:text-dracula-fg shadow-sm" : "text-gray-500 dark:text-dracula-comment"}`}>
                  {c.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {groups.length === 0 ? (
        <p className="text-gray-500 dark:text-dracula-comment text-center py-8">관심종목 그룹이 없습니다.</p>
      ) : (
        <div className="space-y-6">
          {groups.map((group) => {
            const items = processItems(group.items);
            const alertableCount = group.items.filter(i => !stocksWithVolumeAlert.has(i.stockId)).length;
            return (
              <Card key={group.id} className="p-4" hover>
                <div className="flex items-center justify-between mb-3">
                  <h2 className="font-semibold text-lg text-gray-900 dark:text-dracula-fg">{group.name}</h2>
                  {group.items.length > 0 && (
                    <button
                      onClick={() => bulkVolumeAlert.mutate(group)}
                      disabled={bulkVolumeAlert.isPending || alertableCount === 0}
                      title="그룹 전체 종목에 거래량 급증 알림을 설정합니다"
                      className="flex items-center gap-1 text-[11px] px-2 py-1 rounded-lg border border-gray-300 dark:border-dracula-line text-gray-600 dark:text-dracula-comment hover:bg-gray-50 dark:hover:bg-dracula-line/30 transition-colors disabled:opacity-40"
                    >
                      <Bell size={12} weight="bold" aria-hidden />
                      {alertableCount === 0 ? "전체 알림 설정됨" : `그룹 알림 설정 (${alertableCount})`}
                    </button>
                  )}
                </div>
                {group.items.length === 0 ? (
                  <p className="text-gray-400 dark:text-dracula-comment text-sm">종목이 없습니다.</p>
                ) : items.length === 0 ? (
                  <p className="text-gray-400 dark:text-dracula-comment text-sm">선택한 거래세션에 해당하는 종목이 없습니다.</p>
                ) : (
                  <ul className="space-y-1">
                    {items.map((item) => {
                      const q = quoteByStockId[item.stockId];
                      const up = q ? q.changeRate >= 0 : false;
                      return (
                        <li key={item.id} className="group flex items-center justify-between py-2 border-b border-gray-100 dark:border-dracula-line last:border-0">
                          <Link href={`/stocks/${item.symbol}`} className="min-w-0 flex-1 hover:opacity-80 transition-opacity">
                            <div className="flex items-baseline gap-2">
                              <span className="font-medium dark:text-dracula-fg truncate">{item.name}</span>
                              <span className="text-sm text-gray-500 dark:text-dracula-comment shrink-0">{item.symbol}</span>
                            </div>
                            {item.memo && <p className="text-xs text-gray-400 dark:text-dracula-comment mt-0.5">{item.memo}</p>}
                          </Link>
                          <div className="flex items-center gap-3 shrink-0">
                            {columnSet === "detailed" && q && (
                              <div className="text-right">
                                <p className="text-sm font-mono font-semibold dark:text-dracula-fg">
                                  {isDomestic(q.market) ? "₩" : "$"}{fmt(q.price)}
                                </p>
                                <p className={`text-xs font-mono ${up ? "text-market-up" : "text-market-down"}`}>
                                  {up ? "+" : ""}{q.changeRate.toFixed(2)}%
                                </p>
                              </div>
                            )}
                            <button
                              onClick={() => removeItem.mutate(item.id)}
                              aria-label={`${item.name} 관심종목에서 제거`}
                              className="opacity-0 group-hover:opacity-100 focus:opacity-100 p-1 rounded text-gray-400 dark:text-dracula-comment hover:text-dracula-red hover:bg-dracula-red/10 transition-all duration-150"
                            >
                              <X size={14} weight="bold" aria-hidden />
                            </button>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
