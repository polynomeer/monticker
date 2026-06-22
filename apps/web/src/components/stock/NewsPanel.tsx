interface NewsItem {
  id: number;
  title: string;
  source: string;
  publishedAt: string;
  url: string;
}

interface Props {
  stockId: number;
}

export default function NewsPanel({ stockId: _ }: Props) {
  // News collection (Week 6+ with real news API integration)
  const placeholderNews: NewsItem[] = [];

  return (
    <div className="border border-gray-200 dark:border-[#2b3240] dark:bg-[#1e2329] rounded-lg p-4">
      <h3 className="font-semibold mb-3 dark:text-[#eaecef]">관련 뉴스</h3>

      {placeholderNews.length === 0 ? (
        <div className="text-center py-6">
          <p className="text-gray-400 dark:text-[#848e9c] text-sm">뉴스 수집 기능은 추후 구현 예정입니다.</p>
          <p className="text-gray-300 dark:text-[#848e9c] text-xs mt-1">외부 뉴스 API 연동 후 표시됩니다.</p>
        </div>
      ) : null}
    </div>
  );
}
