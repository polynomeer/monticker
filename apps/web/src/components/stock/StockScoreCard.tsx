"use client";

import { useEffect, useState } from "react";
import { Pill } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { SkeletonRows } from "./parts";

interface StockScoreAxis {
  axis: string;
  label: string;
  available: boolean;
  score: number | null;
  detail: string | null;
}
interface StockScoreResponse {
  stockId: number;
  axes: StockScoreAxis[];
  isValuationPopulationMocked: boolean;
}

interface Props {
  stockId: number;
  /** 제목 없이 내용만 (패널 탭 안에 임베드할 때) */
  bare?: boolean;
}

const SCORE_LABEL: Record<number, string> = { 0: "고평가", 1: "적정", 2: "저평가" };

export default function StockScoreCard({ stockId, bare = false }: Props) {
  const [data, setData] = useState<StockScoreResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetch(`/api/stocks/${stockId}/score`)
      .then(res => (res.ok ? res.json() : null))
      .then((json: StockScoreResponse | null) => { if (!cancelled) setData(json); })
      .catch(() => { if (!cancelled) setData(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [stockId]);

  const valuation = data?.axes.find(a => a.axis === "VALUATION");
  const pending = data?.axes.filter(a => a.axis !== "VALUATION") ?? [];
  const scoreClass = valuation?.score === 2 ? "text-up" : valuation?.score === 0 ? "text-down" : "text-dracula-fg";

  return (
    <div className={cn("flex flex-col gap-2.5", !bare && "rounded-[10px] bg-tm-panel p-3.5")}>
      <div className="flex items-center justify-between gap-2">
        <span className={bare ? "text-2xs text-tm-muted" : "text-15 font-bold"}>종목 스코어</span>
        {data?.isValuationPopulationMocked && (
          <span title="KIS API 미설정 또는 응답 없음 — 비교 종목 상당수가 모의 데이터라 스코어 신뢰도가 낮음">
            <Pill tone="orange">모의 데이터</Pill>
          </span>
        )}
      </div>

      {loading && <SkeletonRows n={2} h="h-8" />}

      {!loading && !valuation && <p className="m-0 text-xs text-tm-muted">스코어를 불러올 수 없습니다.</p>}

      {!loading && valuation && (
        <>
          {valuation.available ? (
            <div className="flex items-center justify-between gap-3 rounded-lg bg-tm-inner px-3 py-2.5">
              <div className="flex min-w-0 flex-col gap-0.5">
                <span className="text-13 font-semibold">{valuation.label}</span>
                {valuation.detail && <span className="text-2xs text-tm-muted">{valuation.detail}</span>}
              </div>
              <span className={cn("text-sm font-semibold", scoreClass)}>
                {valuation.score !== null ? SCORE_LABEL[valuation.score] : "—"}
              </span>
            </div>
          ) : (
            <p className="m-0 text-xs text-tm-muted">국내(KOSPI/KOSDAQ) 종목만 밸류에이션 스코어를 제공합니다.</p>
          )}
          {pending.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {pending.map(a => (
                <Pill key={a.axis} tone="muted">{a.label} 준비중</Pill>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
