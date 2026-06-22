"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { login, saveTokens } from "@/services/auth";
import Link from "next/link";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const tokens = await login(email, password);
      saveTokens(tokens);
      router.push("/");
    } catch (err) {
      setError(err instanceof Error ? err.message : "로그인 실패");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-[#0b0e11]">
      <div className="dark:bg-[#1e2329] dark:border-[#2b3240] bg-white p-8 rounded-xl shadow-sm border border-gray-200 w-full max-w-sm">
        <h1 className="text-2xl font-bold mb-6 text-center">로그인</h1>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <input
            type="email" value={email} onChange={e => setEmail(e.target.value)}
            placeholder="이메일" required
            className="border border-gray-300 dark:border-[#2b3240] dark:bg-[#252c3a] dark:text-[#eaecef] dark:placeholder-[#848e9c] rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <input
            type="password" value={password} onChange={e => setPassword(e.target.value)}
            placeholder="비밀번호" required
            className="border border-gray-300 dark:border-[#2b3240] dark:bg-[#252c3a] dark:text-[#eaecef] dark:placeholder-[#848e9c] rounded-lg px-4 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          {error && <p className="text-red-500 text-sm">{error}</p>}
          <button type="submit" disabled={loading}
            className="bg-blue-600 dark:bg-[#f0b90b] dark:text-black dark:hover:bg-[#d4a20a] text-white py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 font-semibold">
            {loading ? "로그인 중..." : "로그인"}
          </button>
        </form>
        <p className="mt-4 text-center text-sm text-gray-500">
          계정이 없으신가요?{" "}
          <Link href="/signup" className="text-blue-600 hover:underline">회원가입</Link>
        </p>
      </div>
    </div>
  );
}
