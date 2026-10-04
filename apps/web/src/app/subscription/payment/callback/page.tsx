"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { CheckCircle, XCircle, HourglassMedium } from "@phosphor-icons/react";
import { Card } from "@/components/ui/Card";
import { confirmPayment } from "@/services/payment";

type Status = "processing" | "success" | "error";

/**
 * 일회성 구독 결제의 토스 리다이렉트 착지점 (ADR-059).
 *
 * 성공: `?paymentKey=...&orderId=...&amount=...`
 * 실패: `?code=...&message=...`
 *
 * `amount`도 쿼리로 오지만 **의도적으로 쓰지 않는다.** 금액은 서버가 prepare 시점에 정해
 * 기록에 박아둔 값이고, confirm은 orderId로 그 기록을 찾아 자기 금액을 PG에 보낸다.
 * 주소창의 값을 승인 금액으로 쓰면 그게 곧 결제 우회다 — 과거에 실제로 그랬다.
 */
function PaymentCallbackContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [status, setStatus] = useState<Status>("processing");
  const [message, setMessage] = useState("");
  // React 18 StrictMode는 개발 중 effect를 두 번 실행한다. 승인은 서버가 멱등하게
  // 처리하지만(같은 orderId → 첫 결과 재생), 불필요한 왕복을 만들지는 않는다.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    // 토스 실패 리다이렉트 — 사용자가 창을 닫은 경우도 여기로 온다.
    const failCode = searchParams.get("code");
    const failMessage = searchParams.get("message");
    if (failCode || failMessage) {
      setStatus("error");
      setMessage(failMessage ?? "결제가 완료되지 않았습니다.");
      return;
    }

    const paymentKey = searchParams.get("paymentKey");
    const orderId = searchParams.get("orderId");
    if (!paymentKey || !orderId) {
      setStatus("error");
      setMessage("잘못된 접근입니다.");
      return;
    }

    confirmPayment(paymentKey, orderId)
      .then((result) => {
        if (result.success) {
          setStatus("success");
        } else {
          setStatus("error");
          setMessage(result.message ?? "결제 승인에 실패했습니다.");
        }
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
            <h1 className="text-lg font-bold text-gray-900 dark:text-dracula-fg mb-1">결제 승인 처리 중...</h1>
            {/* 이 화면에서 나가면 결제는 이미 승인됐을 수 있다 — 떠나지 말라고 분명히 말한다. */}
            <p className="text-sm text-gray-500 dark:text-dracula-comment">
              창을 닫지 말고 잠시만 기다려주세요.
            </p>
          </>
        )}
        {status === "success" && (
          <>
            <div className="flex justify-center mb-4 text-dracula-green">
              <CheckCircle size={40} weight="duotone" aria-hidden />
            </div>
            <h1 className="text-lg font-bold text-gray-900 dark:text-dracula-fg mb-1">결제 완료</h1>
            <p className="text-sm text-gray-500 dark:text-dracula-comment mb-6">
              구독이 활성화되었습니다.
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
            <h1 className="text-lg font-bold text-gray-900 dark:text-dracula-fg mb-1">결제 실패</h1>
            <p className="text-sm text-gray-500 dark:text-dracula-comment mb-4">{message}</p>
            {/* 승인 단계에서 실패하면 "돈은 빠졌는데 구독은 없는" 상태일 수 있다. 서버가
                6시간 주기로 PG에 되물어 정리하지만(ADR-059), 사용자가 그걸 알 수는 없다. */}
            <p className="text-xs text-gray-400 dark:text-dracula-line mb-6">
              결제가 이루어졌다면 자동으로 확인해 구독에 반영됩니다. 결제 내역에서 상태를 확인할 수 있어요.
            </p>
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

export default function PaymentCallbackPage() {
  return (
    <Suspense fallback={null}>
      <PaymentCallbackContent />
    </Suspense>
  );
}
