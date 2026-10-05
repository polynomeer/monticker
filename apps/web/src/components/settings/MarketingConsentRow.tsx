"use client";

import { Toggle } from "@/components/terminal";
import { useConsentStatus, useSetOptionalConsent } from "@/hooks/useConsents";

function fmtDate(iso: string | null | undefined) {
  return iso ? new Date(iso).toLocaleDateString("ko-KR") : null;
}

/**
 * ADR-068 — 마케팅(광고성 정보) 수신 동의. 알림 설정의 "저장"과 별개로 누르는 즉시 동의/철회가 기록된다 — 동의는 증빙 원장이라
 * 저장 전 임시 상태를 두지 않는다. 철회하면 전략 마켓 소식 같은 광고성 알림은 알림 설정과 무관하게 보내지 않는다(ADR-082).
 */
export function MarketingConsentRow() {
  const { data, isLoading, isError } = useConsentStatus(true);
  const setConsent = useSetOptionalConsent();
  const marketing = data?.states.find((s) => s.type === "MARKETING");
  const agreed = !!marketing?.agreed && marketing.documentVersion === marketing.currentVersion;
  const when = fmtDate(marketing?.recordedAt);

  return (
    <div className="flex items-center justify-between gap-4 border-b border-tm-line py-3">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="font-semibold">마케팅 정보 수신 동의 (선택)</span>
        <span className="text-xs text-tm-muted">
          새 검증 전략·프로모션 같은 광고성 정보를 받습니다. 끄면 즉시 철회되며, 서비스 이용에는 영향이 없습니다.
        </span>
        <span className="text-2xs text-tm-muted" aria-live="polite">
          {isLoading ? "동의 상태 확인 중…"
            : isError ? "동의 상태를 불러오지 못했습니다."
            : setConsent.isError ? (setConsent.error as Error).message
            : when ? `${when} ${agreed ? "동의" : "철회"}` : "동의한 적 없음"}
        </span>
      </div>
      <Toggle
        checked={agreed}
        label="마케팅 정보 수신 동의"
        disabled={isLoading || isError || setConsent.isPending}
        onChange={(v) => setConsent.mutate({ type: "MARKETING", agreed: v })}
      />
    </div>
  );
}
