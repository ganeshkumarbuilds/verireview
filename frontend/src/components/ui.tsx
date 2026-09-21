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

/**
 * Animation utilities for smooth transitions and stage animations.
 * These work with Tailwind's existing transition utilities.
 */
export const animationStyles = {
  /** Smooth pulse for active states */
  pulse: 'animate-pulse',
  /** Gentle spin for loading indicators */
  spin: 'animate-spin',
  /** Fade in from 0 to 1 opacity */
  fadeIn: 'animate-in fade-in duration-300',
  /** Fade out from 1 to 0 opacity */
  fadeOut: 'animate-out fade-out duration-200',
  /** Slide in from left */
  slideInLeft: 'animate-in slide-in-from-left duration-300',
  /** Slide in from right */
  slideInRight: 'animate-in slide-in-from-right duration-300',
  /** Slide in from top */
  slideInTop: 'animate-in slide-in-from-top duration-300',
  /** Slide in from bottom */
  slideInBottom: 'animate-in slide-in-from-bottom duration-300',
  /** Scale up from 0.95 to 1 */
  scaleIn: 'animate-in zoom-in-95 duration-200',
  /** Scale down from 1 to 0.95 */
  scaleOut: 'animate-out zoom-out-95 duration-150',
} as const;

/**
 * Stage transition class names for pipeline visualization.
 * Usage: combine base classes with state-specific classes.
 */
export const pipelineStageClasses = {
  base: 'relative flex gap-3 pb-4 last:pb-0 transition-all duration-500 ease-out',
  connector: 'absolute left-[13px] top-7 h-[calc(100%-1.5rem)] w-px transition-colors duration-500',
  icon: {
    base: 'flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold transition-all duration-300 ease-out',
    waiting: 'bg-white text-slate-400 ring-1 ring-inset ring-slate-200',
    active: 'bg-indigo-600 text-white ring-2 ring-indigo-300 shadow-lg shadow-indigo-600/30 animate-pulse',
    completed: 'bg-emerald-600 text-white ring-2 ring-emerald-300',
    failed: 'bg-red-600 text-white ring-2 ring-red-300 animate-bounce',
  },
  label: {
    base: 'min-w-0 flex-1 pt-0.5 transition-opacity duration-300',
    active: 'opacity-100',
    completed: 'opacity-100',
    waiting: 'opacity-70',
  },
} as const;

/**
 * Returns computed className for a pipeline stage icon based on state.
 */
export function getPipelineStageIconClass(state: 'waiting' | 'active' | 'completed' | 'failed'): string {
  const base = pipelineStageClasses.icon.base;
  switch (state) {
    case 'active':
      return `${base} ${pipelineStageClasses.icon.active}`;
    case 'completed':
      return `${base} ${pipelineStageClasses.icon.completed}`;
    case 'failed':
      return `${base} ${pipelineStageClasses.icon.failed}`;
    default:
      return `${base} ${pipelineStageClasses.icon.waiting}`;
  }
}

/**
 * Returns computed className for a pipeline stage label based on state.
 */
export function getPipelineStageLabelClass(state: 'waiting' | 'active' | 'completed' | 'failed'): string {
  const base = pipelineStageClasses.label.base;
  switch (state) {
    case 'active':
      return `${base} ${pipelineStageClasses.label.active}`;
    case 'completed':
      return `${base} ${pipelineStageClasses.label.completed}`;
    case 'failed':
      return `${base} opacity-100`;
    default:
      return `${base} ${pipelineStageClasses.label.waiting}`;
  }
}

/**
 * Returns computed className for a pipeline stage connector line.
 */
export function getPipelineConnectorClass(state: 'waiting' | 'active' | 'completed' | 'failed'): string {
  const base = pipelineStageClasses.connector;
  switch (state) {
    case 'active':
    case 'completed':
      return `${base} bg-emerald-200`;
    case 'failed':
      return `${base} bg-red-200`;
    default:
      return `${base} bg-indigo-100`;
  }
}
