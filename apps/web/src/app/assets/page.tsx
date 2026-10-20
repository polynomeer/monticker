"use client";

import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import type { PortfolioAccount } from "@monticker/types";
import { Btn, Icon, Notice, Panel, PanelRow, Seg, TerminalPage, dirClass, fmtSigned, type TopStat } from "@/components/terminal";
import { LoginRequired, Skeleton } from "@/components/portfolio/PaperStates";
import { useIsLoggedIn } from "@/components/home/data";
import { useConsentStatus } from "@/hooks/useConsents";
import { useAccounts, useAgreePortfolioConsent, useHoldings } from "@/hooks/usePortfolio";
import { AccountDetail } from "@/components/assets/AccountDetail";
import { BrokerageLinkCard } from "@/components/assets/BrokerageLinkCard";
import { PaperPortfolioView, usePaperStats } from "@/components/portfolio/PaperPortfolioView";
import { AddAccountDialog } from "@/components/assets/AddAccountDialog";
import { AssetHoldingsTable } from "@/components/assets/AssetHoldingsTable";
import { CurrencyTotals } from "@/components/assets/CurrencyTotals";
import { DeleteAllData } from "@/components/assets/DeleteAllData";
import { HoldingEventsFeed } from "@/components/assets/HoldingEventsFeed";
import { PortfolioConsentBlock, portfolioConsentNeeded } from "@/components/assets/PortfolioConsent";
import { INSTITUTION_LABEL, SOURCE_LABEL, fmtMoney } from "@/components/assets/labels";

type View = "TOTAL" | "ACCOUNTS" | "PAPER";
const VIEWS = [
  { value: "TOTAL", label: "통합" },
  { value: "ACCOUNTS", label: "계좌별" },
  { value: "PAPER", label: "모의투자" },
] as const;

const isView = (v: string | null): v is View => v === "TOTAL" || v === "ACCOUNTS" || v === "PAPER";

const TITLE = "내 자산";
const CRUMB = "여러 증권 계좌 통합";

/**
 * 내 자산(ADR-106 1단계, docs/account-aggregation-plan.md §8) — 손 입력·CSV로 넣은 여러 증권 계좌를 한곳에.
 * 통합: 계좌 사이에 합친 보유와 보유 종목 소식(ADR-108). 계좌별: 계좌 추가·가져오기·거래. 모의: 모의 포트폴리오 전체(ADR-110 —
 * 예전 /portfolio). 탭은 주소(?view=)에 남아 링크로 열고 새로 고침해도 유지된다 — 탭 이동은 방문 기록을 쌓지 않는다(replace).
 * 표시·복기용이다 — 실계좌 주문·리스크 검사는 이 값을 쓰지 않는다(ADR-055).
 */
export default function AssetsPage() {
  // useSearchParams는 정적 렌더에서 Suspense 경계가 필요하다(login 화면과 같은 방식)
  return (
    <Suspense fallback={<TerminalPage title={TITLE} crumb={CRUMB}><Skeleton className="h-72" /></TerminalPage>}>
      <AssetsScreen />
    </Suspense>
  );
}

function AssetsScreen() {
  const isLoggedIn = useIsLoggedIn();
  const router = useRouter();
  const param = useSearchParams().get("view");
  const view: View = isView(param) ? param : "TOTAL";
  const setView = (v: View) => router.replace(v === "TOTAL" ? "/assets" : `/assets?view=${v}`, { scroll: false });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [adding, setAdding] = useState(false);
  const [renewAgreed, setRenewAgreed] = useState(false);

  const consentStatus = useConsentStatus(isLoggedIn);
  const accounts = useAccounts(isLoggedIn);
  const real = useHoldings("REAL", isLoggedIn && view === "TOTAL");
  const paperStats = usePaperStats();
  const agree = useAgreePortfolioConsent();

  const list = accounts.data ?? [];
  const hasAccounts = list.length > 0;
  const consent = portfolioConsentNeeded(consentStatus.data);
  // 계좌가 있는데 동의가 현재 버전이 아니다(문서가 바뀌었다) — 가져오기 전에 다시 받는다
  const renewalNeeded = consentStatus.data != null && hasAccounts && consent.needed;
  const selected = list.find((a) => a.id === selectedId) ?? list[0] ?? null;

  if (!isLoggedIn) {
    return (
      <TerminalPage title={TITLE} crumb={CRUMB}>
        <LoginRequired message="내 자산을 보려면 로그인이 필요합니다." />
      </TerminalPage>
    );
  }

  const krw = real.data?.totals.find((t) => t.currency === "KRW");
  const stats: TopStat[] = [
    { label: "원화 평가금액", value: krw ? fmtMoney(krw.marketValue, "KRW") : "—" },
    { label: "원화 평가손익", value: krw ? fmtSigned(krw.unrealizedPnl) : "—", tone: krw ? dirClass(krw.unrealizedPnl) : undefined },
    { label: "외화 통화", value: `${real.data?.totals.filter((t) => t.currency !== "KRW").length ?? 0}개`, hint: "환율 환산은 아직 하지 않습니다 — 통화별로 따로 봅니다" },
    { label: "계좌", value: `${list.length}개` },
    { label: "보유 종목", value: `${real.data?.holdings.length ?? 0}개` },
  ];

  const onCreated = (a: PortfolioAccount) => {
    setAdding(false);
    setSelectedId(a.id);
    setView("ACCOUNTS");
  };

  return (
    <TerminalPage title={TITLE} crumb={CRUMB} stats={view === "PAPER" ? paperStats : view === "TOTAL" && hasAccounts ? stats : []}>
      <div className="flex flex-wrap items-center gap-2">
        <Seg options={VIEWS} value={view} onChange={setView} />
        <Btn size="sm" icon="plus" className="ml-auto" onClick={() => setAdding(true)}>계좌 추가</Btn>
      </div>

      {renewalNeeded && (
        <div className="flex flex-col gap-2 rounded-[10px] bg-tm-panel p-3">
          <PortfolioConsentBlock checked={renewAgreed} onChange={setRenewAgreed} renewal={consent.renewal} />
          {agree.isError && <Notice tone="danger">{agree.error instanceof Error ? agree.error.message : "동의를 기록하지 못했습니다."}</Notice>}
          <Btn size="sm" className="self-end" disabled={!renewAgreed || agree.isPending} onClick={() => agree.mutate(["PORTFOLIO_DATA_COLLECTION"])}>
            동의하고 계속
          </Btn>
        </div>
      )}

      {view === "PAPER" ? (
        <PaperPortfolioView />
      ) : accounts.isPending ? (
        <Skeleton className="h-72" />
      ) : accounts.isError ? (
        <Notice tone="danger">계좌를 불러오지 못했습니다.</Notice>
      ) : !hasAccounts ? (
        <Intro onAdd={() => setAdding(true)} />
      ) : view === "TOTAL" ? (
        <>
          {real.data && <CurrencyTotals totals={real.data.totals} />}
          <PanelRow>
            <Panel tabs={["보유 종목"]} actions={[]} closable={false} className="flex-[999_1_700px]" bodyClassName="px-1.5 pb-1.5 pt-1">
              {real.isPending ? <Skeleton className="h-60" /> : <AssetHoldingsTable holdings={real.data?.holdings ?? []} />}
              <p className="m-0 px-2 py-1.5 text-2xs text-tm-muted">
                평균단가는 이동평균(매수 수수료 포함)입니다 — 증권사 앱과 기준이 다를 수 있습니다. 현재가는 monticker가 받은 최근 1분봉 종가입니다.
              </p>
            </Panel>
            <Panel tabs={["보유 종목 소식"]} actions={[]} closable={false} className="flex-[1_1_320px]" bodyClassName="p-0">
              <HoldingEventsFeed enabled={view === "TOTAL"} />
            </Panel>
          </PanelRow>
        </>
      ) : (
        <PanelRow>
          <div className="flex flex-[0_1_260px] flex-col gap-2">
            <nav aria-label="계좌 목록" className="flex flex-col gap-1 rounded-[10px] bg-tm-panel p-1.5">
              {list.map((a) => (
                <button
                  key={a.id}
                  type="button"
                  aria-current={selected?.id === a.id ? "true" : undefined}
                  onClick={() => setSelectedId(a.id)}
                  className={`flex flex-col gap-0.5 rounded-lg px-3 py-2 text-left ${selected?.id === a.id ? "bg-tm-raised" : "hover:bg-tm-raised/60"}`}
                >
                  <span className="text-13 font-semibold">{a.name}</span>
                  <span className="text-2xs text-tm-muted">{INSTITUTION_LABEL[a.institution]} · {SOURCE_LABEL[a.source]}</span>
                </button>
              ))}
            </nav>
            <BrokerageLinkCard />
            <DeleteAllData onDone={() => { setSelectedId(null); setView("TOTAL"); }} />
          </div>
          <div className="flex-[999_1_640px] min-w-0">
            {selected && <AccountDetail key={selected.id} account={selected} consentMissing={renewalNeeded} onDeleted={() => setSelectedId(null)} />}
          </div>
        </PanelRow>
      )}

      {adding && (
        <AddAccountDialog
          consentNeeded={consent.needed}
          consentRenewal={consent.renewal}
          onClose={() => setAdding(false)}
          onCreated={onCreated}
        />
      )}
    </TerminalPage>
  );
}

/** 계좌가 하나도 없을 때 — 무엇을 하는 화면인지와 시작 버튼 */
function Intro({ onAdd }: { onAdd: () => void }) {
  return (
    <section className="flex flex-col items-center gap-4 rounded-[10px] bg-tm-panel px-6 py-14 text-center">
      <span className="grid h-12 w-12 place-items-center rounded-full bg-tm-raised text-dracula-purple">
        <Icon name="pie" size={22} />
      </span>
      <div className="flex max-w-lg flex-col gap-2">
        <p className="m-0 text-15 font-semibold">여러 증권사 계좌를 한곳에서 봅니다</p>
        <p className="m-0 text-13 leading-relaxed text-tm-soft">
          증권사 HTS·MTS에서 내려받은 거래내역 파일을 올리거나 거래를 직접 적으면, 계좌 사이에 합친 보유·평균단가·손익을 보여 드리고
          가진 종목의 급등·급락·뉴스·공시를 알려 드립니다. 증권사 아이디·비밀번호는 받지 않습니다.
        </p>
      </div>
      <Btn icon="plus" onClick={onAdd}>계좌 추가</Btn>
    </section>
  );
}
