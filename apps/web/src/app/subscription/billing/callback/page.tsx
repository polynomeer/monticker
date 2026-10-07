"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Btn } from "@/components/terminal";
import { CenteredPage, StatusCard } from "@/components/auth/StatusCard";
import { registerBillingKey } from "@/services/billing";

type Status = "processing" | "success" | "error";

function BillingCallbackContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [status, setStatus] = useState<Status>("processing");
  const [message, setMessage] = useState("");
  const [card, setCard] = useState<{ cardCompany: string | null; cardLast4: string | null }>({
    cardCompany: null, cardLast4: null,
  });

  useEffect(() => {
    // 토스 실패 리다이렉트: ?code=...&message=...
    const failCode = searchParams.get("code");
    const failMessage = searchParams.get("message");
    if (failCode || failMessage) {
      setStatus("error");
      setMessage(failMessage ?? "자동결제 카드 등록에 실패했습니다.");
      return;
    }

    // 토스 성공 리다이렉트: ?authKey=...&customerKey=...
    const authKey = searchParams.get("authKey");
    const customerKey = searchParams.get("customerKey");
    if (!authKey || !customerKey) {
      setStatus("error");
      setMessage("잘못된 접근입니다.");
      return;
    }

    registerBillingKey(authKey, customerKey)
      .then((result) => {
        setStatus("success");
        setCard({ cardCompany: result.cardCompany, cardLast4: result.cardLast4 });
      })
      .catch((err: Error) => {
        setStatus("error");
        setMessage(err.message);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const back = (
    <Btn kind={status === "success" ? "primary" : "ghost"} size="lg" full onClick={() => router.push("/subscription")}>
      구독 페이지로 돌아가기
    </Btn>
  );

  return (
    <CenteredPage>
      {status === "processing" && (
        <StatusCard tone="pending" title="카드 등록 처리 중...">
          <p className="m-0">잠시만 기다려주세요.</p>
        </StatusCard>
      )}
      {status === "success" && (
        <StatusCard tone="ok" title="자동결제 카드 등록 완료" actions={back}>
          <p className="m-0">
            {card.cardCompany ?? "카드"} {card.cardLast4 ? `끝자리 ${card.cardLast4}` : ""}가 등록되었습니다.
          </p>
        </StatusCard>
      )}
      {status === "error" && (
        <StatusCard tone="error" title="카드 등록 실패" actions={back}>
          <p className="m-0">{message}</p>
        </StatusCard>
      )}
    </CenteredPage>
  );
}

export default function BillingCallbackPage() {
  return (
    <Suspense fallback={null}>
      <BillingCallbackContent />
    </Suspense>
  );
}
