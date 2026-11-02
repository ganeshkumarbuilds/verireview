import type { ReactNode } from 'react';

export type AgentStepState = 'waiting' | 'running' | 'completed' | 'failed' | 'skipped';

export interface AgentStep {
  key: string;
  label: string;
  state: AgentStepState;
  startedAt: string | null;
  finishedAt: string | null;
  currentActivity: string | null;
  activityLog?: string | null;
  error: string | null;
  filesWritten?: number | null;
  filesPlanned?: number | null;
  detail?: ReactNode;
}

interface AgentPipelineProps {
  steps: AgentStep[];
  now: number;
  onRetry?: (step: AgentStep) => void;
}

function elapsed(startedAt: string | null, finishedAt: string | null, now: number): string {
  if (!startedAt) return 'Not started';
  const start = Date.parse(startedAt);
  const end = finishedAt ? Date.parse(finishedAt) : now;
  if (!Number.isFinite(start) || !Number.isFinite(end)) return 'Elapsed unavailable';
  const seconds = Math.max(0, Math.floor((end - start) / 1000));
  return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`;
}

const labels: Record<AgentStepState, string> = {
  waiting: 'Waiting',
  running: 'Running',
  completed: 'Completed',
  failed: 'Failed',
  skipped: 'Skipped',
};

export function AgentPipeline({ steps, now, onRetry }: AgentPipelineProps) {
  return (
    <ol aria-label="Agent pipeline" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
      {steps.map((step, index) => {
        const hasProgress = step.key === 'coding'
          && Number.isFinite(step.filesWritten)
          && Number.isFinite(step.filesPlanned)
          && (step.filesPlanned ?? 0) > 0;
        const progress = hasProgress
          ? Math.min(100, Math.round(((step.filesWritten ?? 0) / (step.filesPlanned ?? 1)) * 100))
          : null;
        return (
          <li
            key={step.key}
            className={`rounded-xl border p-3 transition-colors duration-200 motion-reduce:transition-none ${
              step.state === 'running' ? 'border-indigo-300 bg-indigo-50/40' : 'border-slate-200'
            }`}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm font-semibold text-slate-900">{index + 1}. {step.label}</span>
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700">
                {labels[step.state]}
              </span>
            </div>
            <p className="mt-2 text-xs text-slate-600">{step.detail}</p>
            <p className="mt-2 text-[11px] text-slate-500">{elapsed(step.startedAt, step.finishedAt, now)}</p>
            {step.currentActivity && (
              <p className="mt-2 truncate text-xs text-indigo-700" aria-live="polite">
                {step.currentActivity}
              </p>
            )}
            {step.activityLog && (
              <ul className="mt-2 max-h-16 space-y-1 overflow-auto rounded bg-slate-50 p-2 text-[11px] text-slate-600" aria-label={`${step.label} activity log`}>
                {step.activityLog.split(/\r?\n/).filter(Boolean).map((activity, activityIndex) => (
                  <li key={`${step.key}-activity-${activityIndex}`} className="break-words">
                    <span className="mr-1 text-indigo-400">›</span>{activity}
                  </li>
                ))}
              </ul>
            )}
            {step.key === 'coding' && (
              <div className="mt-3" aria-label="Coding progress">
                <div className="h-1.5 overflow-hidden rounded-full bg-slate-200">
                  {progress === null ? (
                    <span className="block h-full w-1/3 animate-[pipeline-sweep_1.4s_ease-in-out_infinite] rounded-full bg-indigo-500 motion-reduce:animate-none" />
                  ) : (
                    <span
                      className="block h-full rounded-full bg-indigo-600 transition-[width] duration-200 motion-reduce:transition-none"
                      style={{ width: `${progress}%` }}
                    />
                  )}
                </div>
                <p className="mt-1 text-[11px] text-slate-500">
                  {progress === null
                    ? 'Progress updating'
                    : `${step.filesWritten} of ${step.filesPlanned} files`}
                </p>
              </div>
            )}
            {step.state === 'skipped' && (
              <p className="mt-2 text-xs font-medium text-slate-600">Not verified</p>
            )}
            {step.state === 'failed' && (
              <div className="mt-3" role="alert">
                <p className="text-xs font-medium text-red-700">{step.error ?? 'This stage failed.'}</p>
                {onRetry && (
                  <button
                    type="button"
                    className="mt-2 rounded-lg border border-red-300 bg-white px-2.5 py-1 text-xs font-semibold text-red-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-600"
                    onClick={() => onRetry(step)}
                  >
                    Retry {step.label}
                  </button>
                )}
              </div>
            )}
          </li>
        );
      })}
    </ol>
  );
}
