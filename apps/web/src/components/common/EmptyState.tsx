interface Props {
  icon?: string;
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
}

export default function EmptyState({ icon = "📭", title, description, action }: Props) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-4 text-center">
      <span className="text-5xl opacity-80">{icon}</span>
      <div className="space-y-1">
        <p className="font-semibold text-gray-900 dark:text-[#f8f8f2]">{title}</p>
        {description && <p className="text-sm text-gray-500 dark:text-[#6272a4]">{description}</p>}
      </div>
      {action && (
        <button
          onClick={action.onClick}
          className="mt-2 px-5 py-2 rounded-lg bg-blue-600 dark:bg-[#bd93f9] text-white dark:text-[#282a36] font-semibold text-sm hover:opacity-90 active:scale-95 transition-all duration-150"
        >
          {action.label}
        </button>
      )}
    </div>
  );
}
