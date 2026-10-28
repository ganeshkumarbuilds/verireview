import type { ReviewResponse } from '../../../api/types';
import { Badge, Card, primaryButtonClass } from '../../../components/ui';

type StageState = 'WAITING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';

export function ReviewRunPipeline({
  review,
  onRetry,
}: {
  review: ReviewResponse;
  onRetry: (stage: 'analyze' | 'review' | 'rating') => void;
}) {
  const terminal = review.status === 'COMPLETED' || review.status === 'NOT_ANALYZED';
  const failed = review.status === 'FAILED';
  const failedStage: 'analyze' | 'review' | 'rating' =
    review.error?.toLowerCase().includes('rating') ? 'rating'
      : review.error?.toLowerCase().includes('ai') || review.error?.toLowerCase().includes('review')
        ? 'review'
        : 'analyze';
  const stages: Array<{ key: 'import' | 'analyze' | 'review' | 'rating'; label: string; state: StageState; detail: string; retry?: 'analyze' | 'review' | 'rating' }> = [
    { key: 'import', label: 'Import', state: review.filesTotal > 0 ? 'COMPLETED' : 'WAITING', detail: `${review.filesTotal} files available` },
    {
      key: 'analyze',
      label: 'Analyze',
      state: failed ? 'FAILED' : terminal ? 'COMPLETED' : review.status === 'RUNNING' ? 'RUNNING' : 'WAITING',
      detail: `Analyzed ${review.filesAnalyzed} of ${review.filesTotal} files`,
      retry: failed ? 'analyze' : undefined,
    },
    {
      key: 'review',
      label: 'Review (AI)',
      state: failed && failedStage === 'review' ? 'FAILED' : review.status === 'QUEUED' ? 'WAITING' : terminal ? 'COMPLETED' : 'RUNNING',
      detail: (review.checks ?? []).some((check) => check.status === 'RAN') ? 'Reviewing findings with available checks' : 'Waiting for analysis checks',
      retry: failed && failedStage === 'review' ? 'review' : undefined,
    },
    {
      key: 'rating',
      label: 'Rating',
      state: failed && failedStage === 'rating' ? 'FAILED' : terminal ? 'COMPLETED' : 'WAITING',
      detail: terminal ? `Overall rating ${review.rating?.overall ?? 0}/100` : 'Waiting for review results',
      retry: failed && failedStage === 'rating' ? 'rating' : undefined,
    },
  ];
  return (
    <Card title="Review run" subtitle="Import → Analyze → Review (AI) → Rating">
      <ol aria-label="Review run pipeline" className="space-y-3">
        {stages.map((stage) => (
          <li key={stage.key} className="rounded-xl border border-slate-200 bg-slate-50 p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-semibold text-indigo-950">{stage.label}</span>
              <Badge tone={stage.state === 'COMPLETED' ? 'green' : stage.state === 'FAILED' ? 'red' : stage.state === 'RUNNING' ? 'blue' : 'amber'}>
                {stage.state}
              </Badge>
              <span className="text-xs text-slate-500">
                {stage.key === 'analyze' && review.startedAt ? `Started ${new Date(review.startedAt).toLocaleTimeString()}` : ''}
                {stage.key === 'rating' && review.finishedAt ? ` · Finished ${new Date(review.finishedAt).toLocaleTimeString()}` : ''}
              </span>
              {stage.retry && (
                <button type="button" className={`${primaryButtonClass} ml-auto px-3 py-1 text-xs`} onClick={() => onRetry(stage.retry!)}>
                  Retry
                </button>
              )}
            </div>
            <p className="mt-1 text-xs text-slate-600">{stage.detail}</p>
            {stage.state === 'FAILED' && <p className="mt-1 text-xs font-medium text-red-700" role="alert">{review.error ?? 'This stage failed.'}</p>}
          </li>
        ))}
      </ol>
      {(review.activity?.length ?? 0) > 0 && (
        <div className="mt-4 border-t border-slate-200 pt-3">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-slate-500">Activity</h3>
          <ul aria-label="Review activity" className="mt-2 space-y-1 text-xs text-slate-600">
            {review.activity.map((event) => <li key={`${event.message}-${event.at}`}>{event.message} · {new Date(event.at).toLocaleTimeString()}</li>)}
          </ul>
        </div>
      )}
    </Card>
  );
}
