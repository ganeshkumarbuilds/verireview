import type { ReactNode } from 'react';

/** Shared button/input class tokens for a consistent professional look. */
export const primaryButtonClass =
  'inline-flex items-center justify-center gap-1.5 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm shadow-indigo-200 transition-colors hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60';

export const secondaryButtonClass =
  'inline-flex items-center justify-center gap-1.5 rounded-lg border border-indigo-200 bg-white px-4 py-2 text-sm font-semibold text-indigo-700 shadow-sm transition-colors hover:bg-indigo-50 hover:border-indigo-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60';

export const quietButtonClass =
  'inline-flex items-center justify-center gap-1 rounded-lg px-3 py-1.5 text-sm font-medium text-indigo-700 transition-colors hover:bg-indigo-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60';

export const inputClass =
  'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 transition-colors hover:border-indigo-300 focus:border-indigo-600 focus:outline-none focus:ring-2 focus:ring-indigo-600/30';

export const selectClass =
  'rounded-lg border border-slate-300 bg-white px-2 py-1.5 text-sm text-slate-900 transition-colors hover:border-indigo-300 focus:border-indigo-600 focus:outline-none focus:ring-2 focus:ring-indigo-600/30';

/** Minimal card for the design system. */
export function Card({
  title,
  subtitle,
  actions,
  children,
}: {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="rounded-2xl border border-indigo-100 bg-white p-5 shadow-sm shadow-indigo-100">
      <div className="mb-3 flex flex-wrap items-start gap-2">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold tracking-tight text-indigo-950">{title}</h2>
          {subtitle && <p className="mt-0.5 text-xs text-slate-500">{subtitle}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>
      <div className="text-sm text-slate-600">{children}</div>
    </section>
  );
}

/** Page header with clear visual hierarchy: title, description, actions. */
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start gap-3">
      <div className="min-w-0 flex-1">
        <h1 className="text-2xl font-bold tracking-tight text-indigo-950">{title}</h1>
        {description && <p className="mt-1 max-w-2xl text-sm leading-relaxed text-slate-600">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** Key metric for dashboard stat cards. */
export function Stat({
  label,
  value,
  hint,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
}) {
  return (
    <div>
      <p className="text-xs font-semibold uppercase tracking-wider text-indigo-400">{label}</p>
      <p className="mt-1 text-3xl font-bold tracking-tight text-indigo-950">{value}</p>
      {hint && <div className="mt-1 text-sm text-slate-600">{hint}</div>}
    </div>
  );
}

type BadgeTone = 'gray' | 'green' | 'red' | 'amber' | 'blue' | 'violet';

const TONES: Record<BadgeTone, string> = {
  gray: 'bg-slate-100 text-slate-600 ring-slate-600/20',
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-600/25',
  red: 'bg-red-50 text-red-700 ring-red-600/25',
  amber: 'bg-amber-50 text-amber-700 ring-amber-600/25',
  blue: 'bg-sky-50 text-sky-700 ring-sky-600/25',
  violet: 'bg-violet-50 text-violet-700 ring-violet-600/25',
};

/** Status/severity/source badge. Tones mirror the backend finding enums so
 *  later phases can map DETERMINISTIC/AI/VERIFIED and severities directly. */
export function Badge({ tone = 'gray', children }: { tone?: BadgeTone; children: ReactNode }) {
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset ${TONES[tone]}`}
    >
      {children}
    </span>
  );
}

/** Accessible error banner with optional retry (wired to existing reloads). */
export function ErrorAlert({
  message,
  onRetry,
  retryLabel = 'Retry',
}: {
  message: string;
  onRetry?: () => void;
  retryLabel?: string;
}) {
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      <p className="min-w-0 flex-1">{message}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="shrink-0 rounded-lg border border-red-300 bg-white px-3 py-1.5 text-sm font-semibold text-red-700 transition-colors hover:bg-red-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-600 focus-visible:ring-offset-2"
        >
          {retryLabel}
        </button>
      )}
    </div>
  );
}

/** Loading indicator with spinner; label text stays screen-reader friendly. */
export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <p role="status" className="flex items-center gap-2.5 py-4 text-sm text-slate-600">
      <span
        aria-hidden="true"
        className="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-indigo-200 border-t-indigo-600"
      />
      {label}
    </p>
  );
}

/** Skeleton rows for list loading states (visual only, no content shift). */
export function SkeletonList({ rows = 3 }: { rows?: number }) {
  return (
    <div aria-hidden="true" className="space-y-2 py-2">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="h-9 animate-pulse rounded-lg bg-indigo-50" />
      ))}
    </div>
  );
}

/** Friendly empty state with optional action (existing links/buttons). */
export function EmptyState({
  title,
  body,
  action,
}: {
  title: string;
  body?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="rounded-xl border border-dashed border-indigo-200 bg-indigo-50/50 px-4 py-8 text-center">
      <p className="text-sm font-semibold text-indigo-950">{title}</p>
      {body && <div className="mx-auto mt-1 max-w-md text-sm leading-relaxed text-slate-600">{body}</div>}
      {action && <div className="mt-4 flex items-center justify-center gap-2">{action}</div>}
    </div>
  );
}
