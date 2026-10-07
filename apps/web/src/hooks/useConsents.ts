"use client";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { agreeConsents, getConsentStatus, withdrawConsent, type ConsentType } from "@/services/consent";

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
