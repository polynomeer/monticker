import Link from "next/link";

export default function NotFound() {
  return (
    <div className="min-h-[80vh] flex flex-col items-center justify-center gap-6 text-center px-4">
      <p className="text-6xl font-bold text-[#bd93f9]">404</p>
      <div className="space-y-2">
        <h1 className="text-xl font-bold dark:text-[#f8f8f2]">페이지를 찾을 수 없습니다</h1>
        <p className="text-sm dark:text-[#6272a4] text-gray-500">
          요청하신 페이지가 존재하지 않거나 이동되었습니다.
        </p>
      </div>
      <Link
        href="/"
        className="px-5 py-2 rounded-lg bg-[#bd93f9] text-[#282a36] font-semibold text-sm hover:bg-[#ff79c6] transition-colors"
      >
        홈으로 돌아가기
      </Link>
    </div>
  );
}
