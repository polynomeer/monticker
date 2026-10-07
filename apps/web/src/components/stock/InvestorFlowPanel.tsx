"use client";

import { useEffect, useState } from "react";
import { Pill } from "@/components/terminal";
import { cn } from "@/lib/utils";
import { Muted, SkeletonRows } from "./parts";

interface InvestorFlowDay {
  tradeDate: string;
  individualNetAmount: number;
  foreignNetAmount: number;
  institutionNetAmount: number;
  isMocked: boolean;
}
interface InvestorFlowResult {
  stockId: number;
  days: InvestorFlowDay[];
  isAnyMocked: boolean;
}

interface Props {
  stockId: number;
  /** 제목 없이 내용만 (패널 탭 안에 임베드할 때) */
  bare?: boolean;
}

function fmtAmount(n: number) {
  const sign = n < 0 ? "-" : "+";
  const abs = Math.abs(n);
  const body =
    abs >= 100_000_000 ? `${(abs / 100_000_000).toFixed(1)}억` :
    abs >= 10_000       ? `${(abs / 10_000).toFixed(0)}만` :
    abs.toLocaleString("ko-KR");
  return `${sign}${body}`;
}

const dir = (v: number) => (v >= 0 ? "text-up" : "text-down");

/** 개인·외국인·기관 순매수 — 최근일 막대 + 일별 표. 색은 사용자 차트 테마(상승/하락색)를 따른다. */
export default function InvestorFlowPanel({ stockId, bare = false }: Props) {
  const [data, setData] = useState<InvestorFlowResult | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetch(`/api/stocks/${stockId}/investor-flow?days=10`)
      .then(res => (res.ok ? res.json() : null))
      .then((json: InvestorFlowResult | null) => { if (!cancelled) setData(json); })
      .catch(() => { if (!cancelled) setData(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [stockId]);

  const header = (
    <div className="flex items-center justify-between gap-2">
      {!bare ? <h3 className="m-0 text-15 font-bold">개인·외국인·기관 순매수</h3> : <span className="text-2xs text-tm-muted">개인·외국인·기관 순매수 · 최근 10거래일</span>}
      {data?.isAnyMocked && (
        <span title="KIS API 미설정 또는 응답 없음 — 모의 데이터로 대체됨">
          <Pill tone="orange">모의 데이터</Pill>
        </span>
      )}
    </div>
  );

  let body: React.ReactNode;
  if (loading) body = <SkeletonRows n={3} h="h-6" />;
  else if (!data || data.days.length === 0) body = <Muted>수급 데이터 없음 — 국내(KOSPI/KOSDAQ) 종목만 제공됩니다.</Muted>;
  else {
    const latest = data.days[0];
    const rows = [
      { label: "개인", value: latest.individualNetAmount },
      { label: "외국인", value: latest.foreignNetAmount },
      { label: "기관", value: latest.institutionNetAmount },
    ];
    const max = Math.max(1, ...rows.map(r => Math.abs(r.value)));
    body = (
      <>
        <div className="flex flex-col gap-2">
          {rows.map(r => (
            <div key={r.label} className="flex items-center gap-2">
              <span className="w-10 flex-none text-xs text-tm-muted">{r.label}</span>
              <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-tm-inner">
                <div className={cn("h-full rounded-full", r.value >= 0 ? "bg-up" : "bg-down")} style={{ width: `${(Math.abs(r.value) / max) * 100}%` }} />
              </div>
              <span className={cn("num w-16 text-right text-xs font-semibold", dir(r.value))}>{fmtAmount(r.value)}</span>
            </div>
          ))}
        </div>
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-xs">
            <thead>
              <tr className="text-tm-muted">
                <th scope="col" className="border-b border-tm-line py-1.5 text-left text-2xs font-medium">날짜</th>
                <th scope="col" className="border-b border-tm-line py-1.5 text-right text-2xs font-medium">개인</th>
                <th scope="col" className="border-b border-tm-line py-1.5 text-right text-2xs font-medium">외국인</th>
                <th scope="col" className="border-b border-tm-line py-1.5 text-right text-2xs font-medium">기관</th>
              </tr>
            </thead>
            <tbody>
              {data.days.map(d => (
                <tr key={d.tradeDate}>
                  <td className="num border-b border-tm-line py-1.5 text-tm-muted">
                    {new Date(d.tradeDate).toLocaleDateString("ko-KR", { month: "2-digit", day: "2-digit" })}
                  </td>
                  <td className={cn("num border-b border-tm-line py-1.5 text-right", dir(d.individualNetAmount))}>{fmtAmount(d.individualNetAmount)}</td>
                  <td className={cn("num border-b border-tm-line py-1.5 text-right", dir(d.foreignNetAmount))}>{fmtAmount(d.foreignNetAmount)}</td>
                  <td className={cn("num border-b border-tm-line py-1.5 text-right", dir(d.institutionNetAmount))}>{fmtAmount(d.institutionNetAmount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    );
  }

  return (
    <div className={cn("flex flex-col gap-3", !bare && "rounded-[10px] bg-tm-panel p-3.5")}>
      {header}
      {body}
    </div>
  );
}
