"use client";

import { useEffect, useState } from "react";

interface NewsItem {
  id: number;
  title: string;
  description: string | null;
  url: string;
  source: string | null;
  publishedAt: string;
  sentiment: string | null;
}

interface Props {
  stockId: number;
}

export default function NewsPanel({ stockId }: Props) {
  const [news, setNews] = useState<NewsItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetch_ = async () => {
      try {
        const res = await fetch(`/api/stocks/${stockId}/news?limit=10`);
        if (res.ok) setNews(await res.json());
      } finally {
        setLoading(false);
      }
    };
    fetch_();
    const id = setInterval(fetch_, 60_000); // 1분마다 갱신
    return () => clearInterval(id);
  }, [stockId]);

  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <h3 className="font-semibold mb-3">관련 뉴스</h3>

      {loading && (
        <div className="space-y-3">
          {[1, 2, 3].map(i => (
            <div key={i} className="animate-pulse">
              <div className="h-4 bg-gray-200 rounded w-3/4 mb-1" />
              <div className="h-3 bg-gray-100 rounded w-1/4" />
            </div>
          ))}
        </div>
      )}

      {!loading && news.length === 0 && (
        <p className="text-gray-400 text-sm text-center py-4">
          뉴스 데이터를 수집 중입니다…
        </p>
      )}

      <ul className="space-y-3">
        {news.map(item => (
          <li key={item.id} className="border-b border-gray-100 pb-3 last:border-0 last:pb-0">
            <a
              href={item.url}
              target="_blank"
              rel="noopener noreferrer"
              className="block hover:text-blue-600 transition-colors"
            >
              <p className="text-sm font-medium leading-snug line-clamp-2">{item.title}</p>
              {item.description && (
                <p className="text-xs text-gray-500 mt-1 line-clamp-1">{item.description}</p>
              )}
              <div className="flex items-center gap-2 mt-1">
                {item.source && (
                  <span className="text-xs text-gray-400">{item.source}</span>
                )}
                <span className="text-xs text-gray-300">
                  {new Date(item.publishedAt).toLocaleDateString("ko-KR")}
                </span>
                {item.sentiment && (
                  <span className={`text-xs px-1.5 py-0.5 rounded ${
                    item.sentiment === "POSITIVE" ? "bg-green-50 text-green-600" :
                    item.sentiment === "NEGATIVE" ? "bg-red-50 text-red-600" :
                    "bg-gray-50 text-gray-500"
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
