import { BtnLink } from "@/components/terminal";
import { CenteredPage, StatusCardFrame } from "@/components/auth/StatusCard";

export default function NotFound() {
  return (
    <CenteredPage>
      <StatusCardFrame className="items-start">
        <span className="num text-[3rem] font-semibold leading-none text-dracula-purple">404</span>
        <h1 className="m-0 text-[1.375rem] font-bold">페이지를 찾을 수 없습니다</h1>
        <p className="m-0 leading-relaxed text-tm-soft">요청하신 페이지가 존재하지 않거나 이동되었습니다.</p>
        <div className="flex w-full flex-col gap-2">
          <BtnLink href="/" size="lg" full>홈으로 돌아가기</BtnLink>
          <BtnLink href="/stocks/search" kind="ghost" size="lg" full icon="search">종목 검색</BtnLink>
        </div>
      </StatusCardFrame>
    </CenteredPage>
  );
}
