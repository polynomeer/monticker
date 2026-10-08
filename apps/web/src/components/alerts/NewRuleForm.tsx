"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Btn, Field, Notice, SelectBox } from "@/components/terminal";
import { StockPicker } from "@/components/quant/StockPicker";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";
import {
  CREATABLE_RULE_TYPES, buildRuleRequest, createRuleErrorMessage, defaultDraft,
  type CreatableRuleType, type RuleDraft, type RuleErrors,
} from "./ruleForm";

const errText = (id: string, msg?: string) =>
  msg ? <span id={id} role="alert" className="text-2xs text-[#ff8a8a]">{msg}</span> : null;

/**
 * 알림 화면에서 바로 만드는 새 알림 규칙(기존 POST /api/alerts/rules). 종목은 스크리너 검색(StockPicker)으로 고른다.
 * 성공하면 알림 관련 캐시를 새로 받고 폼을 비운다(유형은 유지).
 */
export default function NewRuleForm({ onDone }: { onDone?: () => void }) {
  const [draft, setDraft] = useState<RuleDraft>(() => defaultDraft());
  const [errors, setErrors] = useState<RuleErrors>({});
  const qc = useQueryClient();
  const { toast } = useToast();

  const patch = (p: Partial<RuleDraft>) => {
    setDraft((d) => ({ ...d, ...p }));
    setErrors((e) => {
      const next = { ...e };
      for (const k of Object.keys(p)) delete next[k as keyof RuleErrors];
      return next;
    });
  };

  const create = useMutation({
    mutationFn: async (body: NonNullable<ReturnType<typeof buildRuleRequest>["body"]>) => {
      const r = await authFetch("/api/alerts/rules", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!r.ok) {
        const msg = await r.json().then((b) => (typeof b?.message === "string" ? b.message : null)).catch(() => null);
        throw new Error(createRuleErrorMessage(r.status, msg));
      }
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["alerts"] });
      toast({ type: "success", title: "알림 규칙 추가", message: "새 알림 규칙을 만들었습니다." });
      setDraft((d) => defaultDraft(d.ruleType));
      setErrors({});
      onDone?.();
    },
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const { errors: errs, body } = buildRuleRequest(draft);
    setErrors(errs);
    if (body) create.mutate(body);
  };

  const t = draft.ruleType;
  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-2.5 rounded-lg border border-tm-line p-3" aria-label="새 알림 규칙">
      <div className="flex flex-col gap-1">
        <StockPicker
          market="all"
          marketCapTier="all"
          value={draft.stockId}
          onChange={(id) => patch({ stockId: id })}
        />
        {errText("rule-stock-err", errors.stockId)}
      </div>

      <SelectBox
        label="알림 유형"
        value={t}
        onChange={(e) => {
          const next = e.target.value as CreatableRuleType;
          setDraft((d) => ({ ...defaultDraft(next, d.stockId) }));
          setErrors((er) => ({ stockId: er.stockId }));
        }}
      >
        {CREATABLE_RULE_TYPES.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </SelectBox>

      {(t === "PRICE_ABOVE" || t === "PRICE_BELOW") && (
        <div className="flex flex-col gap-1">
          <Field
            label="기준 가격"
            type="number"
            inputMode="decimal"
            min={0}
            value={draft.threshold}
            onChange={(e) => patch({ threshold: e.target.value })}
            aria-invalid={!!errors.threshold}
            aria-describedby={errors.threshold ? "rule-threshold-err" : undefined}
          />
          {errText("rule-threshold-err", errors.threshold)}
        </div>
      )}

      {(t === "RSI_BELOW" || t === "RSI_ABOVE") && (
        <div className="flex gap-2">
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <Field label="RSI 기간(일)" type="number" inputMode="numeric" value={draft.period} onChange={(e) => patch({ period: e.target.value })}
              aria-invalid={!!errors.period} aria-describedby={errors.period ? "rule-period-err" : undefined} />
            {errText("rule-period-err", errors.period)}
          </div>
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <Field label="RSI 기준값" type="number" inputMode="decimal" value={draft.threshold} onChange={(e) => patch({ threshold: e.target.value })}
              aria-invalid={!!errors.threshold} aria-describedby={errors.threshold ? "rule-threshold-err" : undefined} />
            {errText("rule-threshold-err", errors.threshold)}
          </div>
        </div>
      )}

      {(t === "PRICE_BELOW_MA" || t === "PRICE_ABOVE_MA") && (
        <div className="flex flex-col gap-1">
          <Field label="이동평균 기간(일)" type="number" inputMode="numeric" value={draft.period} onChange={(e) => patch({ period: e.target.value })}
            aria-invalid={!!errors.period} aria-describedby={errors.period ? "rule-period-err" : undefined} />
          {errText("rule-period-err", errors.period)}
        </div>
      )}

      {t === "HOLDING_DROP" && (
        <div className="flex flex-col gap-1">
          <Field label="하락률 기준" unit="%" type="number" inputMode="decimal" value={draft.dropPct} onChange={(e) => patch({ dropPct: e.target.value })}
            aria-invalid={!!errors.dropPct} aria-describedby={errors.dropPct ? "rule-drop-err" : undefined} />
          {errText("rule-drop-err", errors.dropPct)}
          <span className="text-2xs text-tm-muted">모의투자로 이 종목을 보유 중일 때만 평가됩니다.</span>
        </div>
      )}

      {t === "VOLUME_SURGE" && (
        <span className="text-2xs text-tm-muted">최신 거래량이 평소(20거래일 평균)보다 크게 늘면 알립니다.</span>
      )}

      {create.error && <Notice tone="warn">{(create.error as Error).message}</Notice>}

      <Btn type="submit" kind="primary" full disabled={create.isPending}>
        {create.isPending ? "만드는 중..." : "알림 규칙 만들기"}
      </Btn>
    </form>
  );
}
