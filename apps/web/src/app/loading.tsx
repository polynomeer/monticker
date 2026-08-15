export default function Loading() {
  return (
    <div className="max-w-6xl mx-auto px-4 py-6 space-y-4">
      <div className="h-8 w-40 rounded-lg bg-dracula-line/30 animate-pulse" />
      <div className="h-4 w-64 rounded bg-dracula-line/20 animate-pulse" />
      <div className="space-y-3 mt-6">
        {Array.from({ length: 8 }).map((_, i) => (
          <div key={i} className="h-14 rounded-xl bg-dracula-line/20 animate-pulse" />
        ))}
      </div>
    </div>
  );
}
