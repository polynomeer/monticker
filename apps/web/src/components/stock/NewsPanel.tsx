"use client";

import { useEffect, useState } from "react";
import { Newspaper } from "@phosphor-icons/react";
import { Badge } from "@/components/ui/Badge";
import { EmptyState } from "@/components/ui/EmptyState";
import { Skeleton } from "@/components/ui/Skeleton";
import { Card } from "@/components/ui/Card";

interface NewsArticle {
  id: number;
  title: string;
  description: string | null;
  url: string;
  source: string | null;
  publishedAt: string;
  sentiment: string | null;
}

type BadgeVariant = "up" | "down" | "neutral";

const SENTIMENT_BADGE: Record<string, BadgeVariant> = {
  POSITIVE: "up",
  NEGATIVE: "down",
  NEUTRAL: "neutral",
};

const SENTIMENT_LABEL: Record<string, string> = {
  POSITIVE: "긍정",
  NEGATIVE: "부정",
  NEUTRAL: "중립",
};

interface Props {
  stockId: number;
}

export default function NewsPanel({ stockId }: Props) {
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

  return (
    <Card className="p-4">
      <h3 className="font-semibold text-gray-900 dark:text-dracula-fg mb-3">관련 뉴스</h3>

      {loading && (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <Skeleton key={i} className="h-16 w-full rounded-lg" />)}
        </div>
      )}

      {!loading && articles.length === 0 && (
        <EmptyState
          icon={Newspaper}
          title="관련 뉴스 없음"
          description="이 종목에 대한 최근 뉴스가 없습니다."
        />
      )}

      {!loading && articles.length > 0 && (
        <ul className="space-y-2">
          {articles.map(a => (
            <li key={a.id}>
              <a
                href={a.url}
                target="_blank"
                rel="noopener noreferrer"
                className="block p-3 rounded-lg border border-gray-200 dark:border-dracula-line/60 bg-gray-50 dark:bg-dracula-bg hover:border-gray-300 dark:hover:border-dracula-comment/60 transition-colors"
              >
                <div className="flex items-start justify-between gap-2">
                  <span className="text-sm font-medium text-gray-900 dark:text-dracula-fg line-clamp-2">
                    {a.title}
                  </span>
                  {a.sentiment && (
                    <Badge variant={SENTIMENT_BADGE[a.sentiment] ?? "neutral"} className="shrink-0">
                      {SENTIMENT_LABEL[a.sentiment] ?? a.sentiment}
                    </Badge>
                  )}
                </div>
                {a.description && (
                  <p className="text-xs mt-1 text-gray-500 dark:text-dracula-comment line-clamp-2">{a.description}</p>
                )}
                <div className="flex items-center gap-2 mt-1.5 text-xs text-gray-500 dark:text-dracula-comment">
                  {a.source && <span>{a.source}</span>}
                  <span>{new Date(a.publishedAt).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}</span>
                </div>
              </a>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
