"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { authFetch } from "@/services/api";
import { getAccessToken } from "@/services/auth";
import { usePaperHistory } from "@/hooks/usePaperTrade";
import type { LedgerEvent } from "@/hooks/useWalletLedger";
import WalletLedger, { type LedgerFilter } from "@/components/wallet/WalletLedger";
import { ReceiptCard, type Receipt } from "@/components/wallet/ReceiptCard";
import { useRiskExposure } from "@/components/risk/useRiskExposure";
import { reconciliationLabel, useReconciliation } from "@/components/wallet/useReconciliation";
import { EmptyNote, LoginRequired, Skeleton } from "@/components/portfolio/PaperStates";
import {
  AutoGrid, Bar, BtnLink, Panel, PanelCol, PanelRow, Seg, TerminalPage, Tile, dirClass, fmtNum, fmtPct, fmtSigned, type TopStat,
} from "@/components/terminal";

interface WalletMap {
  availableCash: number;
  reservedCash: number;
  holdingsValue: number;
  settlementPending: number;
  totalAssets: number;
  recentLedger: LedgerEvent[];
}

interface BehaviorScore {
  behaviorScore: number;
  survivalScore: number;
  feedback: string[];
  reliabilityNotes: Record<string, unknown>;
}

const LEDGER_SEG = [
  { value: "all", label: "전체" },
  { value: "fill", label: "체결" },
  { value: "settle", label: "정산" },
  { value: "cash", label: "입출금" },
] as const;

function grade(score: number) {
  return score >= 80 ? "A" : score >= 60 ? "B" : score >= 40 ? "C" : "D";
}

/** 가장 최근 모의 거래의 영수증 — 거래가 없으면 빈 상태 */
function LatestReceipt() {
  const { data: history = [], isLoading } = usePaperHistory();
  const latest = history[0];
  const { data: receipt, isLoading: loadingReceipt } = useQuery<Receipt | null>({
    queryKey: ["receipt", latest?.id],
    queryFn: async () => {
      const r = await authFetch(`/api/paper/trades/${latest!.id}/receipt`);
      return r.ok ? r.json() : null;
    },
    enabled: latest != null,
  });
  if (isLoading || (latest && loadingReceipt)) return <Skeleton className="h-64" />;
  if (!latest || !receipt) return <EmptyNote>아직 체결된 거래가 없습니다. 첫 모의 주문을 내면 영수증이 여기에 남습니다.</EmptyNote>;
  return <ReceiptCard receipt={receipt} />;
}

export default function WalletPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [ledgerTab, setLedgerTab] = useState("원장 타임라인");
  const [filter, setFilter] = useState<LedgerFilter>("all");

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const { data: wallet, isLoading: walletLoading, isError } = useQuery<WalletMap>({
    queryKey: ["wallet"],
    queryFn: async () => {
      const res = await authFetch("/api/wallet");
      if (!res.ok) throw new Error("지갑 정보 조회 실패");
      return res.json();
    },
    refetchInterval: 30_000,
    enabled: isLoggedIn,
  });

  const { data: score, isLoading: scoreLoading } = useQuery<BehaviorScore>({
    queryKey: ["wallet", "score"],
    queryFn: async () => {
      const res = await authFetch("/api/wallet/score");
      if (!res.ok) throw new Error("점수 조회 실패");
      return res.json();
    },
    enabled: isLoggedIn,
  });

  const { data: exposure } = useRiskExposure(isLoggedIn);
  const { data: recon } = useReconciliation(isLoggedIn);
  const reconLabel = reconciliationLabel(recon);

  const title = { title: "투자 월렛", crumb: "모의투자 · 내 돈이 어디에 어떤 상태로 있는지" };

  if (!isLoggedIn) {
    return (
      <TerminalPage {...title}>
        <LoginRequired message="투자 월렛을 보려면 로그인이 필요합니다." icon="wallet" />
      </TerminalPage>
    );
  }

  const total = wallet?.totalAssets ?? 0;
  const pct = (v: number) => (total > 0 ? (v / total) * 100 : 0);
  const buckets = wallet
    ? [
        { name: "현금", desc: "주문 가능", value: wallet.availableCash, color: "#50fa7b" },
        { name: "예약금", desc: "미체결 주문에 묶인 돈", value: wallet.reservedCash, color: "#f1fa8c" },
        { name: "평가액", desc: "보유 종목 현재가 기준", value: wallet.holdingsValue, color: "#bd93f9" },
        { name: "정산 대기", desc: "체결 후 T+2 정산 전", value: wallet.settlementPending, color: "#8be9fd" },
      ]
    : [];

  const stats: TopStat[] = wallet
    ? [
        { label: "총 자산", value: `${fmtNum(total)}원` },
        {
          label: "오늘 손익",
          value: exposure ? `${fmtSigned(exposure.dailyPnl)} (${fmtPct(exposure.dailyPnlPct)})` : "—",
          tone: exposure ? dirClass(exposure.dailyPnl) : "text-tm-muted",
        },
        { label: "가용 현금", value: `${fmtNum(wallet.availableCash)}원` },
        { label: "예약금", value: `${fmtNum(wallet.reservedCash)}원`, tone: "text-dracula-yellow" },
        { label: "정산 대기", value: `${fmtNum(wallet.settlementPending)}원`, tone: "text-dracula-cyan" },
      ]
    : [];

  return (
    <TerminalPage {...title} stats={stats} account={{ kind: "paper", balance: wallet ? `${fmtNum(total)}원` : undefined }}>
      <Panel
        tabs={["돈의 이동 지도"]}
        actions={["expand"]}
        right={
          <div className="flex gap-1.5">
            <BtnLink href="/wallet/replay" kind="ghost" size="sm">오늘의 리플레이</BtnLink>
            <BtnLink href="/settlement" kind="ghost" size="sm">정산 내역</BtnLink>
          </div>
        }
      >
        {walletLoading ? (
          <Skeleton className="h-40" />
        ) : isError || !wallet ? (
          <p role="alert" className="m-0 py-8 text-center text-13 text-[#ff8a8a]">지갑 정보를 불러오지 못했습니다.</p>
        ) : (
          <>
            <div className="flex h-3.5 gap-0.5 overflow-hidden rounded-full bg-tm-inner" role="img" aria-label={buckets.map((b) => `${b.name} ${pct(b.value).toFixed(1)}%`).join(", ")}>
              {buckets.map((b) => pct(b.value) > 0 && <div key={b.name} title={b.name} style={{ width: `${pct(b.value)}%`, background: b.color }} />)}
            </div>
            <AutoGrid min={180}>
              {buckets.map((b) => (
                <Tile key={b.name}>
                  <span className="flex items-center gap-2 text-xs text-tm-soft">
                    <span className="h-2.5 w-2.5 rounded-[3px]" style={{ background: b.color }} />
                    {b.name}
                    <span className="num ml-auto text-tm-muted">{pct(b.value).toFixed(1)}%</span>
                  </span>
                  <span className="num text-xl font-semibold">
                    {fmtNum(b.value)}<span className="text-xs text-tm-muted"> 원</span>
                  </span>
                  <span className="text-2xs text-tm-muted">{b.desc}</span>
                </Tile>
              ))}
            </AutoGrid>
            <div className="flex flex-wrap items-center gap-1.5 text-xs text-tm-muted">
              {[
                ["현금", "text-dracula-green"],
                ["예약금", "text-dracula-yellow"],
                ["정산 대기", "text-dracula-cyan"],
                ["평가액 / 현금", "text-dracula-purple"],
              ].map(([n, c], i) => (
                <span key={n} className="flex items-center gap-1.5">
                  {i > 0 && <span aria-hidden>→</span>}
                  <span className={`rounded-full bg-tm-inner px-2.5 py-1 ${c}`}>{n}</span>
                </span>
              ))}
              <span className="ml-1.5">주문 → 체결 → T+2 정산 순서로 돈이 이동합니다</span>
            </div>
          </>
        )}
      </Panel>

      <PanelRow>
        <Panel
          tabs={["원장 타임라인", "입출금"]}
          active={ledgerTab}
          onTabChange={(k) => { setLedgerTab(k); setFilter(k === "입출금" ? "cash" : "all"); }}
          actions={["expand"]}
          className="flex-[999_1_560px]"
        >
          <div className="flex flex-wrap justify-between gap-2">
            {ledgerTab === "원장 타임라인" ? <Seg options={LEDGER_SEG} value={filter} onChange={setFilter} /> : <span />}
            {/* ADR-043 일일 대사(스냅샷) 결과 — 불일치는 자동 교정하지 않고 조사한다 */}
            <span className="self-center text-xs text-tm-muted" title={reconLabel.title}>
              이중 기입 원장 기준 · 잔액 불일치 <span className={`num ${reconLabel.tone}`}>{reconLabel.value}</span>
            </span>
          </div>
          {/* ADR-043 커서 페이징 (recentLedger 10건이 아니라 전체를 무한 스크롤) */}
          <WalletLedger filter={filter} />
        </Panel>

        <PanelCol className="flex-[1_1_360px]">
          <Panel tabs={["투자 영수증"]} actions={[]}>
            <LatestReceipt />
          </Panel>
          <Panel tabs={["점수"]} actions={[]}>
            {scoreLoading ? (
              <Skeleton className="h-28" />
            ) : !score ? (
              <EmptyNote>점수를 불러오지 못했습니다.</EmptyNote>
            ) : (
              <>
                <AutoGrid min={220}>
                  {[
                    { n: "투자 행동 점수", v: score.behaviorScore, c: "text-dracula-purple", bar: "bg-dracula-purple" },
                    { n: "투자 생존 점수", v: score.survivalScore, c: "text-dracula-green", bar: "bg-dracula-green" },
                  ].map((s) => (
                    <Tile key={s.n}>
                      <span className="flex justify-between text-xs text-tm-muted">
                        {s.n}
                        <span className="font-bold">등급 {grade(s.v)}</span>
                      </span>
                      <span className={`num text-[1.625rem] font-semibold ${s.c}`}>{s.v}</span>
                      <Bar pct={s.v} color={s.bar} h={5} track="bg-tm-panel" />
                    </Tile>
                  ))}
                </AutoGrid>
                {score.feedback.length > 0 && (
                  <ul className="m-0 flex list-disc flex-col gap-1 pl-4 text-xs text-tm-soft">
                    {score.feedback.map((fb, i) => <li key={i}>{fb}</li>)}
                  </ul>
                )}
                <span className="text-2xs text-tm-muted">모의투자 전용 교육용 피드백입니다. 실제 투자 조언이 아닙니다.</span>
              </>
            )}
          </Panel>
        </PanelCol>
      </PanelRow>
    </TerminalPage>
  );
}
