import { cn } from "@/lib/utils";

type BadgeVariant = "up" | "down" | "neutral" | "info" | "purple";

interface BadgeProps {
  variant?: BadgeVariant;
  children: React.ReactNode;
  className?: string;
}

const VARIANTS: Record<BadgeVariant, string> = {
  up: "bg-[#0ecb81]/15 text-[#0ecb81] border border-[#0ecb81]/30",
  down: "bg-[#f6465d]/15 text-[#f6465d] border border-[#f6465d]/30",
  neutral: "bg-gray-100 text-gray-700 border border-gray-300 dark:bg-[#44475a] dark:text-[#f8f8f2] dark:border-[#6272a4]/30",
  info: "bg-[#8be9fd]/15 text-[#8be9fd] border border-[#8be9fd]/30",
  purple: "bg-[#bd93f9]/15 text-[#bd93f9] border border-[#bd93f9]/30",
};

export function Badge({ variant = "neutral", children, className }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium transition-colors duration-150",
        VARIANTS[variant],
        className
      )}
    >
      {children}
    </span>
  );
}
