"use client";

import { useQuery } from "@tanstack/react-query";
import { stockKeys } from "@/hooks/useStockChart";

interface NewsItem {
  id: number; title: string; description: string | null;
  url: string; source: string | null; publishedAt: string; sentiment: string | null;
}

interface Props { stockId: number; }

export default function NewsPanel({ stockId }: Props) {
  const { data: news = [], isLoading } = useQuery<NewsItem[]>({
    queryKey:        stockKeys.news(stockId),
    queryFn:         async () => {
      const res = await fetch(`/api/stocks/${stockId}/news?limit=10`);
      return res.ok ? res.json() : [];
    },
    refetchInterval: 60_000,
    staleTime:       60_000,
  });

  return (
    <div className="border border-gray-200 dark:border-[#44475a] dark:bg-[#282a36] rounded-lg p-4">
      <h3 className="font-semibold mb-3 dark:text-[#f8f8f2]">관련 뉴스</h3>

      {isLoading && (
        <div className="space-y-3">
          {[1,2,3].map(i => (
            <div key={i} className="animate-pulse">
              <div className="h-4 bg-gray-200 dark:bg-[#44475a] rounded w-3/4 mb-1" />
              <div className="h-3 bg-gray-100 dark:bg-[#44475a]/50 rounded w-1/4" />
            </div>
          ))}
        </div>
      )}

      {!isLoading && news.length === 0 && (
        <p className="text-gray-400 dark:text-[#6272a4] text-sm text-center py-4">
          뉴스 데이터를 수집 중입니다…
        </p>
      )}

      <ul className="space-y-3">
        {news.map((item: NewsItem) => (
          <li key={item.id} className="border-b border-gray-100 dark:border-[#44475a]/50 pb-3 last:border-0 last:pb-0">
            <a href={item.url} target="_blank" rel="noopener noreferrer"
              className="block hover:text-blue-600 transition-colors">
              <p className="text-sm font-medium leading-snug line-clamp-2 dark:text-[#f8f8f2]">{item.title}</p>
              {item.description && (
                <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-1 line-clamp-1">{item.description}</p>
              )}
              <div className="flex items-center gap-2 mt-1">
                {item.source && <span className="text-xs text-gray-400 dark:text-[#6272a4]">{item.source}</span>}
                <span className="text-xs text-gray-300 dark:text-[#44475a]">
                  {new Date(item.publishedAt).toLocaleDateString("ko-KR")}
                </span>
                {item.sentiment && (
                  <span className={`text-xs px-1.5 py-0.5 rounded ${
                    item.sentiment === "POSITIVE" ? "bg-green-50 dark:bg-[#0ecb81]/15 text-green-600 dark:text-[#0ecb81]" :
                    item.sentiment === "NEGATIVE" ? "bg-red-50 dark:bg-[#f6465d]/15 text-red-600 dark:text-[#f6465d]" :
                    "bg-gray-50 dark:bg-[#44475a]/50 text-gray-500 dark:text-[#6272a4]"
                  }`}>
                    {item.sentiment === "POSITIVE" ? "긍정" : item.sentiment === "NEGATIVE" ? "부정" : "중립"}
                  </span>
                )}
              </div>
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
