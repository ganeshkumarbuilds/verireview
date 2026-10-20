import type { PipelineStageResponse, PipelineStageState } from '../api/types';

export interface AgentPipelineViewProps {
  stages: PipelineStageResponse[];
  /** Retry a failed stage (e.g. re-run the fix). */
  onRetry?: (stage: PipelineStageResponse) => void;
  /** Compact mode: single-line rows, no detail line. */
  compact?: boolean;
  className?: string;
}

const DOT: Record<PipelineStageState, string> = {
  WAITING: 'bg-white text-slate-400 ring-1 ring-inset ring-slate-200',
  RUNNING: 'bg-indigo-600 text-white ring-2 ring-indigo-300',
  COMPLETED: 'bg-emerald-600 text-white ring-2 ring-emerald-300',
  FAILED: 'bg-red-600 text-white ring-2 ring-red-300',
  SKIPPED: 'bg-slate-200 text-slate-500 ring-1 ring-inset ring-slate-300',
};

const CONNECTOR: Record<PipelineStageState, string> = {
  WAITING: 'bg-slate-200',
  RUNNING: 'bg-indigo-200',
  COMPLETED: 'bg-emerald-200',
  FAILED: 'bg-red-200',
  SKIPPED: 'bg-slate-200',
};

const STATE_LABEL: Record<PipelineStageState, string> = {
  WAITING: 'Waiting',
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  SKIPPED: 'Skipped',
};

const STATE_BADGE: Record<PipelineStageState, string> = {
  WAITING: 'bg-slate-100 text-slate-600 ring-slate-600/20',
  RUNNING: 'bg-sky-50 text-sky-700 ring-sky-600/25',
  COMPLETED: 'bg-emerald-50 text-emerald-700 ring-emerald-600/25',
  FAILED: 'bg-red-50 text-red-700 ring-red-600/25',
  SKIPPED: 'bg-slate-100 text-slate-500 ring-slate-500/20',
};

/** Formats an ISO instant as a short local time. Never throws on bad input. */
export function formatStageTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/** Formats a millisecond duration as e.g. "1.2s" or "340ms". */
export function formatDuration(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function StageIcon({ state, index }: { state: PipelineStageState; index: number }) {
  if (state === 'COMPLETED') return <>✓</>;
  if (state === 'FAILED') return <>!</>;
  if (state === 'SKIPPED') return <>–</>;
  if (state === 'RUNNING') {
    return (
      <span
        aria-hidden="true"
        className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white"
      />
    );
  }
  return <>{index + 1}</>;
}

/**
 * Visible agent pipeline. Every stage state comes from real backend records
 * (agent executions / check runs). A failed stage shows the exact error message
 * and a Retry action; a skipped stage (sandbox unavailable) is never shown as
 * a success.
 */
export function AgentPipelineView({
  stages,
  onRetry,
  compact = false,
  className = '',
}: AgentPipelineViewProps) {
  if (!stages || stages.length === 0) {
    return <p className="py-2 text-sm text-slate-500">No pipeline data yet.</p>;
  }
  return (
    <ol
      aria-label="Agent pipeline"
      className={`space-y-0 ${className}`}
    >
      {stages.map((stage, index) => {
        const state: PipelineStageState = stage.state ?? 'WAITING';
        const isLast = index === stages.length - 1;
        const running = state === 'RUNNING';
        return (
          <li key={stage.key} className="relative flex gap-3 pb-3 last:pb-0">
            {!isLast && (
              <span
                aria-hidden="true"
                className={`absolute left-[13px] top-7 h-[calc(100%-1rem)] w-px ${CONNECTOR[state]}`}
              />
            )}
            <span
              data-testid={`stage-icon-${stage.key}`}
              className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold ${DOT[state]} ${
                running ? 'animate-pulse' : ''
              }`}
            >
              <StageIcon state={state} index={index} />
            </span>

            <div className="min-w-0 flex-1 pt-0.5">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-semibold text-indigo-950">{stage.label}</span>
                <span
                  data-testid={`stage-state-${stage.key}`}
                  className={`inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset ${STATE_BADGE[state]}`}
                >
                  {STATE_LABEL[state]}
                </span>
                {!compact && (
                  <span className="text-[11px] text-slate-400">
                    {formatStageTime(stage.startedAt)}
                    {stage.finishedAt ? ` → ${formatStageTime(stage.finishedAt)}` : ''}
                    {stage.durationMs != null ? ` · ${formatDuration(stage.durationMs)}` : ''}
                  </span>
                )}
              </div>

              {!compact && stage.detail && (
                <p className="mt-0.5 break-words text-xs leading-relaxed text-slate-500">{stage.detail}</p>
              )}

              {state === 'SKIPPED' && (compact || !stage.detail) && (
                <p className="mt-1 text-xs font-medium text-slate-600" role="note">
                  Not verified: sandbox unavailable
                </p>
              )}

              {state === 'FAILED' && (
                <div className="mt-1 flex flex-wrap items-center gap-2" role="alert">
                  <span className="break-words text-xs font-medium text-red-700">
                    {stage.error ?? 'This stage failed.'}
                  </span>
                  {onRetry && (
                    <button
                      type="button"
                      onClick={() => onRetry(stage)}
                      className="rounded-lg border border-red-300 bg-white px-2.5 py-1 text-xs font-semibold text-red-700 transition-colors hover:bg-red-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-600 focus-visible:ring-offset-2"
                    >
                      Retry
                    </button>
                  )}
                </div>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
