"use client";

import { useEffect, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { SettlementCalendar, signedNet, type PaperSettlement } from "@/components/settlement/SettlementCalendar";
import { addDays, holidaySet, kstYmd, nextBusinessDays, useMarketCalendar } from "@/components/market/krxCalendar";
import { useStockMeta } from "@/components/portfolio/useStockMeta";
import { LoginRequired, Skeleton } from "@/components/portfolio/PaperStates";
import { downloadCsv } from "@/components/portfolio/csv";
import { reconciliationLabel, useReconciliation } from "@/components/wallet/useReconciliation";
import { fmtMonthDay } from "@/components/portfolio/format";
import { Btn, DataTable, Num, Panel, Pill, Seg, TerminalPage, fmtNum, fmtSigned, type Column, type Tone } from "@/components/terminal";

interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

type Filter = "all" | "waiting" | "processing" | "done";

/** GET /api/settlement/paper/summary — 기간(정산일 기준) 순액, 매수 -, 매도 + (ADR-086) */
interface SettlementSummary {
  from: string;
  to: string;
  pendingNet: number;
  settledNet: number;
  totalNet: number;
  count: number;
  byDate: { date: string; net: number; count: number; holidayName: string | null }[];
  holidays: { date: string; name: string }[];
}

const FILTERS = [
  { value: "all", label: "전체" },
  { value: "waiting", label: "정산 대기" },
  { value: "processing", label: "처리 중" },
  { value: "done", label: "완료" },
] as const;

/**
 * 표시 상태 — 백엔드는 PENDING/SETTLED/FAILED 셋뿐이다.
 * 정산일이 오늘 이전인데 아직 PENDING이면 오늘 16:30 배치를 기다리는 중이므로 "처리 중"으로 보여 준다.
 */
function displayStatus(s: PaperSettlement, today: string): { label: string; tone: Tone; key: Filter | "failed" } {
  if (s.status === "SETTLED") return { label: "정산 완료", tone: "green", key: "done" };
  if (s.status === "FAILED") return { label: "실패", tone: "red", key: "failed" };
  return s.settleDate.slice(0, 10) <= today ? { label: "처리 중", tone: "yellow", key: "processing" } : { label: "정산 대기", tone: "cyan", key: "waiting" };
}

export default function SettlementPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [filter, setFilter] = useState<Filter>("all");
  const [page, setPage] = useState(0);

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  // "완료"는 서버에서 거른다(?status=SETTLED) — 현재 페이지만 거르면 20건 중 완료 건만 남아 페이지가 들쭉날쭉해진다
  const statusParam = filter === "done" ? "&status=SETTLED" : "";
  const { data, isLoading } = useQuery<Page<PaperSettlement>>({
    queryKey: ["settlement", "paper", filter === "done" ? "settled" : "all", page],
    queryFn: async () => {
      const res = await authFetch(`/api/settlement/paper?page=${page}&size=20${statusParam}`);
      if (!res.ok) throw new Error("조회 실패");
      return res.json();
    },
    enabled: isLoggedIn,
  });

  // 대기 목록은 일반 배열로 온다
  const { data: pending = [], isLoading: pendingLoading } = useQuery<PaperSettlement[]>({
    queryKey: ["settlement", "paper", "pending"],
    queryFn: async () => {
      const res = await authFetch("/api/settlement/paper/pending");
      if (!res.ok) throw new Error("조회 실패");
      const json = await res.json();
      return Array.isArray(json) ? json : json.content ?? [];
    },
    enabled: isLoggedIn,
  });
  // 이번 주(KST 월~일) 정산 순액 — 서버 집계(정산 완료 + 대기)
  const { data: week } = useQuery<SettlementSummary>({
    queryKey: ["settlement", "paper", "summary", "week"],
    queryFn: async () => {
      const res = await authFetch("/api/settlement/paper/summary");
      if (!res.ok) throw new Error("조회 실패");
      return res.json();
    },
    enabled: isLoggedIn,
  });
  const { data: recon } = useReconciliation(isLoggedIn);
  const reconLabel = reconciliationLabel(recon);

  // KRX 휴장일 캘린더(ADR-086). 받지 못하면 주말만 건너뛴다(아래 안내 문구가 그 사실을 알린다).
  const today = kstYmd();
  const { data: cal, isError: calError } = useMarketCalendar(today, addDays(today, 30));
  const holidays = holidaySet(cal);
  const days = nextBusinessDays(3, today, holidays);
  const [, d1, d2] = days;
  const upcomingHolidays = cal?.holidays ?? [];
  const calendarGap = calError || !cal || cal.uncoveredYears.length > 0;
  const meta = useStockMeta([...(data?.content ?? []), ...pending].map((s) => s.stockId));

  const rows = useMemo(() => {
    if (filter === "waiting" || filter === "processing") return pending.filter((s) => displayStatus(s, today).key === filter);
    const all = data?.content ?? [];
    return all;
  }, [filter, pending, data, today]);

  const sumOn = (day: string) => pending.filter((s) => s.settleDate.slice(0, 10) === day).reduce((a, s) => a + signedNet(s), 0);
  const pendingNet = pending.reduce((a, s) => a + signedNet(s), 0);
  const weekNet = week?.totalNet ?? null;
  const tone = (v: number) => (v < 0 ? "text-down" : v > 0 ? "text-up" : undefined);
  const name = (s: PaperSettlement) => (s.stockId && meta.get(s.stockId)?.name) || `거래 #${s.tradeId}`;

  const cols: Column<PaperSettlement>[] = [
    { key: "trade", header: "체결일", cell: (s) => <Num className="text-tm-muted">{fmtMonthDay(s.createdAt)}</Num> },
    { key: "settle", header: "정산일", cell: (s) => <Num className="text-tm-muted">{fmtMonthDay(s.settleDate)}</Num> },
    { key: "name", header: "종목", cell: (s) => <span>{name(s)} <span className="num text-2xs text-tm-muted">{fmtNum(s.quantity)}주</span></span> },
    { key: "side", header: "구분", cell: (s) => <span className={s.side === "BUY" ? "text-up" : "text-down"}>{s.side === "BUY" ? "매수" : "매도"}</span> },
    { key: "gross", header: "체결금", align: "right", cell: (s) => <Num>{fmtNum(s.grossAmount)}</Num> },
    { key: "fee", header: "수수료", align: "right", cell: (s) => <Num>{fmtNum(s.fee)}</Num> },
    { key: "tax", header: "세금", align: "right", cell: (s) => <Num>{fmtNum(s.tax)}</Num> },
    { key: "net", header: "순액", align: "right", cell: (s) => <Num className={tone(signedNet(s))}>{fmtSigned(signedNet(s))}</Num> },
    { key: "status", header: "상태", cell: (s) => { const st = displayStatus(s, today); return <Pill tone={st.tone}>{st.label}</Pill>; } },
  ];

  const title = { title: "모의투자 정산", crumb: "지갑" };
  if (!isLoggedIn) {
    return (
      <TerminalPage {...title}>
        <LoginRequired message="정산 내역을 보려면 로그인이 필요합니다." icon="calendar" />
      </TerminalPage>
    );
  }

  const exportCsv = () =>
    downloadCsv("paper-settlements.csv", ["체결일", "정산일", "종목", "구분", "수량", "체결금", "수수료", "세금", "순액", "상태"],
      rows.map((s) => [s.createdAt.slice(0, 10), s.settleDate.slice(0, 10), name(s), s.side === "BUY" ? "매수" : "매도", s.quantity, s.grossAmount, s.fee, s.tax, signedNet(s), displayStatus(s, today).label]));

  return (
    <TerminalPage
      {...title}
      stats={[
        { label: "정산 대기", value: `${fmtSigned(pendingNet)}원`, tone: "text-dracula-cyan" },
        { label: "다음 영업일 정산", value: d1 ? fmtSigned(sumOn(d1)) : "—", tone: d1 ? tone(sumOn(d1)) : undefined },
        { label: "그다음 영업일", value: d2 ? fmtSigned(sumOn(d2)) : "—", tone: d2 ? tone(sumOn(d2)) : undefined },
        { label: "이번 주 순액", value: weekNet == null ? "—" : fmtSigned(weekNet), tone: weekNet == null ? undefined : tone(weekNet) },
        // ADR-043 일일 원장 대사(스냅샷) 결과
        { label: "잔액 불일치", value: reconLabel.value, tone: reconLabel.tone },
      ]}
    >
      <Panel tabs={["정산 캘린더"]} actions={["expand"]}>
        {pendingLoading ? <Skeleton className="h-36" /> : <SettlementCalendar pending={pending} meta={meta} days={days} />}
        {upcomingHolidays.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5 text-xs">
            <span className="text-tm-muted">다가오는 휴장일</span>
            {upcomingHolidays.map((h) => (
              <Pill key={h.date} tone="yellow">{fmtMonthDay(h.date)} {h.name}</Pill>
            ))}
          </div>
        )}
        <span className="text-xs text-tm-muted">
          체결 후 T+2 영업일에 자동으로 정산됩니다(매일 16:30 KST). 주말과 KRX 휴장일(공휴일·대체공휴일·선거일·연말 휴장일)은 건너뜁니다.
          {calendarGap && " 지금은 휴장일 정보를 확인하지 못해 주말만 건너뛴 날짜로 표시합니다."}
        </span>
      </Panel>

      <Panel tabs={["정산 내역"]} actions={["download", "expand"]} onAction={(a) => a === "download" && exportCsv()} bodyClassName="px-1.5 pb-1.5 pt-2">
        <div className="flex flex-wrap gap-2 px-1.5 py-1">
          <Seg options={FILTERS} value={filter} onChange={(v) => { setFilter(v); setPage(0); }} />
        </div>
        {isLoading && filter !== "waiting" && filter !== "processing" ? (
          <Skeleton className="m-1.5 h-40" />
        ) : (
          <DataTable
            columns={cols}
            rows={rows}
            rowKey={(s) => s.id}
            minWidth={860}
            empty={filter === "waiting" || filter === "processing" ? "대기 중인 정산이 없습니다." : "아직 정산 내역이 없습니다. 모의투자를 시작해보세요."}
          />
        )}
        {(filter === "all" || filter === "done") && (data?.totalPages ?? 0) > 1 && (
          <div className="flex items-center justify-center gap-3 py-2">
            <Btn kind="soft" size="sm" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>이전</Btn>
            <span className="num text-xs text-tm-muted">{page + 1} / {data?.totalPages}</span>
            <Btn kind="soft" size="sm" disabled={page >= (data?.totalPages ?? 1) - 1} onClick={() => setPage((p) => p + 1)}>다음</Btn>
          </div>
        )}
        {rows.some((s) => s.side === "SELL" && s.tax > 0) && (
          <span className="px-1.5 text-2xs text-tm-muted">* 매도세 = 증권거래세 0.15% + 농특세 0.03% · 수수료 0.015%</span>
        )}
      </Panel>
    </TerminalPage>
  );
}
