"use client";

import { useEffect, useMemo, useState } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { emotionLabel } from "@/components/wallet/emotions";
import { OriginBadge, PLANNED_DEFINITION } from "@/components/wallet/origin";
import { EmptyNote, LoginRequired, Skeleton } from "@/components/portfolio/PaperStates";
import { fmtTime } from "@/components/portfolio/format";
import {
  AutoGrid, Chip, Icon, IconBtn, Panel, PanelCol, PanelRow, Pill, Stat, TerminalPage, dirClass, fmtNum, fmtSigned,
} from "@/components/terminal";
import { cn } from "@/lib/utils";
import CandleReplay from "@/components/wallet/CandleReplay";
import { addDays, holidaySet, previousBusinessDays, shiftBusinessDays, useMarketCalendar } from "@/components/market/krxCalendar";

/** 백엔드 ReplayEvent — 필드 이름이 qty, 종목명은 없고 심볼만 온다. 예전 응답 모양(quantity·stockName)도 받아 준다. */
interface ReplayEvent {
  time: string;
  type: string;
  stockSymbol: string | null;
  stockId?: number | null;
  stockName?: string | null;
  qty?: number | null;
  quantity?: number | null;
  price: number | null;
  amount?: number | null;
  pnlPct: number | null;
  description?: string | null;
  /** 체결 거래 id — 입출금 행은 null */
  tradeId?: number | null;
  /** 감정 태그(EmotionType)와 메모 */
  emotion?: string | null;
  memo?: string | null;
  /** ADR-085 진입 출처 */
  origin?: string | null;
  originRef?: number | null;
  /** ADR-085 계획된 주문 여부. null = 거래가 아니거나 판정 불가 */
  planned?: boolean | null;
}

interface DailyReplay {
  date: string;
  events: ReplayEvent[];
  summary: {
    totalPnl: number;
    tradeCount: number;
    /** 백엔드는 ReplayEvent 객체를 준다 */
    bestTrade: ReplayEvent | string | null;
    worstTrade: ReplayEvent | string | null;
    /** ADR-085 — 계획된 주문 ÷ 판정 가능한 주문 × 100. 판정 가능한 주문이 없으면 null */
    planAdherencePct?: number | null;
    unplannedCount?: number;
    planEvaluatedCount?: number;
  };
}

interface EmotionStat { emotion: string; count: number; avgReturnPct: number | null; }

const TYPE_LABEL: Record<string, string> = { BUY: "매수", SELL: "매도", DEPOSIT: "입금", WITHDRAWAL: "출금", FEE: "수수료" };
const WEEKDAY = ["일", "월", "화", "수", "목", "금", "토"];
const EMO_COLORS = ["#50fa7b", "#bd93f9", "#ff79c6", "#ffb86c", "#8be9fd", "#f1fa8c"];

/** 로컬 날짜 YYYY-MM-DD (toISOString은 UTC라 KST 오전에 하루 전 날짜가 된다) */
function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
function parseYmd(s: string) {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(y, m - 1, d);
}
/** +44,900 → "+4.5만" */
function compactWon(v: number) {
  if (Math.abs(v) < 10_000) return fmtSigned(v);
  return `${v > 0 ? "+" : "-"}${(Math.abs(v) / 10_000).toFixed(1)}만`;
}
function tradeName(t: ReplayEvent | string | null | undefined) {
  if (!t) return null;
  if (typeof t === "string") return t;
  return t.stockName ?? t.stockSymbol ?? null;
}

async function fetchReplay(date: string): Promise<DailyReplay> {
  const res = await authFetch(`/api/wallet/replay?date=${date}`);
  if (!res.ok) throw new Error("리플레이 조회 실패");
  return res.json();
}

/** 장중(09:00–15:30) 시간축 위에 그날 주문을 찍는다 — 캔들 재생은 CandleReplay */
function DayTimeline({ events }: { events: ReplayEvent[] }) {
  const trades = events.filter((e) => e.type === "BUY" || e.type === "SELL");
  const open = 9 * 60;
  const close = 15 * 60 + 30;
  const x = (iso: string) => {
    const d = new Date(iso);
    const m = d.getHours() * 60 + d.getMinutes();
    return Math.min(100, Math.max(0, ((m - open) / (close - open)) * 100));
  };
  return (
    <div className="flex flex-col gap-2 rounded-lg bg-tm-inner p-3">
      <div className="relative h-[200px]" role="img" aria-label={`장중 주문 ${trades.length}건의 시각 분포`}>
        {[0, 25, 50, 75, 100].map((p) => (
          <div key={p} className="absolute bottom-6 top-0 border-l border-dashed border-tm-line" style={{ left: `${p}%` }} />
        ))}
        <div className="absolute bottom-6 left-0 right-0 h-px bg-tm-line2" />
        {trades.map((e, i) => {
          const buy = e.type === "BUY";
          return (
            <span
              key={i}
              title={`${fmtTime(e.time, false)} ${e.stockName ?? e.stockSymbol ?? ""} ${TYPE_LABEL[e.type]}`}
              className={cn(
                "absolute grid h-[18px] w-[18px] -translate-x-1/2 place-items-center rounded text-2xs font-bold text-tm-page",
                buy ? "bg-up" : "bg-down",
              )}
              style={{ left: `${x(e.time)}%`, bottom: `${32 + (i % 6) * 26}px` }}
            >
              {buy ? "B" : "S"}
            </span>
          );
        })}
        {trades.length === 0 && <span className="absolute inset-x-0 top-1/2 -translate-y-1/2 text-center text-13 text-tm-muted">이날 장중 주문이 없습니다.</span>}
        <div className="num absolute bottom-0 left-0 right-0 flex justify-between text-2xs text-tm-muted">
          {["09:00", "10:37", "12:15", "13:52", "15:30"].map((t) => <span key={t}>{t}</span>)}
        </div>
      </div>
    </div>
  );
}

export default function ReplayPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [date, setDate] = useState(() => ymd(new Date()));
  const [anchor, setAnchor] = useState(date);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data, isLoading, error } = useQuery<DailyReplay>({
    queryKey: ["wallet", "replay", date],
    queryFn: () => fetchReplay(date),
    enabled: isLoggedIn,
  });

  // ADR-086 — 날짜 띠는 KRX 영업일만(휴장일 캘린더). 캘린더를 못 받으면 주말만 뺀다.
  const { data: cal } = useMarketCalendar(addDays(anchor, -45), addDays(anchor, 15));
  const holidays = useMemo(() => holidaySet(cal), [cal]);
  const days = useMemo(() => previousBusinessDays(6, anchor, holidays).map(parseYmd), [anchor, holidays]);
  const dayQueries = useQueries({
    queries: days.map((d) => ({ queryKey: ["wallet", "replay", ymd(d)], queryFn: () => fetchReplay(ymd(d)), enabled: isLoggedIn, staleTime: 60_000 })),
  });

  const { data: emotions } = useQuery<{ stats: EmotionStat[] }>({
    queryKey: ["wallet", "emotion-analysis"],
    queryFn: async () => {
      const r = await authFetch("/api/wallet/emotion-analysis");
      if (!r.ok) throw new Error("감정 분석 조회 실패");
      return r.json();
    },
    enabled: isLoggedIn,
  });

  const sel = parseYmd(date);
  const selLabel = `${sel.getFullYear()}.${String(sel.getMonth() + 1).padStart(2, "0")}.${String(sel.getDate()).padStart(2, "0")} (${WEEKDAY[sel.getDay()]})`;
  const title = { title: "주문 리플레이", crumb: "지갑 · 하루 투자 복기" };

  if (!isLoggedIn) {
    return (
      <TerminalPage {...title}>
        <LoginRequired message="주문 리플레이를 보려면 로그인이 필요합니다." icon="play" />
      </TerminalPage>
    );
  }

  const pnl = data?.summary.totalPnl ?? null;
  const best = tradeName(data?.summary.bestTrade);
  const bestPnl = data && typeof data.summary.bestTrade === "object" && data.summary.bestTrade ? data.summary.bestTrade.pnlPct : null;
  const emoStats = (emotions?.stats ?? []).filter((s) => s.count > 0).sort((a, b) => b.count - a.count);
  const emoTotal = emoStats.reduce((a, s) => a + s.count, 0);
  const adherence = data?.summary.planAdherencePct ?? null;
  const evaluated = data?.summary.planEvaluatedCount ?? 0;

  return (
    <TerminalPage
      {...title}
      stats={[
        { label: "선택일", value: selLabel },
        { label: "총 손익", value: pnl == null ? "—" : fmtSigned(pnl), tone: dirClass(pnl) },
        { label: "거래", value: data ? `${data.summary.tradeCount}회` : "—" },
        {
          label: "계획 준수율",
          value: adherence == null ? "—" : `${adherence.toFixed(0)}%`,
          tone: adherence == null ? "text-tm-muted" : undefined,
          hint: PLANNED_DEFINITION,
        },
      ]}
    >
      <Panel tabs={["날짜 선택"]} actions={[]} closable={false}>
        <div className="flex flex-wrap items-center gap-1.5">
          <IconBtn name="chevl" label="이전 주" size={36} onClick={() => setAnchor((a) => shiftBusinessDays(a, -5, holidays))} />
          {days.map((d, i) => {
            const key = ymd(d);
            const on = key === date;
            const v = dayQueries[i]?.data?.summary.totalPnl;
            return (
              <button
                key={key}
                type="button"
                aria-pressed={on}
                aria-label={`${d.getMonth() + 1}월 ${d.getDate()}일 복기`}
                onClick={() => setDate(key)}
                className={cn("flex w-[58px] flex-col items-center gap-0.5 rounded-lg py-2", on ? "bg-dracula-purple text-tm-page" : "bg-tm-inner text-dracula-fg hover:bg-tm-raised")}
              >
                <span className="text-2xs opacity-80">{WEEKDAY[d.getDay()]}</span>
                <span className="num text-13 font-semibold">{`${String(d.getMonth() + 1).padStart(2, "0")}.${String(d.getDate()).padStart(2, "0")}`}</span>
                <span className={cn("num text-[0.625rem]", !on && dirClass(v))}>{v == null ? "—" : v === 0 ? "0" : compactWon(v)}</span>
              </button>
            );
          })}
          <IconBtn name="chevr" label="다음 주" size={36} onClick={() => setAnchor((a) => shiftBusinessDays(a, 5, holidays))} />
          <label className="ml-auto inline-flex h-9 cursor-pointer items-center gap-2 rounded-lg border border-tm-line2 px-3 text-sm font-semibold hover:bg-tm-raised">
            <Icon name="calendar" size={16} />
            <span>달력</span>
            <input
              type="date"
              aria-label="복기할 날짜"
              value={date}
              max={ymd(new Date())}
              onChange={(e) => { if (e.target.value) { setDate(e.target.value); setAnchor(e.target.value); } }}
              className="w-[118px] bg-transparent text-xs text-tm-muted outline-none [color-scheme:dark]"
            />
          </label>
        </div>
      </Panel>

      <PanelRow>
        <Panel tabs={["리플레이"]} actions={["expand"]} className="flex-[999_1_620px]">
          {isLoading ? <Skeleton className="h-[230px]" /> : (
            <>
              {/* 그날 거래한 종목의 1분봉을 재생하며 내 주문이 그 시점에 나타난다. 거래가 없는 날은 시간축만 */}
              <CandleReplay date={date} events={data?.events ?? []} />
              <DayTimeline events={data?.events ?? []} />
            </>
          )}
        </Panel>

        <PanelCol className="flex-[1_1_320px]">
          <Panel tabs={["오늘 요약"]} actions={[]}>
            {isLoading ? (
              <Skeleton className="h-24" />
            ) : error ? (
              <p role="alert" className="m-0 text-13 text-[#ff8a8a]">데이터를 불러올 수 없습니다.</p>
            ) : (
              <AutoGrid min={120}>
                <Stat big label="총 손익" value={pnl == null ? "—" : fmtSigned(pnl)} valueClassName={dirClass(pnl)} />
                <Stat big label="거래 횟수" value={data ? `${data.summary.tradeCount}회` : "—"} />
                <Stat label="최고 거래" value={best ?? "—"} sub={bestPnl != null ? `${bestPnl > 0 ? "+" : ""}${bestPnl.toFixed(2)}%` : undefined} />
                <span title={PLANNED_DEFINITION}>
                  <Stat
                    label="계획 외 주문"
                    value={data && evaluated > 0 ? `${data.summary.unplannedCount ?? 0}건` : "—"}
                    sub={data && evaluated > 0 ? `판정 ${evaluated}건 중` : undefined}
                    valueClassName={data && evaluated > 0 ? undefined : "text-tm-muted"}
                  />
                </span>
              </AutoGrid>
            )}
          </Panel>
          <Panel tabs={["감정 분포"]} actions={[]} right={<span className="text-2xs text-tm-muted">전체 기간</span>}>
            {emoStats.length === 0 ? (
              <EmptyNote className="py-4">아직 감정 태그를 남긴 거래가 없습니다.</EmptyNote>
            ) : (
              emoStats.map((s, i) => {
                const p = (s.count / emoTotal) * 100;
                return (
                  <div key={s.emotion} className="flex flex-col gap-[5px]">
                    <div className="flex justify-between text-xs">
                      <span>{emotionLabel(s.emotion)}</span>
                      <span className="num text-tm-muted">
                        {p.toFixed(0)}%{s.avgReturnPct != null && ` · 평균 ${s.avgReturnPct > 0 ? "+" : ""}${s.avgReturnPct.toFixed(1)}%`}
                      </span>
                    </div>
                    <div className="h-1.5 overflow-hidden rounded-full bg-tm-inner">
                      <div className="h-full rounded-full" style={{ width: `${Math.min(100, p * 2)}%`, background: EMO_COLORS[i % EMO_COLORS.length] }} />
                    </div>
                  </div>
                );
              })
            )}
          </Panel>
        </PanelCol>
      </PanelRow>

      <Panel tabs={["주문 복기"]} actions={[]}>
        {isLoading ? (
          <Skeleton className="h-24" />
        ) : !data || data.events.length === 0 ? (
          <EmptyNote>이날의 거래 기록이 없습니다.</EmptyNote>
        ) : (
          <ol className="m-0 list-none p-0">
            {data.events.map((ev, i) => {
              const q = ev.qty ?? ev.quantity;
              const buy = ev.type === "BUY";
              const sell = ev.type === "SELL";
              return (
                <li key={i} className="flex gap-3 border-b border-tm-line px-1 py-3">
                  <span className="num w-10 flex-none text-xs text-tm-muted">{fmtTime(ev.time, false)}</span>
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <div className="flex flex-wrap items-center gap-2">
                      {(ev.stockName || ev.stockSymbol) && <b>{ev.stockName ?? ev.stockSymbol}</b>}
                      <span className={buy ? "text-up" : sell ? "text-down" : "text-tm-soft"}>{TYPE_LABEL[ev.type] ?? ev.type}</span>
                      {q != null && <span className="num text-tm-soft">{fmtNum(q)}주{ev.price != null && ` ${fmtNum(ev.price)}`}</span>}
                      {ev.tradeId != null && <OriginBadge origin={ev.origin} originRef={ev.originRef} />}
                      {ev.planned === false && <span title={PLANNED_DEFINITION}><Pill tone="orange">계획 외</Pill></span>}
                      {ev.emotion && (
                        <span title={ev.memo ?? undefined}><Chip className="h-[22px]">{emotionLabel(ev.emotion)}</Chip></span>
                      )}
                    </div>
                    {ev.memo && <span className="text-xs leading-normal text-tm-muted">“{ev.memo}”</span>}
                    {ev.description && <span className="text-xs leading-normal text-tm-muted">{ev.description}</span>}
                  </div>
                  {ev.pnlPct != null ? (
                    <span className={`num font-semibold ${dirClass(ev.pnlPct)}`}>{`${ev.pnlPct > 0 ? "+" : ""}${ev.pnlPct.toFixed(2)}%`}</span>
                  ) : ev.amount != null ? (
                    <span className={`num font-semibold ${dirClass(ev.amount)}`}>{fmtSigned(ev.amount)}</span>
                  ) : null}
                </li>
              );
            })}
          </ol>
        )}
      </Panel>
    </TerminalPage>
  );
}
