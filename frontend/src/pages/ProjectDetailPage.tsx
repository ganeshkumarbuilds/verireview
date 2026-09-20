import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { getFileContent, getProject, listFiles } from '../api/projects';
import { getProjectGeneration } from '../api/generations';
import type { GenerationResponse } from '../api/generationTypes';
import { listExecutions } from '../api/executions';
import {
  isTerminal,
  listFindings,
  listReviews,
  triggerAnalysis,
} from '../api/analysis';
import type {
  ExecutionRunResponse,
  FileContentResponse,
  FindingResponse,
  ProjectFileResponse,
  ProjectResponse,
  ReviewResponse,
} from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  PageHeader,
  SkeletonList,
  inputClass,
  primaryButtonClass,
  secondaryButtonClass,
  selectClass,
} from '../components/ui';
import { FindingDetail } from '../components/FindingDetail';
import { WorkspaceCrumb } from '../components/workflow';
import { GENERATION_PIPELINE_STAGES, GenerationPipeline } from '../components/GenerationPipeline';

const POLL_MS = 3000;

function statusTone(status: string): 'gray' | 'blue' | 'green' | 'red' {
  switch (status) {
    case 'COMPLETED':
      return 'green';
    case 'FAILED':
      return 'red';
    case 'RUNNING':
      return 'blue';
    default:
      return 'gray';
  }
}

function severityTone(severity: string): 'red' | 'amber' | 'blue' | 'gray' {
  switch (severity) {
    case 'CRITICAL':
    case 'HIGH':
      return 'red';
    case 'MEDIUM':
      return 'amber';
    case 'LOW':
      return 'blue';
    default:
      return 'gray';
  }
}

function formatDate(value: string | null): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** Project details: metadata, file inventory, capped viewer, and the
 *  deterministic analysis loop (trigger → status → history → findings). */
export function ProjectDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { token } = useAuth();
  const [project, setProject] = useState<ProjectResponse | null>(null);
  const [files, setFiles] = useState<ProjectFileResponse[]>([]);
  const [selected, setSelected] = useState<FileContentResponse | null>(null);
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [selectedReviewId, setSelectedReviewId] = useState<string | null>(null);
  const [findings, setFindings] = useState<FindingResponse[]>([]);
  const [severityFilter, setSeverityFilter] = useState('ALL');
  const [analyzerFilter, setAnalyzerFilter] = useState('ALL');
  const [sourceFilter, setSourceFilter] = useState('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [detailFindingId, setDetailFindingId] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [executions, setExecutions] = useState<ExecutionRunResponse[]>([]);
  const [runsError, setRunsError] = useState<string | null>(null);
  const [generation, setGeneration] = useState<GenerationResponse | null>(null);

  const reload = useCallback(async () => {
    if (!token || !id) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [detail, inventory] = await Promise.all([
        getProject(apiClient(), token, id),
        listFiles(apiClient(), token, id, { size: 100 }),
      ]);
      setProject(detail);
      setFiles(inventory.content);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load the project.');
    } finally {
      setLoading(false);
    }
  }, [token, id]);

  const reloadAnalysis = useCallback(async () => {
    if (!token || !id) {
      return;
    }
    try {
      const history = await listReviews(apiClient(), token, id);
      setReviews(history.content);
      setSelectedReviewId((current) => {
        if (current && history.content.some((review) => review.id === current)) {
          return current;
        }
        return history.content[0]?.id ?? null;
      });
    } catch (err) {
      setAnalysisError(err instanceof ApiError ? err.message : 'Could not load analyses.');
    }
  }, [token, id]);

  const reloadFindings = useCallback(
    async (reviewId: string) => {
      if (!token) {
        return;
      }
      try {
        const page = await listFindings(apiClient(), token, reviewId);
        setFindings(page.content);
      } catch (err) {
        setAnalysisError(err instanceof ApiError ? err.message : 'Could not load findings.');
      }
    },
    [token],
  );

  useEffect(() => {
    void reload();
    void reloadAnalysis();
  }, [reload, reloadAnalysis]);

  // Sandbox run history (best-effort): the executions endpoint has no
  // pagination contract guarantees in tests, so non-array payloads are
  // treated as "no runs" rather than failures.
  useEffect(() => {
    if (!token || !id) {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const runs = await listExecutions(apiClient(), token, id);
        if (!cancelled) {
          setExecutions(Array.isArray(runs) ? runs : []);
          setRunsError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setExecutions([]);
          setRunsError(err instanceof ApiError ? err.message : 'Could not load run history.');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, id, reviews.length]);

  useEffect(() => {
    if (selectedReviewId) {
      void reloadFindings(selectedReviewId);
    } else {
      setFindings([]);
    }
  }, [selectedReviewId, reloadFindings]);

  // Generated-project workspace: load the linked generation request, if any.
  // Best-effort and supplementary — a missing/failed lookup hides the panel
  // instead of blocking the review workflow. Only responses carrying a real
  // requirement string are rendered (never synthetic placeholders).
  useEffect(() => {
    if (!token || !id || project?.sourceType !== 'GENERATED') {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const data = await getProjectGeneration(apiClient(), token, id);
        if (!cancelled) {
          setGeneration(typeof data?.requirement === 'string' ? data : null);
        }
      } catch {
        if (!cancelled) {
          setGeneration(null);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, id, project]);

  const activeReview = reviews.find((review) => !isTerminal(review.status)) ?? null;

  useEffect(() => {
    if (!activeReview) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      void reloadAnalysis();
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [activeReview, reloadAnalysis]);

  useEffect(() => {
    if (activeReview) {
      setSelectedReviewId(activeReview.id);
    }
  }, [activeReview]);

  const openFile = async (path: string) => {
    if (!token || !id) {
      return;
    }
    setError(null);
    try {
      setSelected(await getFileContent(apiClient(), token, id, path));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not read the file.');
    }
  };

  const startAnalysis = async () => {
    if (!token || !id || starting) {
      return;
    }
    setStarting(true);
    setAnalysisError(null);
    try {
      const review = await triggerAnalysis(apiClient(), token, id);
      setSelectedReviewId(review.id);
      await reloadAnalysis();
    } catch (err) {
      setAnalysisError(
        err instanceof ApiError ? err.message : 'Could not start the analysis.',
      );
    } finally {
      setStarting(false);
    }
  };

  const selectedReview = reviews.find((review) => review.id === selectedReviewId) ?? null;
  const analyzers = Array.from(
    new Set(findings.map((finding) => finding.analyzer ?? 'unknown')),
  ).sort();
  const visibleFindings = findings.filter((finding) => {
    if (severityFilter !== 'ALL' && finding.severity !== severityFilter) {
      return false;
    }
    if (analyzerFilter !== 'ALL' && (finding.analyzer ?? 'unknown') !== analyzerFilter) {
      return false;
    }
    if (sourceFilter !== 'ALL' && finding.source !== sourceFilter) {
      return false;
    }
    const query = searchQuery.trim().toLowerCase();
    if (query) {
      const haystack = [finding.title, finding.description, finding.filePath, finding.rule]
        .filter((part): part is string => part != null)
        .join('\n')
        .toLowerCase();
      if (!haystack.includes(query)) {
        return false;
      }
    }
    return true;
  });

  const countBy = (predicate: (finding: FindingResponse) => boolean): number =>
    findings.filter(predicate).length;
  const overviewStats: [string, number][] = [
    ['total', findings.length],
    ['critical', countBy((finding) => finding.severity === 'CRITICAL')],
    ['high', countBy((finding) => finding.severity === 'HIGH')],
    ['medium', countBy((finding) => finding.severity === 'MEDIUM')],
    ['low', countBy((finding) => finding.severity === 'LOW')],
    ['deterministic', countBy((finding) => finding.source === 'DETERMINISTIC')],
    ['ai', countBy((finding) => finding.source === 'AI')],
  ];
  const detailFinding = findings.find((finding) => finding.id === detailFindingId) ?? null;

  if (loading) {
    return (
      <div className="space-y-4">
        <LoadingState label="Loading…" />
        <SkeletonList rows={4} />
      </div>
    );
  }
  if (error && !project) {
    return <ErrorAlert message={error} onRetry={() => void reload()} />;
  }
  if (!project) {
    return null;
  }

  return (
    <div className="space-y-6">
      <WorkspaceCrumb items={[{ label: 'Projects', to: '/projects' }, { label: project.name }]} />
      <PageHeader
        title={project.name}
        description={
          project.description ??
          `${project.sourceType} · ${project.fileCount} files · ${project.language ?? 'auto-detected language'}`
        }
        actions={
          <span className="inline-flex flex-wrap items-center gap-2">
            {selectedReview && (
              <Badge tone={statusTone(selectedReview.status)}>{selectedReview.status}</Badge>
            )}
            <Link to={`/review?project=${project.id}`} className={primaryButtonClass}>
              Open in Review
            </Link>
          </span>
        }
      />
      {error && <ErrorAlert message={error} onRetry={() => void reload()} />}
      <section id="workspace-overview" aria-label="Overview" className="scroll-mt-32 space-y-4">
        <h2 className="text-lg font-bold tracking-tight text-indigo-950">Overview</h2>
      <div className="grid gap-4 lg:grid-cols-5">
        <div className="lg:col-span-2">
          <Card title="Details" subtitle="Project metadata from the backend.">
            <dl className="space-y-2 text-sm">
              <div className="flex items-center justify-between gap-2 border-b border-indigo-50 pb-2">
                <dt className="font-medium text-slate-500">Source</dt>
                <dd className="font-semibold text-indigo-950">{project.sourceType}</dd>
              </div>
              <div className="flex items-center justify-between gap-2 border-b border-indigo-50 pb-2">
                <dt className="font-medium text-slate-500">Language</dt>
                <dd className="font-semibold text-indigo-950">{project.language ?? '—'}</dd>
              </div>
              <div className="flex items-center justify-between gap-2">
                <dt className="font-medium text-slate-500">Files</dt>
                <dd className="font-semibold text-indigo-950">{project.fileCount}</dd>
              </div>
              {project.description && (
                <p className="rounded-lg bg-indigo-50/60 px-3 py-2 text-sm leading-relaxed text-slate-600">
                  {project.description}
                </p>
              )}
            </dl>
          </Card>
        </div>
        <div className="lg:col-span-3">
          <Card
            title="Latest state"
            subtitle="Where this codebase stands — and what needs attention next."
          >
            {selectedReview ? (
              <div className="space-y-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={statusTone(selectedReview.status)}>{selectedReview.status}</Badge>
                  <span className="text-sm text-slate-600">
                    {selectedReview.findingCount} findings ·{' '}
                    {countBy(
                      (finding) =>
                        finding.severity === 'CRITICAL' || finding.severity === 'HIGH',
                    )}{' '}
                    crit/high · finished {formatDate(selectedReview.finishedAt)}
                  </span>
                </div>
                <div className="flex flex-wrap gap-2">
                  <a href="#workspace-analysis" className={secondaryButtonClass}>
                    Run Analysis
                  </a>
                  <a href="#workspace-findings" className={secondaryButtonClass}>
                    Review Findings
                  </a>
                  <a href="#workspace-files" className={secondaryButtonClass}>
                    Open relevant files
                  </a>
                </div>
              </div>
            ) : (
              <div className="space-y-3">
                <p className="text-sm text-slate-600">
                  Nothing analyzed so far — start below to collect findings.
                </p>
                <a href="#workspace-analysis" className={secondaryButtonClass}>
                  Go to Analysis
                </a>
              </div>
            )}
          </Card>
        </div>
      </div>
      {project.sourceType === 'GENERATED' && generation && (
        <Card
          title="Generation pipeline"
          subtitle="This project was created by the generation workflow — review it like any other project."
        >
          <div className="space-y-3">
            <p className="rounded-lg bg-indigo-50/60 px-3 py-2 text-sm leading-relaxed text-slate-600">
              <span className="font-semibold text-indigo-950">Requirement: </span>
              {generation.requirement}
            </p>
            <GenerationPipeline
              stages={GENERATION_PIPELINE_STAGES.map((label, index) => ({
                key: label,
                label,
                state: index === 0 ? 'done' : 'planned',
                hint: index === 0 ? undefined : 'Planned — a future phase.',
              }))}
            />
            <a href="#workspace-findings" className={secondaryButtonClass}>
              Review Findings
            </a>
          </div>
        </Card>
      )}
      </section>
      <section id="workspace-files" aria-label="Files" className="scroll-mt-32 space-y-4">
        <h2 className="text-lg font-bold tracking-tight text-indigo-950">Files</h2>
          <Card title={`Files (${files.length})`} subtitle="Select a file to preview its contents.">
            {files.length === 0 ? (
              <EmptyState
                title="No files yet."
                body="Empty shell — upload a ZIP to fill it (Phase 5 supports ZIP only)."
              />
            ) : (
              <ul className="max-h-64 space-y-0.5 overflow-y-auto pr-1">
                {files.map((file) => {
                  const isSelected = selected?.path === file.path;
                  return (
                    <li key={file.id}>
                      <button
                        type="button"
                        onClick={() => void openFile(file.path)}
                        aria-current={isSelected ? 'true' : undefined}
                        className={`w-full truncate rounded-lg px-3 py-1.5 text-left font-mono text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-600 ${
                          isSelected
                            ? 'bg-indigo-100 font-semibold text-indigo-900'
                            : 'text-indigo-700 hover:bg-indigo-50 hover:text-indigo-900'
                        }`}
                      >
                        {file.path}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </Card>
      {selected && (
        <Card
          title={selected.path}
          subtitle={selected.truncated ? 'Preview truncated at 256 KB.' : 'File preview (capped).'}
        >
          <pre className="max-h-96 overflow-auto whitespace-pre-wrap rounded-xl border border-indigo-100 bg-indigo-50/50 p-4 font-mono text-xs leading-relaxed text-slate-800">
            {selected.content}
          </pre>
          {selected.truncated && <p className="pt-2 text-sm">Preview truncated at 256 KB.</p>}
        </Card>
      )}
      </section>

      <section id="workspace-analysis" aria-label="Analysis" className="scroll-mt-32 space-y-4">
        <h2 className="text-lg font-bold tracking-tight text-indigo-950">Analysis</h2>
      <Card
        title="Deterministic analysis"
        subtitle="Sandboxed analyzers run first; AI findings layer on top."
        actions={
          <button
            type="button"
            onClick={() => void startAnalysis()}
            disabled={starting || activeReview !== null}
            className={primaryButtonClass}
          >
            {starting ? 'Starting…' : 'Start analysis'}
          </button>
        }
      >
        <div className="flex flex-wrap items-center gap-3">
          {activeReview && (
            <span className="flex items-center gap-2 text-sm">
              <Badge tone={statusTone(activeReview.status)}>{activeReview.status}</Badge>
              <span className="text-slate-600">Analysis in progress…</span>
            </span>
          )}
          {!activeReview && reviews.length === 0 && (
            <span className="text-sm text-slate-600">No analysis yet — findings appear here.</span>
          )}
        </div>
        {analysisError && (
          <div className="mt-3">
            <ErrorAlert message={analysisError} onRetry={() => void reloadAnalysis()} />
          </div>
        )}
        {selectedReview && (
          <dl className="mt-4 grid gap-3 rounded-xl bg-indigo-50/50 p-4 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Status</dt>
              <dd className="mt-1">
                <Badge tone={statusTone(selectedReview.status)}>{selectedReview.status}</Badge>
              </dd>
            </div>
            <div>
              <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Findings</dt>
              <dd className="mt-1 text-lg font-bold text-indigo-950">{selectedReview.findingCount}</dd>
            </div>
            <div>
              <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Finished</dt>
              <dd className="mt-1 font-medium text-slate-700">{formatDate(selectedReview.finishedAt)}</dd>
            </div>
            <div>
              <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Duration</dt>
              <dd className="mt-1 font-medium text-slate-700">
                {selectedReview.durationMs == null ? '—' : `${selectedReview.durationMs} ms`}
              </dd>
            </div>
          </dl>
        )}
      {selectedReview?.error && selectedReview.status !== 'FAILED' && (
        <p className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">{selectedReview.error}</p>
      )}
      {selectedReview && selectedReview.status === 'FAILED' && (
        <div
          role="alert"
          className="mt-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800"
        >
          <p className="font-semibold">Analysis failed</p>
          <p className="mt-1 leading-relaxed">
            {selectedReview.error ??
              'The analysis did not complete. Start a new analysis to retry.'}
          </p>
        </div>
      )}
      </Card>

      {reviews.length > 0 && (
        <Card
          title={`Analysis history (${reviews.length})`}
          subtitle="Select a run to inspect its findings."
        >
          <ul className="divide-y divide-indigo-50">
            {reviews.map((review) => {
              const isSelected = review.id === selectedReviewId;
              return (
                <li key={review.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedReviewId(review.id)}
                    aria-current={isSelected ? 'true' : undefined}
                    className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-600 ${
                      isSelected ? 'bg-indigo-50 font-semibold' : 'hover:bg-indigo-50/60'
                    }`}
                  >
                    <Badge tone={statusTone(review.status)}>{review.status}</Badge>
                    <span className="text-slate-600">{formatDate(review.createdAt)}</span>
                    <span className="ml-auto shrink-0 font-medium text-slate-600">
                      {review.findingCount} findings
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </Card>
      )}
      </section>

      <section id="workspace-findings" aria-label="Findings" className="scroll-mt-32 space-y-4">
        <h2 className="text-lg font-bold tracking-tight text-indigo-950">Findings</h2>
      {selectedReview && (
        <Card
          title={`Findings (${visibleFindings.length})`}
          subtitle="Filter and search the selected analysis run."
          actions={
            <Link to={`/review?project=${project.id}`} className={secondaryButtonClass}>
              Full review workspace
            </Link>
          }
        >
          <ul aria-label="Review overview" className="mb-4 flex flex-wrap gap-2 text-sm">
            {overviewStats.map(([label, count]) => (
              <li
                key={label}
                className="rounded-full border border-indigo-100 bg-indigo-50/60 px-3 py-1 text-slate-600"
              >
                <span className="font-bold text-indigo-950">{`${count} ${label}`}</span>
              </li>
            ))}
          </ul>
          <div className="mb-4 grid gap-2 rounded-xl bg-indigo-50/50 p-3 sm:grid-cols-2 lg:grid-cols-4">
            <label className="flex items-center gap-2 text-sm">
              <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">Severity</span>
              <select
                aria-label="Filter by severity"
                value={severityFilter}
                onChange={(event) => setSeverityFilter(event.target.value)}
                className={selectClass}
              >
                <option value="ALL">All</option>
                <option value="CRITICAL">CRITICAL</option>
                <option value="HIGH">HIGH</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="LOW">LOW</option>
                <option value="INFO">INFO</option>
              </select>
            </label>
            <label className="flex items-center gap-2 text-sm">
              <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">Analyzer</span>
              <select
                aria-label="Filter by analyzer"
                value={analyzerFilter}
                onChange={(event) => setAnalyzerFilter(event.target.value)}
                className={selectClass}
              >
                <option value="ALL">All</option>
                {analyzers.map((analyzer) => (
                  <option key={analyzer} value={analyzer}>
                    {analyzer}
                  </option>
                ))}
              </select>
            </label>
            <label className="flex items-center gap-2 text-sm">
              <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">Source</span>
              <select
                aria-label="Filter by source"
                value={sourceFilter}
                onChange={(event) => setSourceFilter(event.target.value)}
                className={selectClass}
              >
                <option value="ALL">All</option>
                <option value="DETERMINISTIC">DETERMINISTIC</option>
                <option value="AI">AI</option>
                <option value="VERIFIED">VERIFIED</option>
              </select>
            </label>
            <label className="flex items-center gap-2 text-sm">
              <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">Search</span>
              <input
                type="search"
                aria-label="Search findings"
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder="Title, file, rule…"
                className={inputClass}
              />
            </label>
          </div>
          {findings.length === 0 ? (
            <EmptyState
              title={selectedReview.status === 'COMPLETED' ? 'Clean review.' : 'No findings yet.'}
              body={
                selectedReview.status === 'COMPLETED'
                  ? 'No findings — this review is clean.'
                  : 'Findings will appear here once the analysis completes.'
              }
            />
          ) : visibleFindings.length === 0 ? (
            <EmptyState
              title="No matching findings."
              body="No findings match the selected filters."
              action={
                <button
                  type="button"
                  onClick={() => {
                    setSeverityFilter('ALL');
                    setAnalyzerFilter('ALL');
                    setSourceFilter('ALL');
                    setSearchQuery('');
                  }}
                  className={secondaryButtonClass}
                >
                  Clear filters
                </button>
              }
            />
          ) : (
            <ul className="space-y-2">
              {visibleFindings.map((finding) => (
                <li
                  key={finding.id}
                  className="rounded-xl border border-indigo-100 bg-white shadow-sm shadow-indigo-100/50 transition-all hover:border-indigo-200 hover:shadow"
                >
                  <button
                    type="button"
                    onClick={() => setDetailFindingId(finding.id)}
                    aria-label={`Open finding ${finding.rule ?? finding.title}`}
                    className="block w-full space-y-1.5 rounded-t-xl px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-600"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={severityTone(finding.severity)}>{finding.severity}</Badge>
                      <Badge tone="violet">{finding.analyzer ?? 'unknown'}</Badge>
                      <span className="font-mono text-sm font-semibold text-indigo-950">
                        {finding.rule ?? finding.title}
                      </span>
                    </div>
                    {finding.description && (
                      <p className="line-clamp-2 text-sm leading-relaxed text-slate-600">{finding.description}</p>
                    )}
                    <p className="font-mono text-xs text-slate-500">
                      {finding.filePath ?? '—'}
                      {finding.lineStart != null ? `:${finding.lineStart}` : ''}
                    </p>
                  </button>
                  {selectedReview && (
                    <div className="flex justify-end border-t border-indigo-50 px-4 py-1.5">
                      <Link
                        to={`/projects/${id}/reviews/${selectedReview.id}/findings/${finding.id}`}
                        aria-label={`Open detail page for ${finding.rule ?? finding.title}`}
                        className="rounded-md text-xs font-semibold text-indigo-600 transition-colors hover:text-indigo-900 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                      >
                        Open detail page →
                      </Link>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}
      </section>

      <section id="workspace-runs" aria-label="Runs" className="scroll-mt-32 space-y-4">
        <h2 className="text-lg font-bold tracking-tight text-indigo-950">Runs</h2>
        <Card
          title={`Sandbox runs (${executions.length})`}
          subtitle="Patch executions recorded for this project. Verification detail lives with each finding."
        >
          {runsError && (
            <p role="alert" className="mb-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
              {runsError}
            </p>
          )}
          {executions.length === 0 ? (
            <EmptyState
              title="No sandbox runs yet."
              body="Runs appear here after a proposed patch is executed from a finding. Start from any finding in the list above."
              action={
                <a href="#workspace-findings" className={secondaryButtonClass}>
                  Go to Findings
                </a>
              }
            />
          ) : (
            <ul className="divide-y divide-indigo-50">
              {executions.map((run) => (
                <li
                  key={run.id}
                  className="flex flex-wrap items-center gap-2 px-3 py-2.5 text-sm"
                >
                  <Badge tone={run.status === 'SUCCESS' ? 'green' : run.status === 'PENDING' || run.status === 'RUNNING' ? 'blue' : 'red'}>
                    {run.status}
                  </Badge>
                  <Badge tone="gray">build: {run.buildStatus}</Badge>
                  {run.exitCode != null && (
                    <span className="text-xs tabular-nums text-slate-500">exit {run.exitCode}</span>
                  )}
                  {run.durationMs != null && (
                    <span className="text-xs tabular-nums text-slate-500">{run.durationMs} ms</span>
                  )}
                  <span className="ml-auto text-xs text-slate-500">{formatDate(run.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </section>
      {detailFinding && (
        <FindingDetail
          finding={detailFinding}
          onClose={() => setDetailFindingId(null)}
          onViewFile={(path) => {
            setDetailFindingId(null);
            void openFile(path);
          }}
        />
      )}
    </div>
  );
}
