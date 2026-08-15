import { cn } from "@/lib/utils";

interface CardProps {
  children: React.ReactNode;
  className?: string;
  outerClassName?: string;
  hover?: boolean;
}

/**
 * Double-Bezel 카드 — 얇은 아우터 셸(트레이) 안에 이너 코어(글라스 패널)를 얹은 구조.
 * 평평한 사각형 대신 "가공된 하드웨어" 느낌을 준다.
 * outerClassName은 트레이(크기/정렬)에, className은 이너 패널(패딩/레이아웃)에 적용된다.
 */
export function Card({ children, className, outerClassName, hover = false }: CardProps) {
  return (
    <div className={cn("rounded-2xl bg-black/[0.03] dark:bg-white/5 ring-1 ring-black/5 dark:ring-white/10 p-1", outerClassName)}>
      <div
        className={cn(
          "rounded-xl bg-white dark:bg-dracula-surface border border-gray-100 dark:border-white/5",
          "shadow-bezel-inset-light dark:shadow-bezel-inset",
          hover &&
            "transition-all duration-300 ease-spring hover:-translate-y-0.5 hover:shadow-lg dark:hover:shadow-glow-line",
          className
        )}
      >
        {children}
      </div>
    </div>
  );
}
