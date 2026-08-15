import { Newspaper } from "@phosphor-icons/react";
import { EmptyState } from "@/components/ui/EmptyState";
import { Card } from "@/components/ui/Card";

interface Props {
  stockId: number;
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
export default function NewsPanel({ stockId: _stockId }: Props) {
  return (
    <Card className="p-4">
      <h3 className="font-semibold text-gray-900 dark:text-[#f8f8f2] mb-3">관련 뉴스</h3>
      <EmptyState
        icon={Newspaper}
        title="뉴스 수집 예정"
        description="외부 뉴스 API 연동 후 표시됩니다."
        className="py-8"
      />
    </Card>
  );
}
