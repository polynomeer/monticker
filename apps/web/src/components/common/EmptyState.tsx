import { type Icon, Package } from "@phosphor-icons/react";

interface Props {
  icon?: Icon;
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
}

export default function EmptyState({ icon: IconComponent = Package, title, description, action }: Props) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-4 text-center">
      <IconComponent size={48} weight="duotone" className="text-gray-400 dark:text-dracula-comment opacity-80" aria-hidden />
      <div className="space-y-1">
        <p className="font-semibold text-gray-900 dark:text-dracula-fg">{title}</p>
        {description && <p className="text-sm text-gray-500 dark:text-dracula-comment">{description}</p>}
      </div>
      {action && (
        <button
          onClick={action.onClick}
          className="mt-2 px-5 py-2 rounded-lg bg-blue-600 dark:bg-dracula-purple text-white dark:text-dracula-bg font-semibold text-sm hover:opacity-90 active:scale-95 transition-all duration-150"
        >
          {action.label}
        </button>
      )}
    </div>
  );
}
