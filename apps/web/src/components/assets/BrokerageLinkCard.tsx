"use client";

import { BtnLink } from "@/components/terminal";
import { maskAccount } from "@/components/brokerage/shared";
import { useBrokerageAccount } from "@/hooks/useBrokerage";

/**
 * 주문용 증권사 연동(BYOK)이 있으면 계좌 목록 아래에 알린다 — 실전투자 화면으로 가는 길만 둔다(ADR-115).
 * 그 잔고는 통합 보유에 넣지 않는다: 증권사 API로 보유를 모으는 것은 2단계이고 법률 자문 뒤다(ADR-106, legal-review-brief §2-12).
 */
export function BrokerageLinkCard() {
  const { data } = useBrokerageAccount();
  if (!data) return null;
  return (
    <div className="flex flex-col gap-1.5 rounded-[10px] border border-dracula-orange/40 bg-tm-panel p-3">
      <span className="text-2xs text-tm-muted">주문용 증권사 연동</span>
      <span className="text-13 font-semibold">
        {data.provider} <span className="num font-normal text-tm-soft">{maskAccount(data.accountNumber)}</span>
      </span>
      <span className="text-2xs leading-relaxed text-tm-muted">주문·잔고는 실전투자 화면에서 봅니다. 이 계좌의 잔고는 통합 합계에 아직 넣지 않습니다.</span>
      <BtnLink href="/brokerage" kind="ghost" size="sm">실전투자로</BtnLink>
    </div>
  );
}
