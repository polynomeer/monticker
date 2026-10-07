import Link from "next/link";
import { Pill } from "@/components/terminal";

/**
 * ADR-085 — 모의 주문의 진입 출처. 서버가 주문을 낸 코드 경로로 정해 체결에 남긴다(클라이언트가 정하지 않는다).
 * 출처를 판정할 수 없던 과거 거래는 null이고 "—"로 보인다.
 */
export type EntryOrigin = "MANUAL" | "WATCH_RULE" | "CONDITIONAL" | "STRATEGY";

/** 리플레이 "계획 준수율"·"계획 외 주문"의 정의 — 화면 툴팁과 ADR-085가 같은 문장을 쓴다. */
export const PLANNED_DEFINITION =
  "계획된 주문: Watch Rule·조건부 주문·전략이 낸 주문, 또는 감정 태그를 '계획대로'로 남긴 주문. " +
  "직접 낸 주문에 '계획대로' 태그가 없으면 계획 외 주문으로 셉니다. 출처를 알 수 없는 과거 주문은 비율에서 뺍니다. " +
  "기록을 돌아보기 위한 지표이며 투자 판단의 기준이 아닙니다.";

export function originLabel(origin: string | null | undefined, ref?: number | null): string | null {
  switch (origin) {
    case "MANUAL": return "직접";
    case "WATCH_RULE": return ref ? `Watch Rule #${ref}` : "Watch Rule";
    case "CONDITIONAL": return ref ? `조건부 #${ref}` : "조건부";
    case "STRATEGY": return ref ? `전략 #${ref}` : "전략";
    default: return null;
  }
}

/** 출처 배지. Watch Rule은 규칙 화면으로 이어진다. 출처를 모르면 "—". */
export function OriginBadge({ origin, originRef, suffix }: { origin?: string | null; originRef?: number | null; suffix?: string }) {
  const label = originLabel(origin, originRef);
  if (!label) return <span className="text-tm-muted">—</span>;
  const text = `${label}${suffix ?? ""}`;
  if (origin === "WATCH_RULE") {
    return (
      <Link href="/watch-rules" className="no-underline" title={`이 체결은 ${label}이(가) 낸 모의 주문입니다`}>
        <Pill tone="purple">{text}</Pill>
      </Link>
    );
  }
  if (origin === "CONDITIONAL") return <Pill tone="cyan">{text}</Pill>;
  if (origin === "STRATEGY") return <Pill tone="green">{text}</Pill>;
  return <span className="text-tm-soft">{text}</span>;
}
