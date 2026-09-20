import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import {
  isTerminal,
  listFindings,
  listReviews,
  triggerAnalysis,
} from '../api/analysis';
import { listProjects } from '../api/projects';
import type {
  FindingResponse,
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
import {
  WorkspaceCrumb,
  severityTone,
  sourceTone,
} from '../components/workflow';

const POLL_MS = 3000;

/**
 * Review workspace: the main investigation surface across the user's
 * projects. Project-scoped data only, existing endpoints only. Each
 * finding links to its focused detail page, where the existing
 * Fix → Apply → Execute → Verify workflow lives.
 */
export function ReviewPage() {
  const { token } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const [projects, setProjects] = useState<ProjectResponse[]>([]);
  const [projectsLoading, setProjectsLoading] = useState(true);
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(
    searchParams.get('project'),
  );
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [reviewsLoading, setReviewsLoading] = useState(false);
  const [selectedReviewId, setSelectedReviewId] = useState<string | null>(null);
  const [findings, setFindings] = useState<FindingResponse[]>([]);
  const [severityFilter, setSeverityFilter] = useState('ALL');
  const [analyzerFilter, setAnalyzerFilter] = useState('ALL');
  const [sourceFilter, setSourceFilter] = useState('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      setProjectsLoading(false);
      return;
    }
    let cancelled = false;
    setProjectsLoading(true);
    void (async () => {
      try {
        const page = await listProjects(apiClient(), token, { size: 50 });
        if (cancelled) return;
        setProjects(page.content);
        setSelectedProjectId((current) => {
          if (current && page.content.some((project) => project.id === current)) {
            return current;
          }
          return page.content[0]?.id ?? null;
        });
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.message : 'Could not load projects.');
        }
      } finally {
        if (!cancelled) setProjectsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token]);

  const reloadReviews = useCallback(async () => {
    if (!token || !selectedProjectId) {
      setReviews([]);
      return;
    }
    setReviewsLoading(true);
    try {
      const history = await listReviews(apiClient(), token, selectedProjectId);
      setReviews(history.content);
      setSelectedReviewId((current) => {
        if (current && history.content.some((review) => review.id === current)) {
          return current;
        }
        return history.content[0]?.id ?? null;
      });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load analyses.');
    } finally {
      setReviewsLoading(false);
    }
  }, [token, selectedProjectId]);

  useEffect(() => {
    void reloadReviews();
  }, [reloadReviews]);

  useEffect(() => {
    if (!token || !selectedReviewId) {
      setFindings([]);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const page = await listFindings(apiClient(), token, selectedReviewId);
        if (!cancelled) setFindings(page.content);
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.message : 'Could not load findings.');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, selectedReviewId]);

  const activeReview = reviews.find((review) => !isTerminal(review.status)) ?? null;

  useEffect(() => {
    if (!activeReview || !token) return undefined;
    const timer = window.setInterval(() => {
      void reloadReviews();
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [activeReview, reloadReviews, token]);

  useEffect(() => {
    if (activeReview) setSelectedReviewId(activeReview.id);
  }, [activeReview]);

  const startAnalysis = async () => {
    if (!token || !selectedProjectId || starting) return;
    setStarting(true);
    setError(null);
    try {
      const review = await triggerAnalysis(apiClient(), token, selectedProjectId);
      setSelectedReviewId(review.id);
      await reloadReviews();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start the analysis.');
    } finally {
      setStarting(false);
    }
  };

  const pickProject = (projectId: string) => {
    setSelectedProjectId(projectId);
    setSelectedReviewId(null);
    setFindings([]);
    setSeverityFilter('ALL');
    setAnalyzerFilter('ALL');
    setSourceFilter('ALL');
    setSearchQuery('');
    setSearchParams(projectId ? { project: projectId } : {}, { replace: true });
  };

  const selectedProject = projects.find((project) => project.id === selectedProjectId) ?? null;
  const selectedReview = reviews.find((review) => review.id === selectedReviewId) ?? null;
  const analyzers = useMemo(
    () => Array.from(new Set(findings.map((finding) => finding.analyzer ?? 'unknown'))).sort(),
    [findings],
  );

  const visibleFindings = findings.filter((finding) => {
    if (severityFilter !== 'ALL' && finding.severity !== severityFilter) return false;
    if (analyzerFilter !== 'ALL' && (finding.analyzer ?? 'unknown') !== analyzerFilter) return false;
    if (sourceFilter !== 'ALL' && finding.source !== sourceFilter) return false;
    const query = searchQuery.trim().toLowerCase();
    if (query) {
      const haystack = [finding.title, finding.description, finding.filePath, finding.rule]
        .filter((part): part is string => part != null)
        .join('\n')
        .toLowerCase();
      if (!haystack.includes(query)) return false;
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
    ['verified', countBy((finding) => finding.source === 'VERIFIED')],
  ];

  return (
    <div className="space-y-6">
      <WorkspaceCrumb items={[{ label: 'Review' }]} />
      <PageHeader
        title="Review"
        description="Investigate findings across your codebases. Pick a project, choose an analysis run, then open a finding to request a fix."
        actions={
          selectedProjectId && (
            <button
              type="button"
              onClick={() => void startAnalysis()}
              disabled={starting || activeReview !== null}
              className={primaryButtonClass}
            >
              {starting ? 'Starting…' : 'Start analysis'}
            </button>
          )
        }
      />
      {error && <ErrorAlert message={error} onRetry={() => void reloadReviews()} />}

      <Card title="Project context" subtitle="Reviews are project-scoped. Select the codebase under investigation.">
        {projectsLoading ? (
          <LoadingState label="Loading projects…" />
        ) : projects.length === 0 ? (
          <EmptyState
            title="No projects to review."
            body="Create a project shell or upload a ZIP first — findings appear after an analysis runs."
            action={
              <Link to="/projects" className={primaryButtonClass}>
                Go to Projects
              </Link>
            }
          />
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex min-w-0 flex-1 items-center gap-2 text-sm sm:max-w-md">
              <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Project
              </span>
              <select
                aria-label="Select project"
                value={selectedProjectId ?? ''}
                onChange={(event) => pickProject(event.target.value)}
                className={selectClass}
              >
                {projects.map((project) => (
                  <option key={project.id} value={project.id}>
                    {project.name} · {project.fileCount} files
                  </option>
                ))}
              </select>
            </label>
            {selectedProject && (
              <Link
                to={`/projects/${selectedProject.id}`}
                className="shrink-0 rounded-lg px-3 py-2 text-sm font-semibold text-indigo-700 transition-colors hover:bg-indigo-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
              >
                Open project workspace
              </Link>
            )}
          </div>
        )}
      </Card>

      {selectedProjectId && (
        <Card
          title={`Analysis runs (${reviews.length})`}
          subtitle="Select a run to inspect its findings. Runs poll while in progress."
        >
          {reviewsLoading && reviews.length === 0 ? (
            <>
              <LoadingState label="Loading analyses…" />
              <SkeletonList rows={2} />
            </>
          ) : reviews.length === 0 ? (
            <EmptyState
              title="No analysis yet."
              body="Start your first analysis to collect deterministic findings for this project."
              action={
                <button
                  type="button"
                  onClick={() => void startAnalysis()}
                  disabled={starting}
                  className={primaryButtonClass}
                >
                  {starting ? 'Starting…' : 'Start analysis'}
                </button>
              }
            />
          ) : (
            <ul className="flex flex-wrap gap-2">
              {reviews.map((review) => {
                const isSelected = review.id === selectedReviewId;
                return (
                  <li key={review.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedReviewId(review.id)}
                      aria-pressed={isSelected}
                      className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 ${
                        isSelected
                          ? 'border-indigo-600 bg-indigo-600 font-semibold text-white'
                          : 'border-indigo-200 bg-white text-slate-700 hover:border-indigo-300 hover:bg-indigo-50'
                      }`}
                    >
                      {review.status} · {review.findingCount}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
          {activeReview && (
            <p className="mt-3 text-sm text-slate-600">Analysis in progress… findings update when it completes.</p>
          )}
        </Card>
      )}

      {selectedReview && (
        <Card
          title={`Findings (${visibleFindings.length})`}
          subtitle={`From ${selectedProject?.name ?? 'the selected project'} · run ${selectedReview.status}. Open a finding to request a fix.`}
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
                <li key={finding.id}>
                  <Link
                    to={`/projects/${selectedProjectId}/reviews/${selectedReview.id}/findings/${finding.id}`}
                    aria-label={`Investigate finding ${finding.rule ?? finding.title}`}
                    className="block space-y-1.5 rounded-xl border border-indigo-100 bg-white px-4 py-3 shadow-sm shadow-indigo-100/50 transition-all hover:border-indigo-200 hover:shadow focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={severityTone(finding.severity)}>{finding.severity}</Badge>
                      <Badge tone={sourceTone(finding.source)}>{finding.source}</Badge>
                      <Badge tone="violet">{finding.analyzer ?? 'unknown'}</Badge>
                      <span className="font-mono text-sm font-semibold text-indigo-950">
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
                    <p className="font-mono text-xs text-slate-500">
                      {finding.filePath ?? '—'}
                      {finding.lineStart != null ? `:${finding.lineStart}` : ''}
                      <span className="ml-2 font-sans font-semibold text-indigo-600">
                        Investigate and fix →
                      </span>
                    </p>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}
    </div>
  );
}
