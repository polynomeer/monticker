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
    <div className={cn("max-w-6xl mx-auto px-4 sm:px-6 py-6", className)}>
      {(title || subtitle) && (
        <div className="flex items-start justify-between mb-6">
          <div>
            {title && (
              <h1 className="text-2xl font-bold text-[#f8f8f2]">{title}</h1>
            )}
            {subtitle && (
              <p className="mt-1 text-sm text-[#6272a4]">{subtitle}</p>
            )}
          </div>
          {actions && <div className="flex-shrink-0">{actions}</div>}
        </div>
      )}
      {children}
    </div>
  );
}
