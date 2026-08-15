"use client";

import { useState } from "react";
import { Input } from "@/components/ui/Input";
import { Card } from "@/components/ui/Card";
import { useToast } from "@/hooks/useToast";

interface Props {
  stockId: number;
  symbol: string;
}

export default function AlertPanel({ stockId, symbol }: Props) {
  const [ruleType, setRuleType] = useState("PRICE_ABOVE");
  const [threshold, setThreshold] = useState("");
  const [loading, setLoading] = useState(false);
  const { toast } = useToast();

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!threshold) return;

    setLoading(true);
    try {
      const res = await fetch("/api/alerts/rules", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          stockId,
          ruleType,
          condition: { threshold: parseFloat(threshold) },
        }),
      });
      if (res.ok) {
        toast({ type: "success", title: "알림 저장 완료", message: `${symbol} 알림이 등록되었습니다.` });
        setThreshold("");
      } else {
        toast({ type: "error", title: "저장 실패", message: "알림 저장 중 오류가 발생했습니다." });
      }
    } catch {
      toast({ type: "error", title: "네트워크 오류", message: "서버에 연결할 수 없습니다." });
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card className="p-4">
      <h3 className="font-semibold text-gray-900 dark:text-dracula-fg mb-3">
        알림 설정{" "}
        <span className="text-gray-500 dark:text-dracula-comment text-sm font-normal">({symbol})</span>
      </h3>

      <form onSubmit={handleSave} className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <label className="text-sm font-medium text-gray-700 dark:text-dracula-fg">알림 유형</label>
          <select
            value={ruleType}
            onChange={(e) => setRuleType(e.target.value)}
            className="w-full rounded-lg border border-gray-300 dark:border-dracula-line bg-white dark:bg-dracula-surface px-4 py-2.5 text-sm text-gray-900 dark:text-dracula-fg transition-colors hover:border-gray-400 dark:hover:border-dracula-comment focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple"
          >
            <option value="PRICE_ABOVE">가격 이상 알림</option>
            <option value="PRICE_BELOW">가격 이하 알림</option>
            <option value="VOLUME_SURGE">거래량 급증 알림</option>
          </select>
        </div>

        {(ruleType === "PRICE_ABOVE" || ruleType === "PRICE_BELOW") && (
          <Input
            type="number"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            placeholder="기준 가격 입력"
            label="기준 가격"
          />
        )}

        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-semibold text-sm py-2.5 hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-50 disabled:active:scale-100"
        >
          {loading ? "저장 중..." : "알림 저장"}
        </button>
      </form>
    </Card>
  );
}
