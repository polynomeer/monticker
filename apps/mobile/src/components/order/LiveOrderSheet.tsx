import { useRouter } from "expo-router";
import { EmptyState } from "@/components/ui";
import type { Side } from "@/lib/order";

/** 실전 주문 시트 자리 — 길게 눌러 주문하는 실전 시트는 M6에서 붙인다. 그 전에는 실전 모드에서 주문하지 않는다 */
export function LiveOrderSheet(_props: { symbol: string; initialSide: Side }) {
  const router = useRouter();
  return (
    <EmptyState
      title="실전 주문은 곧 앱에서 할 수 있어요"
      sub="지금은 웹의 실전투자 화면에서 주문해 주세요. 모의 주문은 내 계좌에서 모의로 바꾸면 할 수 있어요."
      action={{ label: "닫기", onPress: () => router.back() }}
    />
  );
}
