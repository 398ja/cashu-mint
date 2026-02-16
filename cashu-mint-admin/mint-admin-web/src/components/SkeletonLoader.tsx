interface SkeletonLoaderProps {
  rows?: number;
}

export function SkeletonLoader({ rows = 5 }: SkeletonLoaderProps) {
  return (
    <div className="space-y-3 animate-pulse" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="h-10 bg-zinc-800/60 rounded-lg" />
      ))}
    </div>
  );
}
