interface Props {
  icon?: string;
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
}

export default function EmptyState({ icon = "📭", title, description, action }: Props) {
  return (
    <div className="flex flex-col items-center justify-center py-20 gap-4 text-center">
      <span className="text-5xl">{icon}</span>
      <div className="space-y-1">
        <p className="font-semibold text-[#f8f8f2]">{title}</p>
        {description && <p className="text-sm text-[#6272a4]">{description}</p>}
      </div>
      {action && (
        <button
          onClick={action.onClick}
          className="mt-2 px-5 py-2 rounded-lg bg-[#bd93f9] text-[#282a36] font-semibold text-sm hover:bg-[#ff79c6] transition-colors"
        >
          {action.label}
        </button>
      )}
    </div>
  );
}
