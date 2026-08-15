import { cn } from "@/lib/utils";

type BadgeVariant = "up" | "down" | "neutral" | "info" | "purple";

interface BadgeProps {
  variant?: BadgeVariant;
  children: React.ReactNode;
  className?: string;
}

const VARIANTS: Record<BadgeVariant, string> = {
  up: "bg-market-up/15 text-market-up border border-market-up/30",
  down: "bg-market-down/15 text-market-down border border-market-down/30",
  neutral: "bg-gray-100 text-gray-700 border border-gray-300 dark:bg-dracula-line dark:text-dracula-fg dark:border-dracula-comment/30",
  info: "bg-dracula-cyan/15 text-dracula-cyan border border-dracula-cyan/30",
  purple: "bg-dracula-purple/15 text-dracula-purple border border-dracula-purple/30",
};

export function Badge({ variant = "neutral", children, className }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center px-2 py-0.5 rounded text-xs font-medium transition-colors duration-300 ease-spring",
        VARIANTS[variant],
        className
      )}
    >
      {children}
    </span>
  );
}
