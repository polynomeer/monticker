"use client";

import { useState } from "react";

interface Props {
  stockId: number;
  symbol: string;
}

export default function AlertPanel({ stockId, symbol }: Props) {
  const [ruleType, setRuleType] = useState("PRICE_ABOVE");
  const [threshold, setThreshold] = useState("");
  const [saved, setSaved] = useState(false);
  const [loading, setLoading] = useState(false);

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
        setSaved(true);
        setTimeout(() => setSaved(false), 2000);
        setThreshold("");
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <h3 className="font-semibold mb-3">알림 설정 <span className="text-gray-400 text-sm font-normal">({symbol})</span></h3>

      <form onSubmit={handleSave} className="flex flex-col gap-3">
        <select
          value={ruleType}
          onChange={(e) => setRuleType(e.target.value)}
          className="border border-gray-300 rounded px-3 py-2 text-sm"
        >
          <option value="PRICE_ABOVE">가격 이상 알림</option>
          <option value="PRICE_BELOW">가격 이하 알림</option>
          <option value="VOLUME_SURGE">거래량 급증 알림</option>
        </select>

        {(ruleType === "PRICE_ABOVE" || ruleType === "PRICE_BELOW") && (
          <input
            type="number"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            placeholder="기준 가격 입력"
            className="border border-gray-300 rounded px-3 py-2 text-sm"
          />
        )}

        <button
          type="submit"
          disabled={loading}
          className="bg-blue-600 text-white rounded px-4 py-2 text-sm hover:bg-blue-700 disabled:opacity-50"
        >
          {saved ? "저장됨 ✓" : loading ? "저장 중..." : "알림 저장"}
        </button>
      </form>
    </div>
  );
}
