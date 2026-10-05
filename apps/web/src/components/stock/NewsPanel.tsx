"use client";

import { useEffect, useState } from "react";
import { Pill, type Tone } from "@/components/terminal";
import { Muted, SkeletonRows } from "./parts";

interface NewsArticle {
  id: number;
  title: string;
  description: string | null;
  url: string;
  source: string | null;
  publishedAt: string;
  sentiment: string | null;
}

const SENTIMENT_TONE: Record<string, Tone> = {
  POSITIVE: "green",
  NEGATIVE: "red",
  NEUTRAL: "muted",
};

const SENTIMENT_LABEL: Record<string, string> = {
  POSITIVE: "긍정",
  NEGATIVE: "부정",
  NEUTRAL: "중립",
};

interface Props {
  stockId: number;
  /** 테두리/제목 없이 내용만 렌더링 (탭 전환형 컨테이너에 임베드할 때) */
  bare?: boolean;
}

export default function NewsPanel({ stockId, bare = false }: Props) {
  const [articles, setArticles] = useState<NewsArticle[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetch(`/api/stocks/${stockId}/news?limit=10`)
      .then(res => (res.ok ? res.json() : []))
      .then((data: NewsArticle[]) => { if (!cancelled) setArticles(data); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [stockId]);

  const content = (
    <>
      {!bare && <h3 className="m-0 mb-2 text-15 font-bold text-dracula-fg">관련 뉴스</h3>}

      {loading && <SkeletonRows n={3} h="h-16" />}

      {!loading && articles.length === 0 && <Muted>이 종목에 대한 최근 뉴스가 없습니다.</Muted>}

      {!loading && articles.length > 0 && (
        <ul className="m-0 list-none p-0">
          {articles.map(a => (
            <li key={a.id} className="border-b border-tm-line">
              <a
                href={a.url}
                target="_blank"
                rel="noopener noreferrer"
                className="flex flex-col gap-1 px-1 py-2.5 text-dracula-fg hover:bg-tm-raised/40 hover:text-dracula-fg"
              >
                <span className="flex items-start justify-between gap-2">
                  <span className="line-clamp-2 text-13 font-semibold">{a.title}</span>
                  {a.sentiment && (
                    <Pill tone={SENTIMENT_TONE[a.sentiment] ?? "muted"} className="flex-none">
                      {SENTIMENT_LABEL[a.sentiment] ?? a.sentiment}
                    </Pill>
                  )}
                </span>
                {a.description && <span className="line-clamp-2 text-xs text-tm-muted">{a.description}</span>}
                <span className="flex items-center gap-2 text-2xs text-tm-muted">
                  {a.source && <span>{a.source}</span>}
                  <span className="num">{new Date(a.publishedAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}</span>
                </span>
              </a>
            </li>
          ))}
        </ul>
      )}
    </>
  );

  if (bare) return <div>{content}</div>;
  return <div className="rounded-[10px] bg-tm-panel p-3.5">{content}</div>;
}
