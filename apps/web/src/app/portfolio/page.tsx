import { redirect } from "next/navigation";

/** 모의 포트폴리오는 "내 자산"의 모의 탭으로 옮겼다(ADR-115). 예전 주소·북마크는 그쪽으로 보낸다 */
export default function PortfolioPage() {
  redirect("/assets?view=PAPER");
}
