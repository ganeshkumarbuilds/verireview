import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, Check } from 'lucide-react';
import { PageHeader } from './ui';

/**
 * One visual shell for every feature page. The header, the way back to the
 * dashboard and the feature-local stepper always render in the same order, so
 * Generate/Review/Fix/Verify cannot drift apart. The stepper only lists the
 * steps of the current feature — never the other features' inputs.
 */
export function FeaturePage({
  title,
  description,
  actions,
  steps,
  current,
  children,
}: {
  title: string;
  description: string;
  actions?: ReactNode;
  steps: readonly string[];
  /** Zero-based index of the step the user is on. */
  current: number;
  children: ReactNode;
}) {
  return (
    <div className="space-y-6">
      <Link
        to="/dashboard"
        className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1 text-sm font-semibold text-indigo-700 transition-colors hover:bg-indigo-50 hover:text-indigo-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Back to Dashboard
      </Link>
      <PageHeader title={title} description={description} actions={actions} />
      <FeatureSteps steps={steps} current={current} />
      {children}
    </div>
  );
}

/** The small, feature-scoped progress trail. */
export function FeatureSteps({
  steps,
  current,
  className,
}: {
  steps: readonly string[];
  current: number;
  className?: string;
}) {
  return (
    <nav
      aria-label="Feature steps"
      className={`rounded-2xl border border-slate-200 bg-white px-4 py-3 shadow-sm ${className ?? ''}`}
    >
      <ol className="flex flex-wrap items-center gap-2">
        {steps.map((label, index) => {
          const done = index < current;
          const active = index === current;
          return (
            <li key={label} className="flex items-center gap-2">
              <span
                aria-current={active ? 'step' : undefined}
                className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold ${
                  active
                    ? 'bg-indigo-600 text-white'
                    : done
                      ? 'bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-600/20'
                      : 'bg-slate-50 text-slate-500 ring-1 ring-inset ring-slate-200'
                }`}
              >
                <span
                  aria-hidden="true"
                  className={`flex h-4 w-4 items-center justify-center rounded-full text-[10px] font-bold ${
                    active ? 'bg-white/25' : done ? 'bg-emerald-600 text-white' : 'bg-white'
                  }`}
                >
                  {done ? <Check className="h-3 w-3" /> : index + 1}
                </span>
                {label}
              </span>
              {index < steps.length - 1 && (
                <span aria-hidden="true" className="h-px w-3 bg-slate-200" />
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
