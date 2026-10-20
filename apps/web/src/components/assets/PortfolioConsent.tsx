"use client";

import type { ConsentStatus } from "@/services/consent";
import { Checkbox } from "@/components/terminal";

/**
 * "계좌 정보 수집·이용" 동의(ADR-068, ADR-106 §7). 서버 문서 버전은 portfolio-data-draft-2026-10 — 전문은 확정 전이다
 * (docs/human-action-items.md). 문구는 지금 구현이 실제로 하는 것만 적는다: 원본 파일을 저장하지 않는 것, 전체 삭제·탈퇴 시
 * 바로 지우는 것(PortfolioUserDeletionListener), 보유 종목 소식 알림(ADR-108).
 */
export const PORTFOLIO_CONSENT_ITEMS: { k: string; v: string }[] = [
  { k: "목적", v: "여러 증권 계좌의 보유 종목·손익을 한곳에 보여 주고, 보유 종목의 소식(급등·급락·거래량 급증·뉴스·공시)을 알림과 피드로 알려 드립니다." },
  { k: "항목", v: "계좌 이름·증권사·계좌 종류·계좌번호 끝 4자리(입력한 경우), 거래 내역(일자·구분·종목·수량·단가·금액·수수료·세금·통화·메모). 올린 파일 원본은 저장하지 않고, 같은 파일인지 확인하는 해시값만 남깁니다." },
  { k: "보관 기간", v: "직접 지우거나 '내 자산 데이터 전체 삭제'를 누를 때, 또는 회원 탈퇴할 때까지. 그때 바로 지웁니다." },
  { k: "철회", v: "'내 자산 데이터 전체 삭제'로 언제든 철회할 수 있고, 철회하면 가져온 계좌와 거래를 모두 지웁니다." },
  { k: "거부", v: "동의하지 않아도 내 자산을 뺀 다른 기능은 그대로 쓸 수 있습니다. 증권사 아이디·비밀번호는 받지 않습니다." },
];

/** 지금 동의를 받아야 하나 — 처음이거나, 철회했거나, 문서가 바뀌었다 */
export function portfolioConsentNeeded(status: ConsentStatus | undefined): { needed: boolean; renewal: boolean } {
  const s = status?.states.find((x) => x.type === "PORTFOLIO_DATA_COLLECTION");
  if (!s) return { needed: true, renewal: false };
  const current = s.agreed && s.documentVersion === s.currentVersion;
  return { needed: !current, renewal: !current && s.agreed };
}

export function PortfolioConsentBlock({ checked, onChange, renewal = false }: { checked: boolean; onChange: (v: boolean) => void; renewal?: boolean }) {
  return (
    <section aria-label="계좌 정보 수집·이용 동의" className="flex flex-col gap-2.5 rounded-lg border border-tm-line bg-tm-inner p-3">
      <p className="m-0 text-xs font-semibold text-tm-soft">
        계좌 정보 수집·이용 동의 (필수){renewal && <span className="ml-1.5 font-normal text-dracula-orange">— 안내 문서가 바뀌어 다시 동의가 필요합니다</span>}
      </p>
      <dl className="m-0 flex flex-col gap-1.5 text-xs leading-relaxed">
        {PORTFOLIO_CONSENT_ITEMS.map((i) => (
          <div key={i.k} className="flex gap-2">
            <dt className="w-[60px] flex-none text-tm-muted">{i.k}</dt>
            <dd className="m-0 text-tm-soft">{i.v}</dd>
          </div>
        ))}
      </dl>
      <Checkbox checked={checked} onChange={onChange} label="위 내용을 읽었고 계좌 정보 수집·이용에 동의합니다" />
    </section>
  );
}
