"use client";

import { useState } from "react";
import Link from "next/link";
import NewRuleForm from "@/components/alerts/NewRuleForm";
import DeliveryChannelsPanel from "@/components/alerts/DeliveryChannelsPanel";
import { channelsSummary, useDeliveryChannels } from "@/components/alerts/channels";
import {
  Btn, BtnLink, Notice, Panel, PanelRow, Seg, TerminalPage, TitleBlock, Toggle,
} from "@/components/terminal";
import { useIsLoggedIn, useQuotes } from "@/components/home/data";
import {
  describeRule, matchesFilter, ruleMeta, useAlertHistory, useAlertMutations, useAlertRules, useAlertStats,
} from "@/components/alerts/data";

const FILTERS = [
  { value: "all", label: "전체" },
  { value: "unread", label: "읽지 않음" },
  { value: "price", label: "가격" },
  { value: "event", label: "이벤트" },
  { value: "signal", label: "시그널" },
  { value: "account", label: "계좌" },
] as const;
type Filter = (typeof FILTERS)[number]["value"];

const STATUS_LABEL: Record<string, string> = {
  SENT: "발송됨", FAILED: "발송 실패", PENDING: "발송 대기", EMAIL_FALLBACK: "이메일로 발송",
  SUPPRESSED: "설정으로 보내지 않음", QUIET_HOURS: "방해 금지 시간 — 푸시 안 함",
  // ADR-090 — 시그널 이력: 푸시·이메일은 알림 설정에 따라 따로 나간다(결과를 이 행에 되돌려 쓰지 않는다)
  QUEUED: "알림 설정대로 전달",
};

function kstDate(d: Date) {
  return d.toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" }); // YYYY-MM-DD
}

function whenLabel(iso: string) {
  const d = new Date(iso);
  if (kstDate(d) === kstDate(new Date())) {
    return d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "Asia/Seoul" });
  }
  return d.toLocaleDateString("ko-KR", { month: "2-digit", day: "2-digit", timeZone: "Asia/Seoul" }).replace(/\s/g, "").replace(/\.$/, "");
}

export default function AlertsPage() {
  const isLoggedIn = useIsLoggedIn();
  const [filter, setFilter] = useState<Filter>("all");
  const [showNewRule, setShowNewRule] = useState(false);

  const { data: alerts = [], isLoading: loadingHistory, dataUpdatedAt: historyFetchedAt } = useAlertHistory(isLoggedIn);
  const { data: rules = [], isLoading: loadingRules } = useAlertRules(isLoggedIn);
  const { data: stats } = useAlertStats(isLoggedIn);
  const { data: channels, isLoading: loadingChannels } = useDeliveryChannels(isLoggedIn);
  const { toggleRule, markRead, markAllRead } = useAlertMutations();
  const unreadCount = stats?.unread ?? null;
  const actionError = (toggleRule.error ?? markAllRead.error) as Error | null;

  const stockIds = Array.from(new Set([...alerts, ...rules].map((a) => a.stockId).filter((v): v is number => v != null)));
  const quotes = useQuotes(stockIds, "alerts");
  const stockName = (id: number | null) => (id == null ? "관심종목 전체" : quotes.get(id)?.name ?? `종목 #${id}`);

  const shown = alerts.filter((a) => matchesFilter(a, filter));

  const todayKey = kstDate(new Date());
  const todayCount = stats?.recentFires.find((d) => d.date === todayKey)?.count ?? (stats ? 0 : null);

  const page = (children: React.ReactNode) => (
    <TerminalPage
      left={<TitleBlock title="알림" crumb="계정" />}
      stats={isLoggedIn ? [
        { label: "오늘", value: todayCount == null ? "—" : `${todayCount}건` },
        { label: "읽지 않음", value: unreadCount == null ? "—" : `${unreadCount}건`, tone: "text-dracula-pink" },
        { label: "활성 규칙", value: `${stats?.activeRules ?? rules.length}개` },
        { label: "전달 채널", value: loadingChannels ? "—" : channelsSummary(channels) },
      ] : []}
    >
      {children}
    </TerminalPage>
  );

  if (!isLoggedIn) return page(
    <Panel tabs={["알림 이력"]} actions={[]} closable={false}>
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p className="text-13 text-tm-muted">알림을 보려면 로그인이 필요합니다.</p>
        <BtnLink href="/login">로그인</BtnLink>
      </div>
    </Panel>
  );

  return page(
    <PanelRow>
      {/* ── 알림 이력 ─────────────────────────────────────── */}
      <Panel tabs={["알림 이력"]} actions={["sliders"]} closable={false} className="flex-[999_1_600px]" bodyClassName="gap-0 p-0">
        <div className="flex flex-wrap items-center gap-2 border-b border-tm-line px-3.5 py-2.5">
          <Seg size="sm" options={FILTERS} value={filter} onChange={setFilter} />
          <span className="ml-auto flex items-center gap-1.5">
            <button
              type="button"
              disabled={!unreadCount || markAllRead.isPending}
              onClick={() => markAllRead.mutate(new Date(historyFetchedAt || Date.now()))}
              className="text-xs text-dracula-purple hover:underline disabled:opacity-50 disabled:no-underline"
              title="지금 보이는 시점까지 받은 알림을 모두 읽음으로 표시합니다"
            >
              모두 읽음
            </button>
          </span>
        </div>
        {actionError && <Notice tone="warn" className="m-3.5 mb-0">{actionError.message}</Notice>}
        {loadingHistory ? (
          <div className="m-3.5 h-32 animate-shimmer rounded-lg bg-gradient-to-r from-tm-inner via-tm-raised to-tm-inner bg-[length:200%_100%]" />
        ) : shown.length === 0 ? (
          <div className="flex flex-col items-center gap-1.5 px-4 py-16 text-center">
            <span className="text-sm font-semibold text-tm-soft">알림 이력이 없습니다</span>
            <span className="text-xs text-tm-muted">
              {filter === "signal" ? "내 전략·구독 전략에서 포워드 테스트 신호가 나면 여기 쌓입니다." : filter === "unread" ? "최근 알림을 모두 읽었습니다." : "종목 상세 페이지에서 가격 알림을 설정해보세요."}
            </span>
          </div>
        ) : (
          <ul className="m-0 list-none p-0">
            {shown.map((a) => {
              const m = ruleMeta(a.ruleType);
              const failed = a.deliveryStatus === "FAILED";
              const unread = !a.readAt;
              return (
                <li
                  key={a.id}
                  className={`flex gap-3 border-b border-tm-line px-3.5 py-3 ${unread ? "cursor-pointer bg-tm-raised/40" : ""}`}
                  onClick={unread ? () => markRead.mutate(a.id) : undefined}
                >
                  <span
                    className="num grid h-8 w-8 flex-none place-items-center rounded-full border-[1.5px] text-xs font-bold"
                    style={{ borderColor: m.color, color: m.color }}
                    aria-hidden
                  >
                    {m.letter}
                  </span>
                  <div className="flex min-w-0 flex-1 flex-col gap-[3px]">
                    <span className="flex items-center gap-1.5 text-sm font-semibold">
                      {unread && <span className="h-[7px] w-[7px] rounded-full bg-dracula-pink" aria-label="읽지 않음" />}
                      {stockName(a.stockId)} {m.tag}
                    </span>
                    <span className="text-13 text-tm-soft">{a.message}</span>
                    <span className="text-2xs text-tm-muted">
                      {m.tag} · <span className={failed ? "text-[#ff8a8a]" : undefined}>{STATUS_LABEL[a.deliveryStatus] ?? a.deliveryStatus}</span>
                    </span>
                  </div>
                  <span className="flex flex-col items-end gap-1">
                    <span className="num text-xs text-tm-muted">{whenLabel(a.triggeredAt)}</span>
                    {unread && (
                      <button
                        type="button"
                        className="text-2xs text-dracula-purple hover:underline"
                        onClick={(e) => { e.stopPropagation(); markRead.mutate(a.id); }}
                      >
                        읽음
                      </button>
                    )}
                  </span>
                </li>
              );
            })}
          </ul>
        )}
      </Panel>

      {/* ── 알림 규칙 ─────────────────────────────────────── */}
      <Panel tabs={["알림 규칙"]} actions={[]} closable={false} className="flex-[1_1_320px]">
        {loadingRules ? (
          <div className="h-24 animate-pulse rounded-lg bg-tm-inner" />
        ) : rules.length === 0 ? (
          <p className="py-6 text-center text-13 text-tm-muted">알림 규칙이 없습니다.</p>
        ) : (
          <ul className="m-0 list-none p-0">
            {rules.map((r) => (
              <li key={r.id} className="flex items-center gap-3 border-b border-tm-line py-[11px]">
                <div className={`flex flex-1 flex-col gap-0.5 ${r.isActive ? "" : "opacity-60"}`}>
                  <span className="font-semibold">{stockName(r.stockId)}</span>
                  <span className="text-xs text-tm-muted">{describeRule(r)}{r.isActive ? "" : " · 꺼짐"}</span>
                </div>
                <Toggle
                  checked={r.isActive}
                  label={`${stockName(r.stockId)} 알림 ${r.isActive ? "끄기" : "켜기"}`}
                  disabled={toggleRule.isPending && toggleRule.variables?.id === r.id}
                  onChange={(v) => toggleRule.mutate({ id: r.id, isActive: v })}
                />
              </li>
            ))}
          </ul>
        )}
        <Btn kind={showNewRule ? "ghost" : "primary"} icon={showNewRule ? undefined : "plus"} full aria-expanded={showNewRule} onClick={() => setShowNewRule((v) => !v)}>
          {showNewRule ? "닫기" : "새 알림 규칙"}
        </Btn>
        {showNewRule && <NewRuleForm onDone={() => setShowNewRule(false)} />}
        <Link href="/settings/notifications" className="text-xs text-dracula-purple hover:underline">채널·방해 금지 시간 설정 →</Link>
      </Panel>

      {/* ── 전달 채널(ADR-093) ─────────────────────────────── */}
      <DeliveryChannelsPanel data={channels} loading={loadingChannels} />
    </PanelRow>
  );
}
