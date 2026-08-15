"use client";

import { cn } from "@/lib/utils";

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
}

export function Input({ label, error, className, id, ...props }: InputProps) {
  const inputId = id ?? label?.toLowerCase().replace(/\s+/g, "-");
  return (
    <div className="flex flex-col gap-1.5">
      {label && (
        <label htmlFor={inputId} className="text-sm font-medium dark:text-dracula-fg text-gray-700">
          {label}
        </label>
      )}
      <input
        id={inputId}
        className={cn(
          "w-full rounded-lg border px-4 py-2.5 text-sm transition-all duration-150",
          "border-gray-300 bg-white text-gray-900 placeholder-gray-400 hover:border-gray-400",
          "dark:border-dracula-line dark:bg-dracula-surface dark:text-dracula-fg dark:placeholder-dracula-comment dark:hover:border-dracula-comment",
          "focus:outline-none focus:ring-2 focus:ring-dracula-purple/50 focus:border-dracula-purple",
          error && "border-red-500 dark:border-market-down focus:ring-red-500/50 focus:border-red-500",
          className
        )}
        {...props}
      />
      {error && <p className="text-xs text-red-500 dark:text-market-down">{error}</p>}
    </div>
  );
}
