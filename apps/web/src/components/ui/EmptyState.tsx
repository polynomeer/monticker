import { cn } from "@/lib/utils";

interface EmptyStateProps {
  icon?: string;
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}

export function EmptyState({
  icon = "📊",
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
      <span className="text-4xl mb-4 opacity-60" aria-hidden>
        {icon}
      </span>
      <p className="text-gray-900 dark:text-[#f8f8f2] font-semibold text-base mb-1">{title}</p>
      {description && (
        <p className="text-gray-500 dark:text-[#6272a4] text-sm max-w-xs">{description}</p>
      )}
      {action && <div className="mt-6">{action}</div>}
    </div>
  );
}
