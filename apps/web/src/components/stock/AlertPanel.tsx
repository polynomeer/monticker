"use client";

import { useState } from "react";
import { Btn, Field, SelectBox } from "@/components/terminal";
import { useToast } from "@/hooks/useToast";

interface Props {
  stockId: number;
  symbol: string;
  /** 테두리/제목 없이 폼만 렌더링 (다른 카드 안에 임베드할 때) */
  bare?: boolean;
}

const RULE_TYPES = [
  { value: "PRICE_ABOVE",    label: "가격 이상 알림" },
  { value: "PRICE_BELOW",    label: "가격 이하 알림" },
  { value: "VOLUME_SURGE",   label: "거래량 급증 알림" },
  { value: "RSI_BELOW",      label: "RSI 과매도 알림" },
  { value: "RSI_ABOVE",      label: "RSI 과매수 알림" },
  { value: "PRICE_BELOW_MA", label: "이동평균 하향 이탈 알림" },
  { value: "PRICE_ABOVE_MA", label: "이동평균 상향 돌파 알림" },
  { value: "HOLDING_DROP",   label: "보유종목 하락 알림 (모의투자)" },
] as const;
type RuleType = (typeof RULE_TYPES)[number]["value"];

const DEFAULT_RSI_THRESHOLD: Partial<Record<RuleType, string>> = {
  RSI_BELOW: "30",
  RSI_ABOVE: "70",
};

export default function AlertPanel({ stockId, symbol, bare = false }: Props) {
  const [ruleType, setRuleType] = useState<RuleType>("PRICE_ABOVE");
  const [threshold, setThreshold] = useState("");
  const [period, setPeriod] = useState("14");
  const [dropPct, setDropPct] = useState("10");
  const [loading, setLoading] = useState(false);
  const { toast } = useToast();

  const changeRuleType = (next: RuleType) => {
    setRuleType(next);
    setThreshold(DEFAULT_RSI_THRESHOLD[next] ?? "");
    if (next === "RSI_BELOW" || next === "RSI_ABOVE") setPeriod("14");
    if (next === "PRICE_BELOW_MA" || next === "PRICE_ABOVE_MA") setPeriod("20");
  };

  // 유형마다 필요한 조건 필드가 달라 하나의 threshold 값만으로는 검증할 수 없다 —
  // VOLUME_SURGE/이동평균 조건은 기본값만으로 등록 가능해야 하는데, 기존엔 폼 전체가
  // threshold 하나로만 막혀 있어 VOLUME_SURGE는 입력창 자체가 없으니 항상 저장이
  // 막혀 있었다(값을 채울 방법이 없어 클릭해도 아무 일도 안 일어남).
  const buildCondition = (): Record<string, number> | null => {
    switch (ruleType) {
      case "PRICE_ABOVE":
      case "PRICE_BELOW":
        return threshold ? { threshold: parseFloat(threshold) } : null;
      case "VOLUME_SURGE":
        return {};
      case "RSI_BELOW":
      case "RSI_ABOVE":
        return threshold ? { period: parseInt(period, 10) || 14, threshold: parseFloat(threshold) } : null;
      case "PRICE_BELOW_MA":
      case "PRICE_ABOVE_MA":
        return { period: parseInt(period, 10) || 20 };
      case "HOLDING_DROP":
        return dropPct ? { dropPct: parseFloat(dropPct) } : null;
      default:
        return null;
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    const condition = buildCondition();
    if (!condition) return;

    setLoading(true);
    try {
      const res = await fetch("/api/alerts/rules", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ stockId, ruleType, condition }),
      });
      if (res.ok) {
        toast({ type: "success", title: "알림 저장 완료", message: `${symbol} 알림이 등록되었습니다.` });
        setThreshold(DEFAULT_RSI_THRESHOLD[ruleType] ?? "");
      } else {
        toast({ type: "error", title: "저장 실패", message: "알림 저장 중 오류가 발생했습니다." });
      }
    } catch {
      toast({ type: "error", title: "네트워크 오류", message: "서버에 연결할 수 없습니다." });
    } finally {
      setLoading(false);
    }
  };

  const form = (
      <form onSubmit={handleSave} className="flex flex-col gap-2.5">
        <SelectBox label="알림 유형" value={ruleType} onChange={(e) => changeRuleType(e.target.value as RuleType)}>
          {RULE_TYPES.map(rt => <option key={rt.value} value={rt.value}>{rt.label}</option>)}
        </SelectBox>

        {(ruleType === "PRICE_ABOVE" || ruleType === "PRICE_BELOW") && (
          <Field
            label="기준 가격"
            aria-label="기준 가격"
            type="number"
            inputMode="decimal"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            placeholder="기준 가격 입력"
          />
        )}

        {(ruleType === "RSI_BELOW" || ruleType === "RSI_ABOVE") && (
          <div className="flex gap-2">
            <Field label="RSI 기간(일)" aria-label="RSI 기간(일)" type="number" value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="14" />
            <Field label="RSI 기준값" aria-label="RSI 기준값" type="number" value={threshold} onChange={(e) => setThreshold(e.target.value)} placeholder={ruleType === "RSI_BELOW" ? "30" : "70"} />
          </div>
        )}

        {(ruleType === "PRICE_BELOW_MA" || ruleType === "PRICE_ABOVE_MA") && (
          <Field label="이동평균 기간(일)" aria-label="이동평균 기간(일)" type="number" value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="20" />
        )}

        {ruleType === "HOLDING_DROP" && (
          <div className="flex flex-col gap-1.5">
            <Field label="하락률 기준(%)" aria-label="하락률 기준(%)" type="number" value={dropPct} onChange={(e) => setDropPct(e.target.value)} placeholder="10" />
            <p className="m-0 text-xs text-tm-muted">모의투자로 이 종목을 보유 중일 때만 평가됩니다. 실전투자 보유종목은 대상이 아닙니다.</p>
          </div>
        )}

        <Btn type="submit" kind="primary" full disabled={loading}>
          {loading ? "저장 중..." : "알림 저장"}
        </Btn>
      </form>
  );

  if (bare) return form;

  return (
    <div className="flex flex-col gap-3 rounded-[10px] bg-tm-panel p-3.5">
      <h3 className="m-0 text-15 font-bold">
        알림 설정 <span className="text-13 font-normal text-tm-muted">({symbol})</span>
      </h3>
      {form}
    </div>
  );
}
