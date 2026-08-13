"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

const STEPS = [
  {
    title: "monticker에 오신 것을 환영합니다",
    description: "실시간 주식 시세, AI 요약, 맞춤형 알림까지 — 한 곳에서 관리하세요.",
    icon: "📈",
  },
  {
    title: "관심 종목을 추가하세요",
    description: "삼성전자, 카카오 등 원하는 종목을 워치리스트에 담아 빠르게 확인하세요.",
    icon: "⭐",
  },
  {
    title: "가격 알림을 설정하세요",
    description: "목표가에 도달하면 즉시 푸시 알림을 받을 수 있습니다.",
    icon: "🔔",
  },
  {
    title: "Quant Lab으로 전략을 테스트하세요",
    description: "나만의 매매 조건을 설정하고 과거 데이터로 백테스트 해보세요.",
    icon: "🧪",
  },
  {
    title: "준비 완료!",
    description: "지금 바로 시장을 탐색해보세요.",
    icon: "🚀",
  },
];

export default function OnboardingPage() {
  const router = useRouter();
  const [step, setStep] = useState(0);
  const current = STEPS[step];
  const isLast = step === STEPS.length - 1;

  const next = () => {
    if (isLast) {
      localStorage.setItem("onboarding_done", "1");
      router.replace("/");
    } else {
      setStep(s => s + 1);
    }
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-[#0d1117] bg-mesh-dark text-[#f8f8f2] px-6">
      <div key={step} className="max-w-sm w-full space-y-8 text-center animate-fade-up">
        <div className="text-7xl">{current.icon}</div>
        <div className="space-y-3">
          <h1 className="text-2xl font-bold tracking-tight">{current.title}</h1>
          <p className="text-sm text-[#6272a4] leading-relaxed">{current.description}</p>
        </div>

        {/* 진행 점 */}
        <div className="flex justify-center gap-2">
          {STEPS.map((_, i) => (
            <span
              key={i}
              className={`block h-2 rounded-full transition-all duration-300 ${
                i === step ? "w-6 bg-[#bd93f9] shadow-glow-purple" : "w-2 bg-[#44475a]"
              }`}
            />
          ))}
        </div>

        <div className="flex gap-3">
          {step > 0 && (
            <button
              onClick={() => setStep(s => s - 1)}
              className="flex-1 py-3 rounded-xl border border-[#44475a] text-sm text-[#6272a4] hover:border-[#6272a4] hover:text-[#f8f8f2] active:scale-[0.98] transition-all duration-150"
            >
              이전
            </button>
          )}
          <button
            onClick={next}
            className="flex-1 py-3 rounded-xl bg-[#bd93f9] text-[#282a36] font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150"
          >
            {isLast ? "시작하기" : "다음"}
          </button>
        </div>

        {!isLast && (
          <button
            onClick={() => { localStorage.setItem("onboarding_done", "1"); router.replace("/"); }}
            className="text-xs text-[#44475a] hover:text-[#6272a4] transition-colors"
          >
            건너뛰기
          </button>
        )}
      </div>
    </div>
  );
}
