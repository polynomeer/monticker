"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { CheckCircle, XCircle, HourglassMedium } from "@phosphor-icons/react";
import { Card } from "@/components/ui/Card";
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

  return (
    <div className="min-h-[60vh] flex items-center justify-center px-4">
      <Card className="p-8 text-center" outerClassName="w-full max-w-sm animate-fade-up">
        {status === "processing" && (
          <>
            <div className="flex justify-center mb-4 text-dracula-comment">
              <HourglassMedium size={40} weight="duotone" aria-hidden />
            </div>
            <h1 className="text-lg font-bold text-gray-900 dark:text-dracula-fg mb-1">카드 등록 처리 중...</h1>
            <p className="text-sm text-gray-500 dark:text-dracula-comment">잠시만 기다려주세요.</p>
          </>
        )}
        {status === "success" && (
          <>
            <div className="flex justify-center mb-4 text-dracula-green">
              <CheckCircle size={40} weight="duotone" aria-hidden />
            </div>
            <h1 className="text-lg font-bold text-gray-900 dark:text-dracula-fg mb-1">자동결제 카드 등록 완료</h1>
            <p className="text-sm text-gray-500 dark:text-dracula-comment mb-6">
              {card.cardCompany ?? "카드"} {card.cardLast4 ? `끝자리 ${card.cardLast4}` : ""}가 등록되었습니다.
            </p>
            <button
              onClick={() => router.push("/subscription")}
              className="w-full bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg py-2 rounded-lg font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150"
            >
              구독 페이지로 돌아가기
            </button>
          </>
        )}
        {status === "error" && (
          <>
            <div className="flex justify-center mb-4 text-dracula-red">
              <XCircle size={40} weight="duotone" aria-hidden />
            </div>
            <h1 className="text-lg font-bold text-gray-900 dark:text-dracula-fg mb-1">카드 등록 실패</h1>
            <p className="text-sm text-gray-500 dark:text-dracula-comment mb-6">{message}</p>
            <button
              onClick={() => router.push("/subscription")}
              className="w-full border border-gray-300 dark:border-dracula-line text-gray-700 dark:text-dracula-fg py-2 rounded-lg font-semibold hover:border-gray-400 dark:hover:border-dracula-comment active:scale-[0.98] transition-all duration-150"
            >
              구독 페이지로 돌아가기
            </button>
          </>
        )}
      </Card>
    </div>
  );
}

export default function BillingCallbackPage() {
  return (
    <Suspense fallback={null}>
      <BillingCallbackContent />
    </Suspense>
  );
}
