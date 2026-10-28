import { Link } from 'react-router-dom';
import type { FindingResponse, ReviewResponse } from '../../../api/types';
import {
  Badge,
  Card,
  EmptyState,
  LoadingState,
  inputClass,
  secondaryButtonClass,
  selectClass,
} from '../../../components/ui';
import { severityTone, sourceTone } from '../../../components/workflow';

export interface FindingsPanelProps {
  projectId: string | null;
  review: ReviewResponse | null;
  findings: FindingResponse[];
  visibleFindings: FindingResponse[];
  analyzers: string[];
  loading: boolean;
  stats: readonly (readonly [string, number])[];
  severityFilter: string;
  onSeverityFilter: (value: string) => void;
  analyzerFilter: string;
  onAnalyzerFilter: (value: string) => void;
  sourceFilter: string;
  onSourceFilter: (value: string) => void;
  searchQuery: string;
  onSearchQuery: (value: string) => void;
  onClearFilters: () => void;
  /** Load failure, so an error is never mistaken for a clean review. */
  error?: string | null;
}

/** Step 3 of Review: read-only findings with real backend counts. */
export function FindingsPanel({
  projectId,
  review,
  findings,
  visibleFindings,
  analyzers,
  loading,
  stats,
  severityFilter,
  onSeverityFilter,
  analyzerFilter,
  onAnalyzerFilter,
  sourceFilter,
  onSourceFilter,
  searchQuery,
  onSearchQuery,
  onClearFilters,
  error = null,
}: FindingsPanelProps) {
  if (!review) return null;

  return (
    <Card
      title={review.status === 'COMPLETED'
        ? `Findings (${visibleFindings.length})`
        : 'Findings (updating)'}
      subtitle={`Run ${review.status} · ${review.findingCount} reported by the backend. Open a finding to investigate it.`}
    >
      <ul aria-label="Review overview" className="mb-4 flex flex-wrap gap-2 text-sm">
        {stats.map(([label, count]) => (
          <li
            key={label}
            className="rounded-full border border-indigo-100 bg-indigo-50/60 px-3 py-1 text-slate-600"
          >
            <span className="font-bold text-indigo-950">{`${count} ${label}`}</span>
          </li>
        ))}
      </ul>

      {(review.checks?.length ?? 0) > 0 && (
        <div className="mb-4 rounded-xl border border-slate-200 bg-slate-50 p-3">
          <p className="text-sm font-semibold text-slate-900">
            Files analyzed: {review.filesAnalyzed}/{review.filesTotal}
          </p>
          <div className="mt-2 grid gap-2 sm:grid-cols-2">
            {(review.checks ?? []).map((check) => (
              <div key={check.analyzer} className="rounded-lg border border-slate-200 bg-white p-2 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold text-slate-800">{check.analyzer}</span>
                  <Badge tone={check.status === 'RAN' ? 'green' : check.status === 'FAILED' ? 'red' : 'amber'}>
                    {check.status}
                  </Badge>
                </div>
                {check.status === 'RAN' && (
                  <p className="mt-1 text-slate-600">
                    {check.filesAnalyzed}/{review.filesTotal} files · {check.findingCount} findings · {check.durationMs ?? 0} ms
                  </p>
                )}

                {(review.files?.length ?? 0) > 0 && (
                  <div className="mb-4 rounded-xl border border-slate-200 bg-white p-3">
                    <p className="text-sm font-semibold text-slate-900">
                      File progress: {review.files.filter((file) => file.status === 'ANALYZED').length}/
                      {review.files.length} analyzed
                    </p>
                    <ul aria-label="Analysis file progress" className="mt-2 max-h-48 space-y-1 overflow-auto text-xs">
                      {review.files.map((file) => (
                        <li key={file.path} className="flex flex-wrap justify-between gap-2">
                          <span className="font-mono text-slate-700">{file.path}</span>
                          <span className={file.status === 'SKIPPED' ? 'text-amber-700' : 'text-emerald-700'}>
                            {file.status === 'SKIPPED' ? `Skipped: ${file.reason ?? 'not analyzed'}` : 'Analyzed'}
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                {review.rating && (
                  <div className="mb-4 flex flex-wrap gap-2 text-xs">
                    <Badge tone="violet">Rating {review.rating.overall}/100</Badge>
                    <span className="rounded-full bg-slate-100 px-3 py-1">Syntax {review.rating.syntax}</span>
                    <span className="rounded-full bg-slate-100 px-3 py-1">Logic {review.rating.logic}</span>
                    <span className="rounded-full bg-slate-100 px-3 py-1">Security {review.rating.security}</span>
                    <span className="rounded-full bg-slate-100 px-3 py-1">Style {review.rating.style}</span>
                  </div>
                )}
                {check.status !== 'RAN' && check.reason && (
                  <p className="mt-1 text-amber-800">{check.reason}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="mb-4 grid gap-2 rounded-xl bg-indigo-50/50 p-3 sm:grid-cols-2 lg:grid-cols-4">
        <label className="flex min-w-0 items-center gap-2 text-sm">
          <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Severity
          </span>
          <select
            aria-label="Severity"
            value={severityFilter}
            onChange={(event) => onSeverityFilter(event.target.value)}
            className={`${selectClass} min-w-0 max-w-full`}
          >
            <option value="ALL">All</option>
            <option value="CRITICAL">CRITICAL</option>
            <option value="HIGH">HIGH</option>
            <option value="MEDIUM">MEDIUM</option>
            <option value="LOW">LOW</option>
            <option value="INFO">INFO</option>
          </select>
        </label>
        <label className="flex min-w-0 items-center gap-2 text-sm">
          <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Analyzer
          </span>
          <select
            aria-label="Analyzer"
            value={analyzerFilter}
            onChange={(event) => onAnalyzerFilter(event.target.value)}
            className={`${selectClass} min-w-0 max-w-full`}
          >
            <option value="ALL">All</option>
            {analyzers.map((analyzer) => (
              <option key={analyzer} value={analyzer}>
                {analyzer}
              </option>
            ))}
          </select>
        </label>
        <label className="flex min-w-0 items-center gap-2 text-sm">
          <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Source
          </span>
          <select
            aria-label="Source"
            value={sourceFilter}
            onChange={(event) => onSourceFilter(event.target.value)}
            className={`${selectClass} min-w-0 max-w-full`}
          >
            <option value="ALL">All</option>
            <option value="DETERMINISTIC">DETERMINISTIC</option>
            <option value="AI">AI</option>
            <option value="VERIFIED">VERIFIED</option>
          </select>
        </label>
        <label className="flex min-w-0 items-center gap-2 text-sm">
          <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Search
          </span>
          <input
            type="search"
            aria-label="Search findings"
            value={searchQuery}
            onChange={(event) => onSearchQuery(event.target.value)}
            placeholder="Title, file, rule…"
            className={`${inputClass} min-w-0 flex-1`}
          />
        </label>
      </div>

      {loading && findings.length === 0 ? (
        <LoadingState label="Loading findings…" />
      ) : error && findings.length === 0 ? (
        <p className="py-3 text-sm text-slate-600">
          Findings could not be loaded. Fix the error above and retry.
        </p>
      ) : findings.length === 0 ? (
        <EmptyState
          title={
            review.status === 'NOT_ANALYZED'
              ? 'Analysis not completed.'
              : review.findingCount === 0 && review.status === 'COMPLETED'
              ? 'Clean review.'
              : 'No findings yet.'
          }
          body={
            review.status === 'NOT_ANALYZED'
              ? review.error ?? 'No checks could run: sandbox unavailable.'
              : review.findingCount === 0 && review.status === 'COMPLETED'
              ? 'No findings — this review is clean.'
              : review.findingCount > 0
                ? `${review.findingCount} findings were reported but none were returned. Reload the review to see them.`
                : 'Findings will appear here once the analysis completes.'
          }
        />
      ) : visibleFindings.length === 0 ? (
        <EmptyState
          title="No matching findings."
          body="No findings match the selected filters."
          action={
            <button type="button" onClick={onClearFilters} className={secondaryButtonClass}>
              Clear filters
            </button>
          }
        />
      ) : (
        <>
          <ul className="space-y-2">
            {visibleFindings.map((finding) => (
              <li key={finding.id}>
                <Link
                  to={`/projects/${projectId}/reviews/${review.id}/findings/${finding.id}`}
                  aria-label={`Investigate finding ${finding.rule ?? finding.title}`}
                  className="block space-y-1.5 rounded-xl border border-indigo-100 bg-white px-4 py-3 shadow-sm shadow-indigo-100/50 transition-all hover:border-indigo-200 hover:shadow focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={severityTone(finding.severity)}>{finding.severity}</Badge>
                    <Badge tone={sourceTone(finding.source)}>{finding.source}</Badge>
                    <Badge tone="violet">{finding.analyzer ?? 'unknown'}</Badge>
                    <span className="break-words font-mono text-sm font-semibold text-indigo-950">
                      {finding.rule ?? finding.title}
                    </span>
                  </div>
                  {finding.description && (
                    <p className="line-clamp-2 text-sm leading-relaxed text-slate-600">
                      {finding.title !== (finding.rule ?? finding.title)
                        ? `${finding.title} — ${finding.description}`
                        : finding.description}
                    </p>
                  )}
                  <p className="break-all font-mono text-xs text-slate-500">
                    {finding.filePath ?? '—'}
                    {finding.lineStart != null ? `:${finding.lineStart}` : ''}
                    <span className="ml-2 font-sans font-semibold text-indigo-600">
                      Investigate →
                    </span>
                  </p>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </Card>
  );
}
