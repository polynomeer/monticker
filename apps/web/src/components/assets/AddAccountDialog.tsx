"use client";

import { useState } from "react";
import type { PortfolioAccount, PortfolioAccountKind, PortfolioInstitution } from "@monticker/types";
import { Btn, Notice, Seg, SelectBox, TextField } from "@/components/terminal";
import { useCreateAccount } from "@/hooks/usePortfolio";
import { Modal } from "./Modal";
import { PortfolioConsentBlock } from "./PortfolioConsent";
import { ACCOUNT_KIND_LABEL, INSTITUTION_LABEL } from "./labels";

const SOURCES = [
  { value: "CSV", label: "파일 업로드" },
  { value: "MANUAL", label: "손 입력" },
] as const;

/**
 * 계좌 추가 — 증권사 거래내역 파일을 올릴 계좌(CSV) 또는 거래를 직접 적을 계좌(MANUAL).
 * 처음이면(또는 동의 문서가 바뀌었으면) 같은 요청에 동의를 담는다 — 서버가 계좌 생성과 동의 기록을 함께 한다.
 */
export function AddAccountDialog({
  consentNeeded, consentRenewal, onClose, onCreated,
}: {
  consentNeeded: boolean;
  consentRenewal: boolean;
  onClose: () => void;
  onCreated: (a: PortfolioAccount) => void;
}) {
  const [source, setSource] = useState<"CSV" | "MANUAL">("CSV");
  const [institution, setInstitution] = useState<PortfolioInstitution>("KIS");
  const [kind, setKind] = useState<PortfolioAccountKind>("GENERAL");
  const [name, setName] = useState("");
  const [last4, setLast4] = useState("");
  const [agreed, setAgreed] = useState(false);
  const create = useCreateAccount();

  const trimmed = name.trim();
  const last4Error = last4 !== "" && !/^\d{4}$/.test(last4) ? "끝 4자리 숫자만 넣습니다" : null;
  const valid = trimmed.length >= 1 && trimmed.length <= 50 && !last4Error && (!consentNeeded || agreed);

  const submit = async () => {
    try {
      const a = await create.mutateAsync({
        name: trimmed, institution, source, accountKind: kind, accountRefLast4: last4 || null,
        consents: consentNeeded ? ["PORTFOLIO_DATA_COLLECTION"] : [],
      });
      onCreated(a);
    } catch {
      // 오류 문구는 아래 Notice가 보여 준다
    }
  };

  return (
    <Modal title="계좌 추가" onClose={onClose}>
      <div className="flex flex-col gap-1.5">
        <span className="text-2xs text-tm-muted">거래를 넣는 방법</span>
        <Seg options={SOURCES} value={source} onChange={setSource} full />
        <p className="m-0 text-xs text-tm-muted">
          {source === "CSV"
            ? "증권사 HTS·MTS에서 내려받은 거래내역 파일(CSV)을 올립니다. 표준 템플릿에 옮겨 적어도 됩니다."
            : "거래를 한 건씩 직접 적습니다. 파일을 주지 않는 증권사나 펀드·비상장에 씁니다."}
        </p>
      </div>
      <TextField label="계좌 이름" placeholder="예: 한투 일반" value={name} maxLength={50} onChange={(e) => setName(e.target.value)} />
      <div className="flex gap-2">
        <SelectBox label="증권사" value={institution} onChange={(e) => setInstitution(e.target.value as PortfolioInstitution)}>
          {Object.entries(INSTITUTION_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </SelectBox>
        <SelectBox label="계좌 종류" value={kind} onChange={(e) => setKind(e.target.value as PortfolioAccountKind)}>
          {Object.entries(ACCOUNT_KIND_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </SelectBox>
      </div>
      <TextField
        label="계좌번호 끝 4자리 (선택)"
        inputMode="numeric"
        maxLength={4}
        value={last4}
        onChange={(e) => setLast4(e.target.value.trim())}
        error={last4Error}
        hint="계좌를 구분하는 데만 씁니다. 전체 번호는 받지 않습니다."
      />
      {consentNeeded && <PortfolioConsentBlock checked={agreed} onChange={setAgreed} renewal={consentRenewal} />}
      {create.isError && <Notice tone="danger">{create.error instanceof Error ? create.error.message : "계좌를 만들지 못했습니다."}</Notice>}
      <div className="flex justify-end gap-2">
        <Btn kind="ghost" onClick={onClose}>취소</Btn>
        <Btn onClick={submit} disabled={!valid || create.isPending}>{create.isPending ? "만드는 중…" : "계좌 추가"}</Btn>
      </div>
    </Modal>
  );
}
