import type { Metadata } from "next";
import { PageLayout } from "@/components/ui/PageLayout";
import { Card } from "@/components/ui/Card";
import { Warning } from "@phosphor-icons/react/dist/ssr";

export const metadata: Metadata = {
  title: "이용약관",
};

const sections: { heading: string; body: React.ReactNode }[] = [
  {
    heading: "제1조 (목적)",
    body: (
      <p>
        이 약관은 monticker(이하 &ldquo;회사&rdquo;)가 제공하는 이벤트 중심 주식 관찰·모의투자·Quant
        Lab·전략 마켓 서비스(이하 &ldquo;서비스&rdquo;)의 이용과 관련하여 회사와 이용자의 권리·의무
        및 책임사항을 정함을 목적으로 합니다.
      </p>
    ),
  },
  {
    heading: "제2조 (정의)",
    body: (
      <ul className="list-disc pl-5 space-y-1">
        <li>&ldquo;모의투자&rdquo;란 가상의 자금으로 실제 시장 데이터를 기반으로 매매를 체험하는 서비스를 말합니다.</li>
        <li>&ldquo;증권사 연동&rdquo;이란 이용자가 본인 명의로 개설한 증권사 계좌의 Open API 키를 등록하여, 회사가 그 키로 이용자를 대신해 증권사 API를 호출하는 것을 말합니다.</li>
        <li>&ldquo;전략 마켓&rdquo;이란 이용자가 만든 검증된 룰셋의 신호를 다른 이용자가 구독할 수 있는 서비스를 말합니다.</li>
      </ul>
    ),
  },
  {
    heading: "제3조 (약관의 효력 및 변경)",
    body: (
      <p>
        회사는 관련 법령을 위반하지 않는 범위에서 약관을 변경할 수 있으며, 변경 시 서비스 내
        공지사항을 통해 사전 고지합니다.
      </p>
    ),
  },
  {
    heading: "제4조 (회원가입 및 계정)",
    body: (
      <p>
        이용자는 회사가 정한 가입 양식에 따라 회원가입을 신청하며, 이메일 등 기재사항은
        정확한 사실에 근거해야 합니다. 타인 명의 도용, 허위 정보 기재로 인한 불이익은 이용자
        본인이 부담합니다.
      </p>
    ),
  },
  {
    heading: "제5조 (모의투자 서비스의 성격)",
    body: (
      <p>
        모의투자는 가상의 자금으로 이루어지며, 실제 금전적 손익과 무관합니다. 서비스가 제공하는
        백테스트·포워드 테스트·리스크 지표·전략 신호 등은 투자 판단을 돕기 위한 도구이며, 특정
        종목의 매수·매도를 권유하거나 수익을 보장하는 것이 아닙니다. 과거 성과 또는 모의 운용
        결과는 미래 수익을 보장하지 않으며, 실제 투자 판단과 그 결과에 대한 책임은 전적으로
        이용자 본인에게 있습니다.
      </p>
    ),
  },
  {
    heading: "제6조 (증권사 연동 서비스)",
    body: (
      <ul className="list-disc pl-5 space-y-1">
        <li>회사는 자체 브로커(투자중개업자) 라이선스를 보유하지 않습니다.</li>
        <li>증권사 연동은 이용자가 본인 명의로 개설한 증권사 계좌의 API 키를 직접 등록하는 방식으로만 제공되며, 회사는 그 계좌의 자금을 보관·예치하지 않습니다.</li>
        <li>연동된 계좌를 통한 주문 실행 결과(체결가, 체결 여부, 수수료 등)는 해당 증권사의 처리 결과를 따르며, 회사는 증권사의 시스템 장애·지연에 대해 책임지지 않습니다.</li>
        <li>이용자는 언제든지 연동을 해지할 수 있으며, 해지 시 등록된 API 키는 즉시 파기됩니다.</li>
      </ul>
    ),
  },
  {
    heading: "제7조 (유료 서비스 및 결제)",
    body: (
      <p>
        구독형 유료 서비스 및 전략 마켓 결제는 회사가 지정한 전자결제대행사(PG사)를 통해
        처리됩니다. 결제·환불·정기결제 해지 절차는{" "}
        <span className="text-dracula-orange">[구독 관리 화면 경로 — 확정 후 기재]</span>에서 확인할
        수 있습니다.
      </p>
    ),
  },
  {
    heading: "제8조 (환불 정책)",
    body: (
      <p className="text-dracula-orange">
        [전자상거래법 등 관련 법령에 따른 청약철회·환불 기준은 법률 검토 후 확정합니다]
      </p>
    ),
  },
  {
    heading: "제9조 (전략 마켓 및 지식재산권)",
    body: (
      <p>
        이용자가 등록한 룰셋(전략의 조건식)은 서버 사이드에서만 실행되며 원문이 구매자 또는
        제3자에게 공개되지 않습니다. 전략 마켓 판매자는 수익률 보장, 손실 보전 등을 약속하는
        표현을 사용할 수 없으며, 모든 전략 카드에는 &ldquo;과거 성과가 미래 수익을 보장하지
        않으며, 투자 판단과 책임은 이용자 본인에게 있다&rdquo;는 고지가 함께 표시됩니다.
      </p>
    ),
  },
  {
    heading: "제10조 (금지행위)",
    body: (
      <ul className="list-disc pl-5 space-y-1">
        <li>타인의 계정·증권사 API 키를 무단으로 이용하는 행위</li>
        <li>서비스를 이용해 허위 정보나 시세조종성 정보를 유포하는 행위</li>
        <li>전략 마켓의 룰셋을 리버스 엔지니어링하거나 무단 복제하는 행위</li>
      </ul>
    ),
  },
  {
    heading: "제11조 (면책조항)",
    body: (
      <p>
        회사는 천재지변, 증권사·PG사 등 외부 시스템의 장애, 이용자의 귀책사유로 인한 손해에
        대해 책임을 지지 않습니다. 회사는 투자자문업자가 아니며, 서비스 내 정보는 투자 조언에
        해당하지 않습니다.
      </p>
    ),
  },
  {
    heading: "제12조 (준거법 및 관할)",
    body: (
      <p className="text-dracula-orange">
        이 약관은 대한민국 법령을 준거법으로 하며, 분쟁 발생 시 관할 법원은 [사업자 등록 및 법률
        검토 후 확정]으로 합니다.
      </p>
    ),
  },
];

export default function TermsOfServicePage() {
  return (
    <PageLayout
      title="이용약관"
      subtitle="최종 수정일: 2026-09-05 · 시행일: 미정 (법률 검토 후 확정)"
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
