import { type Icon, ChartBar } from "@phosphor-icons/react";
import { cn } from "@/lib/utils";

interface EmptyStateProps {
  icon?: Icon;
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}

export function EmptyState({
  icon: IconComponent = ChartBar,
  title,
  description,
  action,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center py-16 px-4 text-center",
        className
      )}
    >
      <IconComponent size={40} weight="duotone" className="mb-4 text-gray-400 dark:text-dracula-comment opacity-80" aria-hidden />
      <p className="text-gray-900 dark:text-dracula-fg font-semibold text-base mb-1">{title}</p>
      {description && (
        <p className="text-gray-500 dark:text-dracula-comment text-sm max-w-xs">{description}</p>
      )}
      {action && <div className="mt-6">{action}</div>}
    </div>
  );
}
