import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { getFileContent, getProject, listFiles } from '../api/projects';
import {
  isTerminal,
  listFindings,
  listReviews,
  triggerAnalysis,
} from '../api/analysis';
import type {
  FileContentResponse,
  FindingResponse,
  ProjectFileResponse,
  ProjectResponse,
  ReviewResponse,
} from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import { Badge, Card } from '../components/ui';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

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
  useDocumentTitle('Project details');
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
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

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

  useEffect(() => {
    if (selectedReviewId) {
      void reloadFindings(selectedReviewId);
    } else {
      setFindings([]);
    }
  }, [selectedReviewId, reloadFindings]);

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
  const visibleFindings = findings.filter(
    (finding) =>
      (severityFilter === 'ALL' || finding.severity === severityFilter) &&
      (analyzerFilter === 'ALL' || (finding.analyzer ?? 'unknown') === analyzerFilter),
  );

  if (loading) {
    return <p className="text-sm text-slate-500">Loading…</p>;
  }
  if (error && !project) {
    return (
      <p role="alert" className="text-sm text-red-600">
        {error}
      </p>
    );
  }
  if (!project) {
    return null;
  }

  return (
    <div className="space-y-4">
      <Link to="/projects" className="text-sm text-indigo-600 hover:underline">
        ← Projects
      </Link>
      <h1 className="text-xl font-semibold text-slate-900">{project.name}</h1>
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Details">
          <dl className="space-y-1 text-sm">
            <div className="flex gap-2">
              <dt className="text-slate-500">Source</dt>
              <dd>{project.sourceType}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-slate-500">Language</dt>
              <dd>{project.language ?? '—'}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-slate-500">Files</dt>
              <dd>{project.fileCount}</dd>
            </div>
            {project.description && <p className="pt-1">{project.description}</p>}
          </dl>
        </Card>
        <Card title={`Files (${files.length})`}>
          {files.length === 0 ? (
            <p>Empty shell — upload a ZIP to fill it (Phase 5 supports ZIP only).</p>
          ) : (
            <ul className="max-h-64 space-y-1 overflow-y-auto">
              {files.map((file) => (
                <li key={file.id}>
                  <button
                    type="button"
                    onClick={() => void openFile(file.path)}
                    className="w-full truncate text-left text-sm text-indigo-600 hover:underline"
                  >
                    {file.path}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
      {selected && (
        <Card title={selected.path}>
          <pre className="max-h-96 overflow-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-3 font-mono text-xs text-slate-800">
            {selected.content}
          </pre>
          {selected.truncated && <p className="pt-1">Preview truncated at 256 KB.</p>}
        </Card>
      )}

      <Card title="Deterministic analysis">
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={() => void startAnalysis()}
            disabled={starting || activeReview !== null}
            className="rounded-lg bg-indigo-600 px-4 py-1.5 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-60"
          >
            {starting ? 'Starting…' : 'Start analysis'}
          </button>
          {activeReview && (
            <span className="flex items-center gap-2 text-sm">
              <Badge tone={statusTone(activeReview.status)}>{activeReview.status}</Badge>
              <span className="text-slate-500">Analysis in progress…</span>
            </span>
          )}
          {!activeReview && reviews.length === 0 && (
            <span className="text-sm">No analysis yet — findings appear here.</span>
          )}
        </div>
        {analysisError && (
          <p role="alert" className="pt-2 text-sm text-red-600">
            {analysisError}
          </p>
        )}
        {selectedReview && (
          <dl className="grid gap-1 pt-3 text-sm sm:grid-cols-2">
            <div className="flex gap-2">
              <dt className="text-slate-500">Status</dt>
              <dd>
                <Badge tone={statusTone(selectedReview.status)}>{selectedReview.status}</Badge>
              </dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-slate-500">Findings</dt>
              <dd>{selectedReview.findingCount}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-slate-500">Finished</dt>
              <dd>{formatDate(selectedReview.finishedAt)}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-slate-500">Duration</dt>
              <dd>
                {selectedReview.durationMs == null ? '—' : `${selectedReview.durationMs} ms`}
              </dd>
            </div>
          </dl>
        )}
        {selectedReview?.error && (
          <p className="pt-2 text-sm text-amber-700">{selectedReview.error}</p>
        )}
      </Card>

      {reviews.length > 0 && (
        <Card title={`Analysis history (${reviews.length})`}>
          <ul className="divide-y divide-slate-200">
            {reviews.map((review) => (
              <li key={review.id}>
                <button
                  type="button"
                  onClick={() => setSelectedReviewId(review.id)}
                  className={`flex w-full items-center gap-3 py-2 text-left text-sm ${
                    review.id === selectedReviewId ? 'font-medium' : ''
                  }`}
                >
                  <Badge tone={statusTone(review.status)}>{review.status}</Badge>
                  <span className="text-slate-500">{formatDate(review.createdAt)}</span>
                  <span className="ml-auto text-slate-500">
                    {review.findingCount} findings
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {selectedReview && (
        <Card title={`Findings (${visibleFindings.length})`}>
          <div className="mb-3 flex flex-wrap gap-2">
            <label className="flex items-center gap-1.5 text-sm">
              <span className="text-slate-500">Severity</span>
              <select
                aria-label="Filter by severity"
                value={severityFilter}
                onChange={(event) => setSeverityFilter(event.target.value)}
                className="rounded-lg border border-slate-300 bg-white px-2 py-1 text-sm"
              >
                <option value="ALL">All</option>
                <option value="CRITICAL">CRITICAL</option>
                <option value="HIGH">HIGH</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="LOW">LOW</option>
                <option value="INFO">INFO</option>
              </select>
            </label>
            <label className="flex items-center gap-1.5 text-sm">
              <span className="text-slate-500">Analyzer</span>
              <select
                aria-label="Filter by analyzer"
                value={analyzerFilter}
                onChange={(event) => setAnalyzerFilter(event.target.value)}
                className="rounded-lg border border-slate-300 bg-white px-2 py-1 text-sm"
              >
                <option value="ALL">All</option>
                {analyzers.map((analyzer) => (
                  <option key={analyzer} value={analyzer}>
                    {analyzer}
                  </option>
                ))}
              </select>
            </label>
          </div>
          {findings.length === 0 ? (
            <p>
              {selectedReview.status === 'COMPLETED'
                ? 'No findings — this review is clean.'
                : 'Findings will appear here once the analysis completes.'}
            </p>
          ) : visibleFindings.length === 0 ? (
            <p>No findings match the selected filters.</p>
          ) : (
            <ul className="divide-y divide-slate-200">
              {visibleFindings.map((finding) => (
                <li key={finding.id} className="space-y-1 py-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={severityTone(finding.severity)}>{finding.severity}</Badge>
                    <Badge tone="violet">{finding.analyzer ?? 'unknown'}</Badge>
                    <span className="font-mono text-sm font-medium text-slate-800">
                      {finding.rule ?? finding.title}
                    </span>
                  </div>
                  {finding.description && (
                    <p className="text-sm">{finding.description}</p>
                  )}
                  <p className="font-mono text-xs text-slate-500">
                    {finding.filePath ?? '—'}
                    {finding.lineStart != null ? `:${finding.lineStart}` : ''}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}
    </div>
  );
}
