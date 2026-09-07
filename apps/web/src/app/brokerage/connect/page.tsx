"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ShieldCheck, Warning } from "@phosphor-icons/react";
import { getAccessToken } from "@/services/auth";
import { useBrokerageAccount, useConnectBrokerage } from "@/hooks/useBrokerage";
import { useToast } from "@/hooks/useToast";
import { Card } from "@/components/ui/Card";
import { Input } from "@/components/ui/Input";

export default function BrokerageConnectPage() {
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [appKey, setAppKey] = useState("");
  const [appSecret, setAppSecret] = useState("");
  const [accountNumber, setAccountNumber] = useState("");
  const router = useRouter();
  const { toast } = useToast();
  const { data: account } = useBrokerageAccount();
  const connect = useConnectBrokerage();

  useEffect(() => { setIsLoggedIn(!!getAccessToken()); }, []);

  const isValid = appKey.trim().length > 0 && appSecret.trim().length > 0 && accountNumber.trim().length > 0;

  const handleSubmit = async () => {
    try {
      await connect.mutateAsync({ appKey: appKey.trim(), appSecret: appSecret.trim(), accountNumber: accountNumber.trim() });
      toast({ type: "success", title: "연동 완료", message: "증권사 계좌가 연동되었습니다." });
      router.push("/brokerage");
    } catch (e) {
      toast({ type: "error", title: "연동 실패", message: (e as Error).message });
    }
  };

  if (!isLoggedIn) return (
    <div className="max-w-3xl mx-auto p-6 text-center py-20">
      <p className="text-gray-500 dark:text-dracula-comment mb-4">실전투자를 연동하려면 로그인이 필요합니다.</p>
      <Link href="/login" className="inline-block bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg text-white px-6 py-2 rounded-lg font-medium hover:opacity-90 active:scale-[0.98] transition-all duration-150">로그인</Link>
    </div>
  );

  if (account) return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 animate-fade-up text-center">
      <Card className="p-6">
        <ShieldCheck size={28} weight="duotone" className="text-dracula-green mx-auto mb-2" aria-hidden />
        <p className="font-semibold text-gray-900 dark:text-dracula-fg">이미 계좌가 연동되어 있습니다</p>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-1">계좌번호 {account.accountNumber}</p>
        <Link href="/brokerage" className="inline-block mt-4 px-4 py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg text-sm font-semibold hover:opacity-90 active:scale-[0.98] transition-all duration-150">
          대시보드로 이동
        </Link>
      </Card>
    </div>
  );

  return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <div className="mb-8">
        <h1 className="text-xl font-bold text-gray-900 dark:text-dracula-fg">증권사 계좌 연동</h1>
        <p className="text-xs text-gray-500 dark:text-dracula-comment mt-0.5">
          한국투자증권 Open API 앱키를 입력하면 실제 계좌로 주문을 체결할 수 있습니다.
        </p>
      </div>

      <Card className="p-5 mb-5" outerClassName="mb-5">
        <div className="flex items-start gap-2.5 text-xs text-gray-500 dark:text-dracula-comment">
          <Warning size={16} weight="bold" className="text-dracula-orange shrink-0 mt-0.5" aria-hidden />
          <p>
            appKey/appSecret은 암호화되어 저장되며, 몬티커는 이 값을 이용해 사용자 본인 명의 계좌에만
            주문을 전달합니다. 발급 방법은 한국투자증권 Open API 포털의 안내를 참고하세요.
          </p>
        </div>
      </Card>

      <Card className="p-5">
        <div className="flex flex-col gap-4">
          <Input
            label="App Key"
            placeholder="한국투자증권에서 발급받은 App Key"
            value={appKey}
            onChange={e => setAppKey(e.target.value)}
            autoComplete="off"
          />
          <Input
            label="App Secret"
            type="password"
            placeholder="한국투자증권에서 발급받은 App Secret"
            value={appSecret}
            onChange={e => setAppSecret(e.target.value)}
            autoComplete="off"
          />
          <Input
            label="계좌번호"
            placeholder="숫자만 입력 (예: 1234567801)"
            value={accountNumber}
            onChange={e => setAccountNumber(e.target.value.replace(/[^0-9]/g, ""))}
            autoComplete="off"
          />
        </div>

        <button
          onClick={handleSubmit}
          disabled={!isValid || connect.isPending}
          className="w-full mt-6 py-3 rounded-xl font-bold text-sm text-white active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100 bg-blue-600 dark:bg-dracula-purple dark:text-dracula-bg"
        >
          {connect.isPending ? "연동 중..." : "계좌 연동하기"}
        </button>
      </Card>

      <p className="text-xs text-gray-500 dark:text-dracula-comment text-center mt-8">
        실제 자금이 이동하는 기능입니다. 리스크 한도 내에서만 주문이 체결됩니다.
      </p>
    </div>
  );
}
