import { useRouter } from "expo-router";
import { EmptyState } from "@/components/ui";

/** 로그인이 필요한 화면의 자리 — 이유 한 줄과 로그인 버튼 */
export function SignInRequired({ message }: { message: string }) {
  const router = useRouter();
  return <EmptyState title="로그인이 필요해요" sub={message} action={{ label: "로그인", onPress: () => router.push("/login") }} />;
}
