import { cx } from "../lib/cx";

/** A single shimmering block. Compose several to build a skeleton screen. */
export function Skeleton({ className }: { className?: string }) {
  return <div className={cx("skeleton", className)} aria-hidden="true" />;
}

/** Skeleton mirroring the shape of a JobCard, shown while results load. */
export function JobCardSkeleton({ index = 0 }: { index?: number }) {
  return (
    <div
      className="card-surface animate-fade-in p-4"
      style={{ animationDelay: `${index * 60}ms` }}
      aria-hidden="true"
    >
      <div className="flex items-start gap-3.5">
        <Skeleton className="h-[54px] w-[54px] rounded-full" />
        <div className="flex-1 space-y-2 pt-1">
          <Skeleton className="h-4 w-3/4 rounded-md" />
          <Skeleton className="h-3.5 w-1/3 rounded-md" />
          <Skeleton className="h-3 w-1/2 rounded-md" />
        </div>
      </div>
      <div className="mt-3 flex gap-2">
        <Skeleton className="h-6 w-32 rounded-full" />
        <Skeleton className="h-6 w-24 rounded-full" />
      </div>
      <div className="mt-4 flex items-center justify-between">
        <Skeleton className="h-3.5 w-20 rounded-md" />
        <div className="flex gap-2">
          <Skeleton className="h-9 w-20 rounded-xl" />
          <Skeleton className="h-9 w-16 rounded-xl" />
        </div>
      </div>
    </div>
  );
}

/** A list of card skeletons used as the loading state for any job list. */
export function JobListSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div className="space-y-3" role="status" aria-label="Loading opportunities">
      {Array.from({ length: count }).map((_, i) => (
        <JobCardSkeleton key={i} index={i} />
      ))}
    </div>
  );
}
