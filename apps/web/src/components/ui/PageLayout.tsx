import { cn } from "@/lib/utils";

interface PageLayoutProps {
  title?: string;
  subtitle?: string;
  children: React.ReactNode;
  className?: string;
  actions?: React.ReactNode;
}

export function PageLayout({
  title,
  subtitle,
  children,
  className,
  actions,
}: PageLayoutProps) {
  return (
    <div className={cn("max-w-6xl mx-auto px-4 sm:px-6 py-6 sm:py-8 animate-fade-up", className)}>
      {(title || subtitle) && (
        <div className="flex items-start justify-between gap-4 mb-6 sm:mb-8">
          <div>
            {title && (
              <h1 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-dracula-fg">{title}</h1>
            )}
            {subtitle && (
              <p className="mt-1.5 text-sm text-gray-500 dark:text-dracula-comment">{subtitle}</p>
            )}
          </div>
          {actions && <div className="flex-shrink-0">{actions}</div>}
        </div>
      )}
      {children}
    </div>
  );
}
