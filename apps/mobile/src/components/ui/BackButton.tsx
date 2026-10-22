import { useRouter } from "expo-router";
import { useTheme } from "@/theme/ThemeProvider";
import { GlassButton } from "./Controls";
import { Icon } from "./Icon";

/** 시안의 유리 원형 뒤로 버튼 — 돌아갈 곳이 없으면 홈 */
export function BackButton({ label = "뒤로" }: { label?: string }) {
  const { c } = useTheme();
  const router = useRouter();
  return (
    <GlassButton onPress={() => (router.canGoBack() ? router.back() : router.replace("/"))} accessibilityLabel={label}>
      <Icon name="chevronLeft" size={22} color={c.text} strokeWidth={2.4} />
    </GlassButton>
  );
}
