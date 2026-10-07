"use client";

import { useQuery } from "@tanstack/react-query";
import { Pill } from "@/components/terminal";
import { stockKeys } from "@/hooks/useStockChart";

interface Props {
  stockId: number;
  symbol: string;
  /** 배경/제목 없이 내용만 렌더링 (탭 전환형 컨테이너에 임베드할 때) */
  bare?: boolean;
  /** bare 모드에서 "이벤트/뉴스 보기" 바로가기를 누르면 해당 탭으로 전환 */
  onNavigate?: (tab: "events" | "news") => void;
}

export default function SummaryPanel({ stockId, symbol, bare = false, onNavigate }: Props) {
  const { data, isLoading } = useQuery<{ summary: string }>({
    queryKey:        stockKeys.summary(stockId),
    queryFn:         async () => {
      const res = await fetch(`/api/stocks/${stockId}/summary`);
      return res.ok ? res.json() : { summary: null };
    },
    refetchInterval: 60_000,
    staleTime:       60_000,
  });

  const summary = data?.summary ?? null;

  const body = summary ? (
    <p className="m-0 text-sm leading-relaxed text-dracula-fg">{summary}</p>
  ) : isLoading ? (
    <div className="h-12 animate-pulse rounded-lg bg-tm-inner" aria-busy="true" aria-label="요약 불러오는 중" />
  ) : (
    <p className="m-0 text-13 text-tm-muted">요약을 불러올 수 없습니다.</p>
  );

  const tag = (
    <div className="flex items-center gap-2">
      <Pill tone="cyan">AI 생성</Pill>
      {isLoading && <span className="animate-pulse text-xs text-tm-muted">분석 중...</span>}
    </div>
  );

  if (bare) {
    return (
      <div className="flex flex-col gap-3">
        {tag}
        {body}
        {summary && onNavigate && (
          <div className="flex flex-wrap items-center gap-3 text-2xs text-tm-muted">
            <span>최근 이벤트·뉴스·가격 동향 기반 자동 요약 —</span>
            <button type="button" onClick={() => onNavigate("events")} className="text-dracula-purple hover:underline">이벤트 보기</button>
            <button type="button" onClick={() => onNavigate("news")} className="text-dracula-purple hover:underline">뉴스 보기</button>
          </div>
        )}
        <p className="m-0 text-2xs text-tm-muted">이벤트와 가격 변동의 시간적 연관을 요약할 뿐, 인과관계를 단정하지 않습니다.</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2 rounded-[10px] bg-tm-panel p-3.5">
      <div className="flex items-center gap-2">
        <span className="text-15 font-bold">AI 요약</span>
        <span className="text-xs text-tm-muted">({symbol})</span>
        {tag}
      </div>
      {body}
    </div>
  );
}
