"use client";

import { useState, useEffect } from "react";
import { authFetch } from "@/services/api";
import { useToast } from "@/hooks/useToast";

interface NotifPref {
  pushEnabled: boolean;
  emailEnabled: boolean;
  priceAlertPush: boolean;
  priceAlertEmail: boolean;
  newsAlertPush: boolean;
  newsAlertEmail: boolean;
  weeklyReportEmail: boolean;
}

const DEFAULT: NotifPref = {
  pushEnabled: true,
  emailEnabled: true,
  priceAlertPush: true,
  priceAlertEmail: false,
  newsAlertPush: true,
  newsAlertEmail: false,
  weeklyReportEmail: true,
};

function Toggle({ checked, onChange, label, description }: { checked: boolean; onChange: (v: boolean) => void; label: string; description?: string }) {
  return (
    <div className="flex items-center justify-between py-3 border-b border-gray-100 dark:border-[#44475a] last:border-0">
      <div>
        <p className="text-sm font-medium text-gray-900 dark:text-[#f8f8f2]">{label}</p>
        {description && <p className="text-xs text-gray-500 dark:text-[#6272a4] mt-0.5">{description}</p>}
      </div>
      <button
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative w-11 h-6 rounded-full transition-colors duration-200 ${checked ? "bg-blue-600 dark:bg-[#bd93f9]" : "bg-gray-300 dark:bg-[#44475a]"}`}
      >
        <span className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform duration-200 ${checked ? "translate-x-5" : ""}`} />
      </button>
    </div>
  );
}

export default function NotificationSettingsPage() {
  const { toast } = useToast();
  const [pref, setPref] = useState<NotifPref>(DEFAULT);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    authFetch("/api/users/me/notification-preferences")
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data) setPref({ ...DEFAULT, ...data }); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const save = async () => {
    setSaving(true);
    try {
      const res = await authFetch("/api/users/me/notification-preferences", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(pref),
      });
      if (!res.ok) throw new Error();
      toast({ type: "success", title: "저장 완료", message: "알림 설정이 저장되었습니다." });
    } catch {
      toast({ type: "error", title: "저장 실패", message: "다시 시도해주세요." });
    } finally {
      setSaving(false);
    }
  };

  const set = (key: keyof NotifPref) => (v: boolean) => setPref(p => ({ ...p, [key]: v }));

  if (loading) return <div className="p-8 text-gray-500 dark:text-[#6272a4] text-sm">로딩 중...</div>;

  return (
    <div className="max-w-lg mx-auto px-4 py-6 sm:py-8 animate-fade-up">
      <h1 className="text-xl font-bold tracking-tight text-gray-900 dark:text-[#f8f8f2] mb-6">알림 설정</h1>

      <section className="mb-6 p-5 rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line">
        <h2 className="text-sm font-semibold text-blue-600 dark:text-[#bd93f9] mb-3">전체 알림</h2>
        <Toggle checked={pref.pushEnabled}  onChange={set("pushEnabled")}  label="푸시 알림" description="모바일 앱 푸시 알림" />
        <Toggle checked={pref.emailEnabled} onChange={set("emailEnabled")} label="이메일 알림" description="이메일 수신 동의" />
      </section>

      <section className="mb-6 p-5 rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line">
        <h2 className="text-sm font-semibold text-green-600 dark:text-[#50fa7b] mb-3">가격 알림</h2>
        <Toggle checked={pref.priceAlertPush}  onChange={set("priceAlertPush")}  label="푸시" />
        <Toggle checked={pref.priceAlertEmail} onChange={set("priceAlertEmail")} label="이메일" />
      </section>

      <section className="mb-6 p-5 rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line">
        <h2 className="text-sm font-semibold text-orange-600 dark:text-[#ffb86c] mb-3">뉴스·공시 알림</h2>
        <Toggle checked={pref.newsAlertPush}  onChange={set("newsAlertPush")}  label="푸시" />
        <Toggle checked={pref.newsAlertEmail} onChange={set("newsAlertEmail")} label="이메일" />
      </section>

      <section className="mb-8 p-5 rounded-xl border border-gray-200 dark:border-[#44475a] bg-white dark:bg-[#21222c] shadow-sm dark:shadow-glow-line">
        <h2 className="text-sm font-semibold text-cyan-600 dark:text-[#8be9fd] mb-3">리포트</h2>
        <Toggle checked={pref.weeklyReportEmail} onChange={set("weeklyReportEmail")} label="주간 리포트 이메일" description="매주 월요일 발송" />
      </section>

      <button
        onClick={save}
        disabled={saving}
        className="w-full py-3 rounded-xl bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] font-bold text-sm hover:opacity-90 active:scale-[0.98] transition-all duration-150 disabled:opacity-40 disabled:active:scale-100"
      >
        {saving ? "저장 중..." : "저장"}
      </button>
    </div>
  );
}
