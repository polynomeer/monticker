import type { Metadata } from "next";
import { PageLayout } from "@/components/ui/PageLayout";
import { Card } from "@/components/ui/Card";
import { Warning } from "@phosphor-icons/react/dist/ssr";

export const metadata: Metadata = {
  title: "개인정보처리방침",
};

const sections: { heading: string; body: React.ReactNode }[] = [
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
        회원 탈퇴 시 지체 없이 파기합니다. 다만 관계 법령에 따라 보존이 필요한 거래 기록(전자상거래
        등에서의 소비자보호에 관한 법률 등)은 해당 법령이 정한 기간 동안 보관합니다.{" "}
        <span className="text-dracula-orange">[정확한 보유기간은 법률 검토 후 확정]</span>
      </p>
    ),
  },
  {
    heading: "4. 개인정보의 제3자 제공 및 처리위탁",
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
    heading: "5. 쿠키의 사용",
    body: (
      <p>
        로그인 유지 및 서비스 개선을 위해 필수 쿠키를 사용합니다. 브라우저 설정에서 쿠키 저장을
        거부할 수 있으나, 이 경우 로그인이 필요한 일부 기능이 제한될 수 있습니다.
      </p>
    ),
  },
  {
    heading: "6. 이용자의 권리와 행사 방법",
    body: (
      <p>
        이용자는 언제든지 자신의 개인정보를 조회·수정할 수 있으며, 회원 탈퇴를 통해 수집·이용 동의를
        철회할 수 있습니다. 증권사 연동을 해지하면 저장된 API 키는 즉시 파기됩니다.
      </p>
    ),
  },
  {
    heading: "7. 개인정보의 안전성 확보 조치",
    body: (
      <p>
        비밀번호는 단방향 암호화하여 저장하며, 증권사 API 키 등 민감한 인증정보는 별도 암호화하여
        저장합니다. 그 외 접근 권한 관리, 전송 구간 암호화(HTTPS) 등을 적용합니다.
      </p>
    ),
  },
  {
    heading: "8. 개인정보 보호책임자",
    body: (
      <p className="text-dracula-orange">
        [운영자명], [연락처 이메일] — 사업자 등록 및 정식 운영 개시 전 확정 예정
      </p>
    ),
  },
  {
    heading: "9. 고지의 의무",
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
    <PageLayout
      title="개인정보처리방침"
      subtitle="최종 수정일: 2026-09-05"
      className="max-w-3xl"
    >
      <Card className="p-4 mb-6 flex gap-3 items-start bg-amber-50 dark:bg-dracula-orange/10 border-amber-200 dark:border-dracula-orange/30">
        <Warning size={20} weight="fill" className="text-amber-500 dark:text-dracula-orange shrink-0 mt-0.5" aria-hidden />
        <p className="text-sm text-amber-800 dark:text-dracula-orange">
          이 페이지는 초안(draft)입니다. 실제 서비스 오픈 전 법률 자문을 거쳐 확정됩니다. 주황색으로
          표시된 항목은 아직 확정되지 않은 부분입니다.
        </p>
      </Card>

      <Card className="p-6 sm:p-8">
        <div className="space-y-6 text-sm leading-relaxed text-gray-700 dark:text-dracula-fg">
          <p>
            monticker(이하 &ldquo;회사&rdquo;)는 이용자의 개인정보를 중요시하며, 「개인정보 보호법」 등
            관련 법령을 준수합니다. 본 방침은 회사가 제공하는 서비스에 적용됩니다.
          </p>
          {sections.map((section) => (
            <section key={section.heading}>
              <h2 className="font-semibold text-gray-900 dark:text-dracula-fg mb-2">{section.heading}</h2>
              <div className="text-gray-600 dark:text-dracula-comment">{section.body}</div>
            </section>
          ))}
        </div>
      </Card>
    </PageLayout>
  );
}
