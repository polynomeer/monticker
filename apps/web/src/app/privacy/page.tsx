import type { Metadata } from "next";
import { LegalDoc, type LegalSection } from "@/components/auth/LegalDoc";

export const metadata: Metadata = {
  title: "개인정보처리방침",
};

const sections: LegalSection[] = [
  {
    heading: "1. 수집하는 개인정보 항목",
    body: (
      <ul className="list-disc pl-5 space-y-1">
        <li>회원가입 시: 이메일, 닉네임, 비밀번호(암호화 저장)</li>
        <li>소셜 로그인 시: 소셜 서비스가 제공하는 이메일·프로필 정보</li>
        <li>증권사 계좌 연동 시: 사용자가 직접 등록한 증권사 Open API 키(암호화 저장) — monticker는 이 키로 사용자를 대신해 증권사 API를 호출할 뿐, 계좌 비밀번호나 공동인증서 등은 수집하지 않습니다</li>
        <li>서비스 이용 중 자동 수집: 접속 로그, 기기 정보, 쿠키</li>
      </ul>
    ),
  },
  {
    heading: "2. 개인정보의 수집 및 이용 목적",
    body: (
      <ul className="list-disc pl-5 space-y-1">
        <li>회원 식별 및 로그인 유지</li>
        <li>모의투자·Quant Lab·증권사 연동 등 서비스 제공</li>
        <li>구독 결제 및 전략 마켓 정산 처리</li>
        <li>알림(가격·거래량·룰셋 신호) 발송</li>
        <li>부정 이용 방지 및 서비스 안정성 확보</li>
      </ul>
    ),
  },
  {
    heading: "3. 개인정보의 보유 및 이용 기간",
    body: (
      <p>
        회원 탈퇴 시 개인정보는 지체 없이 파기하거나 알아볼 수 없게 바꿉니다. 다만 결제·실거래 기록 등
        관계 법령이나 분쟁 대비를 위해 보존이 필요한 기록은 정해진 기간 동안 보관합니다. 자세한 내용은
        아래 &ldquo;4. 탈퇴 시 개인정보 처리&rdquo;를 확인하세요.{" "}
        <span className="text-caution">[정확한 보유기간은 법률 검토 후 확정]</span>
      </p>
    ),
  },
  {
    heading: "4. 탈퇴 시 개인정보 처리",
    body: (
      <div className="flex flex-col gap-3">
        <p>탈퇴하면 아래와 같이 처리합니다.</p>
        <p className="font-semibold text-dracula-fg">바로 삭제하는 정보</p>
        <ul>
          <li>회사에 저장된 증권사 API 키·토큰, 결제 수단 정보(빌링키), 푸시 알림 토큰</li>
          <li>관심종목, 알림 설정과 알림 내역, 저장한 스크리너 조건, 가입 때 고른 관심 분야·이용 방식</li>
          <li>리스크 한도 설정, 감시 규칙(Watch Rule)과 그 실행 기록</li>
          <li>통합 포트폴리오에 등록한 계좌·거래 정보</li>
          <li>모의투자 계좌·주문·체결·자산 기록, Quant Lab 룰셋·백테스트·신호 기록</li>
        </ul>
        <p className="font-semibold text-dracula-fg">알아볼 수 없게 바꾸는 정보</p>
        <ul>
          <li>
            회원 정보(이메일·닉네임·비밀번호·소셜 로그인 정보)는 알아볼 수 없는 값으로 바꾸거나 지웁니다.
            아래 보관 기록과 연결하기 위한 내부 회원번호만 남습니다.
          </li>
          <li>탈퇴한 이메일이나 소셜 계정으로 다시 가입할 수 있으며, 이때는 새 계정이 됩니다.</li>
          <li>
            종목 커뮤니티에 남긴 댓글은 지우지 않습니다. 작성자는 &ldquo;탈퇴한 사용자&rdquo;로 표시됩니다.
          </li>
        </ul>
        <p className="font-semibold text-dracula-fg">5년간 보관하는 기록</p>
        <ul>
          <li>구독 결제 기록, 전략 크리에이터 수익·정산 기록 — 전자상거래법·국세기본법에 따른 보존</li>
          <li>
            증권사 연동으로 낸 실계좌 주문·체결·정산 기록과 그 주문의 리스크 점검 기록 — 체결·수수료·손익
            분쟁에 대비한 증빙
          </li>
          <li>약관·개인정보 처리 동의 기록 — 언제 무엇에 동의했는지에 대한 증빙</li>
        </ul>
        <p>
          보관 기록은 위 목적 외에는 이용하지 않습니다.{" "}
          <span className="text-caution">
            [보존 대상, 보존 기간(5년), 기간이 지난 뒤의 파기 절차는 법률 검토 후 확정]
          </span>
        </p>
        <p className="font-semibold text-dracula-fg">탈퇴 시 함께 알아둘 점</p>
        <ul>
          <li>이용 중인 구독은 탈퇴와 함께 해지되며, 남은 이용 기간은 환불되지 않습니다.</li>
          <li>
            전략 마켓에 올린 전략은 판매가 중지되고, 그 전략을 구독하던 다른 회원의 구독도 함께 끝납니다.
          </li>
          <li>
            증권사로 보낸 실주문 중 결과(체결·거부 등)를 아직 확인하고 있는 주문이 있으면 탈퇴할 수 없습니다.
            결과 확인에 필요한 API 키를 먼저 지우면 주문 기록이 맞지 않게 되기 때문입니다. 보통 1~2분 뒤에
            다시 시도하면 됩니다.
          </li>
        </ul>
      </div>
    ),
  },
  {
    heading: "5. 개인정보의 제3자 제공 및 처리위탁",
    body: (
      <ul className="list-disc pl-5 space-y-1">
        <li>결제 처리: 토스페이먼츠(PG사) — 결제 승인·정산 목적</li>
        <li>증권사 연동: 한국투자증권(KIS)·토스증권 등 — 사용자가 연동을 요청한 경우에 한해, 등록한 API 키로 주문·잔고 조회 수행</li>
        <li>
          AI 요약 기능: Anthropic PBC(해외 사업자)에 뉴스·공시 텍스트 일부를 전송해 요약을 생성합니다.
          이용자의 개인 식별 정보는 전송하지 않습니다.
        </li>
        <li>법령에 특별한 규정이 있는 경우를 제외하고 위 목적 외로 제3자에게 제공하지 않습니다</li>
      </ul>
    ),
  },
  {
    heading: "6. 쿠키의 사용",
    body: (
      <p>
        로그인 유지 및 서비스 개선을 위해 필수 쿠키를 사용합니다. 브라우저 설정에서 쿠키 저장을
        거부할 수 있으나, 이 경우 로그인이 필요한 일부 기능이 제한될 수 있습니다.
      </p>
    ),
  },
  {
    heading: "7. 이용자의 권리와 행사 방법",
    body: (
      <p>
        이용자는 언제든지 자신의 개인정보를 조회·수정할 수 있으며, 회원 탈퇴를 통해 수집·이용 동의를
        철회할 수 있습니다. 증권사 연동을 해지하면 저장된 API 키는 즉시 파기됩니다.
      </p>
    ),
  },
  {
    heading: "8. 개인정보의 안전성 확보 조치",
    body: (
      <p>
        비밀번호는 단방향 암호화하여 저장하며, 증권사 API 키 등 민감한 인증정보는 별도 암호화하여
        저장합니다. 그 외 접근 권한 관리, 전송 구간 암호화(HTTPS) 등을 적용합니다.
      </p>
    ),
  },
  {
    heading: "9. 개인정보 보호책임자",
    body: (
      <p className="text-caution">
        [운영자명], [연락처 이메일] — 사업자 등록 및 정식 운영 개시 전 확정 예정
      </p>
    ),
  },
  {
    heading: "10. 고지의 의무",
    body: (
      <p>
        이 방침의 내용은 법령·서비스 변경에 따라 수정될 수 있으며, 변경 시 서비스 내 공지사항을 통해
        고지합니다.
      </p>
    ),
  },
];

export default function PrivacyPolicyPage() {
  return (
    <LegalDoc
      doc="privacy"
      title="개인정보처리방침"
      meta="최종 수정일: 2026-10-21"
      intro={
        <p className="m-0">
          monticker(이하 &ldquo;회사&rdquo;)는 이용자의 개인정보를 중요시하며, 「개인정보 보호법」 등
          관련 법령을 준수합니다. 본 방침은 회사가 제공하는 서비스에 적용됩니다.
        </p>
      }
      sections={sections}
    />
  );
}
