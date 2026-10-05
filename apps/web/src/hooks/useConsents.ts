"use client";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { agreeConsents, agreeOptionalConsent, getConsentStatus, withdrawConsent, type ConsentType } from "@/services/consent";

export const consentKey = ["users", "me", "consents"] as const;

export function useConsentStatus(enabled: boolean) {
  return useQuery({ queryKey: consentKey, queryFn: getConsentStatus, enabled, staleTime: 5 * 60_000 });
}

export function useAgreeConsents() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (consents: ConsentType[]) => agreeConsents(consents),
    onSuccess: (data) => qc.setQueryData(consentKey, data),
  });
}

export function useWithdrawConsent() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (type: ConsentType) => withdrawConsent(type),
    onSuccess: (data) => qc.setQueryData(consentKey, data),
  });
}

/** 선택 동의(마케팅) 켜기/끄기 — 설정 화면. 저장 버튼과 무관하게 즉시 기록된다(동의 원장, ADR-068). */
export function useSetOptionalConsent() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ type, agreed }: { type: ConsentType; agreed: boolean }) =>
      agreed ? agreeOptionalConsent(type) : withdrawConsent(type),
    onSuccess: (data) => qc.setQueryData(consentKey, data),
  });
}
