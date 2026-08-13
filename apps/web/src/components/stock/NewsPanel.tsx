import { EmptyState } from "@/components/ui/EmptyState";

interface Props {
  stockId: number;
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export default function NewsPanel({ stockId: _stockId }: Props) {
  return (
    <div className="rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] p-4 shadow-sm dark:shadow-glow-line">
      <h3 className="font-semibold text-gray-900 dark:text-[#f8f8f2] mb-3">관련 뉴스</h3>
      <EmptyState
        icon="📰"
        title="뉴스 수집 예정"
        description="외부 뉴스 API 연동 후 표시됩니다."
        className="py-8"
      />
    </div>
  );
}
