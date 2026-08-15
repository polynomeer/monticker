"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Coins, Flask, Wrench, ChartBar, Trophy } from "@phosphor-icons/react";
import { authFetch } from "@/services/api";
import { Card } from "@/components/ui/Card";

interface RuleSet {
  id: number;
  name: string;
  description: string | null;
  version: number;
  status: string;
  fingerprint: string;
  createdAt: string;
  updatedAt: string;
}

const STATUS_LABEL: Record<string, { label: string; color: string }> = {
  DRAFT:      { label: "작성 중",        color: "text-gray-600 bg-gray-100 dark:text-dracula-comment dark:bg-dracula-line" },
  BACKTESTED: { label: "백테스트 완료", color: "text-dracula-green bg-dracula-green/10" },
  RUNNING:    { label: "운용 중",        color: "text-dracula-purple bg-dracula-purple/10" },
  ARCHIVED:   { label: "보관됨",         color: "text-gray-600 bg-gray-100 dark:text-dracula-comment dark:bg-dracula-line" },
};

export default function QuantLabPage() {
  const router = useRouter();
  const qc = useQueryClient();

  const { data: ruleSets = [], isLoading } = useQuery<RuleSet[]>({
    queryKey: ["quant", "rulesets"],
    queryFn: async () => {
      const res = await authFetch("/api/quant/rulesets");
      if (!res.ok) throw new Error("룰셋 목록 조회 실패");
      return res.json();
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async (id: number) => {
      const res = await authFetch(`/api/quant/rulesets/${id}`, { method: "DELETE" });
      if (!res.ok) throw new Error("삭제 실패");
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["quant", "rulesets"] }),
  });

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">Quant Lab</h1>
          <p className="text-sm text-gray-500 dark:text-dracula-comment mt-1">
            나만의 투자 규칙을 만들고, 검증하고, 운용하는 전략 연구소
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link
            href="/quant-lab/earnings"
            className="px-4 py-2 rounded-xl border border-dracula-green/40 text-dracula-green text-sm font-medium hover:bg-dracula-green/10 active:scale-[0.98] transition-all duration-150 inline-flex items-center gap-1.5"
          >
            <Coins size={16} weight="bold" aria-hidden /> 수익 대시보드
          </Link>
          <Link
            href="/quant-lab/builder"
            className="px-5 py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-semibold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150"
          >
            + 새 룰셋
          </Link>
        </div>
      </div>

      {/* 빈 상태 */}
      {!isLoading && ruleSets.length === 0 && (
        <div className="text-center py-24 border border-dashed border-gray-300 dark:border-dracula-line rounded-2xl">
          <div className="flex justify-center mb-4 text-gray-400 dark:text-dracula-comment"><Flask size={40} weight="duotone" aria-hidden /></div>
          <p className="text-gray-900 dark:text-dracula-fg font-semibold mb-2">아직 룰셋이 없습니다</p>
          <p className="text-gray-500 dark:text-dracula-comment text-sm mb-6">
            투자 아이디어를 조건식으로 만들고 백테스트로 검증해보세요
          </p>
          <Link
            href="/quant-lab/builder"
            className="px-6 py-2.5 rounded-xl bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-semibold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150"
          >
            첫 룰셋 만들기 →
          </Link>
        </div>
      )}

      {/* 룰셋 목록 */}
      {isLoading ? (
        <div className="space-y-3">
          {[1,2,3].map(i => (
            <div key={i} className="h-24 rounded-xl bg-gradient-to-r from-gray-200 via-gray-100 to-gray-200 dark:from-dracula-line/15 dark:via-dracula-line/35 dark:to-dracula-line/15 bg-[length:200%_100%] animate-shimmer" />
          ))}
        </div>
      ) : (
        <div className="space-y-3">
          {ruleSets.map((rs: RuleSet) => {
            const st = STATUS_LABEL[rs.status] ?? STATUS_LABEL.DRAFT;
            return (
              <Card
                key={rs.id}
                className="flex items-center justify-between p-5"
                hover
              >
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="font-semibold text-gray-900 dark:text-dracula-fg truncate">{rs.name}</span>
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${st.color}`}>
                      {st.label}
                    </span>
                    <span className="text-xs text-gray-500 dark:text-dracula-comment tabular-nums">v{rs.version}</span>
                  </div>
                  {rs.description && (
                    <p className="text-sm text-gray-500 dark:text-dracula-comment truncate">{rs.description}</p>
                  )}
                  <p className="text-xs text-gray-400 dark:text-dracula-line mt-1">
                    수정: {new Date(rs.updatedAt).toLocaleDateString("ko-KR")}
                  </p>
                </div>

                <div className="flex items-center gap-2 ml-4 shrink-0">
                  <button
                    onClick={() => router.push(`/quant-lab/${rs.id}`)}
                    className="px-3 py-1.5 rounded-lg text-xs font-medium bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg hover:bg-blue-600 dark:hover:bg-dracula-purple hover:text-white dark:hover:text-dracula-bg active:scale-95 transition-all duration-150"
                  >
                    백테스트
                  </button>
                  <button
                    onClick={() => router.push(`/quant-lab/builder?edit=${rs.id}`)}
                    className="px-3 py-1.5 rounded-lg text-xs font-medium bg-gray-100 dark:bg-dracula-line text-gray-700 dark:text-dracula-fg hover:bg-gray-200 dark:hover:bg-dracula-comment active:scale-95 transition-all duration-150"
                  >
                    수정
                  </button>
                  <button
                    onClick={() => {
                      if (confirm(`"${rs.name}" 룰셋을 삭제할까요?`)) {
                        deleteMutation.mutate(rs.id);
                      }
                    }}
                    className="px-3 py-1.5 rounded-lg text-xs font-medium text-dracula-red hover:bg-dracula-red/10 active:scale-95 transition-all duration-150"
                  >
                    삭제
                  </button>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {/* 안내 카드 */}
      <div className="mt-10 grid grid-cols-1 sm:grid-cols-3 gap-4">
        {[
          { icon: Wrench, title: "1. 룰셋 빌더", desc: "가격·거래량·RSI·MACD 조건을 조합해 나만의 매수/매도 규칙을 만듭니다" },
          { icon: ChartBar, title: "2. 백테스트", desc: "과거 캔들 데이터로 전략을 검증합니다. 수수료·슬리피지를 반영해 현실적인 성과를 계산합니다" },
          { icon: Trophy, title: "3. 신뢰도 점수", desc: "거래 횟수·기간·시장 국면을 기준으로 A~D 신뢰도를 부여해 과최적화를 경고합니다" },
        ].map(c => (
          <div key={c.title} className="p-4 rounded-xl border border-gray-200 dark:border-dracula-line bg-gray-50 dark:bg-dracula-bg">
            <div className="mb-2 text-gray-500 dark:text-dracula-comment"><c.icon size={22} weight="bold" aria-hidden /></div>
            <p className="text-sm font-semibold text-gray-900 dark:text-dracula-fg mb-1">{c.title}</p>
            <p className="text-xs text-gray-500 dark:text-dracula-comment">{c.desc}</p>
          </div>
        ))}
      </div>

      <p className="mt-6 text-xs text-gray-400 dark:text-dracula-comment text-center">
        과거 성과가 미래 수익을 보장하지 않습니다. 실제 투자 판단은 사용자 본인에게 있습니다.
      </p>
    </div>
  );
}
