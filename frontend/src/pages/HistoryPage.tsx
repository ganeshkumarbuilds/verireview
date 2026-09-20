import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { isTerminal, listReviews } from '../api/analysis';
import { listProjects } from '../api/projects';
import type { ProjectResponse, ReviewResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  PageHeader,
  SkeletonList,
  selectClass,
} from '../components/ui';
import { ProjectAvatar, WorkspaceCrumb, formatDate } from '../components/workflow';

interface HistoryEntry {
  key: string;
  project: ProjectResponse;
  review: ReviewResponse;
}

function entryTone(status: string): 'gray' | 'blue' | 'green' | 'red' {
  switch (status) {
    case 'COMPLETED':
      return 'green';
    case 'FAILED':
      return 'red';
    default:
      return 'blue';
  }
}

function describeEntry(entry: HistoryEntry): string {
  const { review } = entry;
  if (review.status === 'COMPLETED') {
    return `Analysis completed with ${review.findingCount} findings.`;
  }
  if (review.status === 'FAILED') {
    return review.error ?? 'Analysis failed before producing findings.';
  }
  return 'Analysis running — findings update when it completes.';
}

/**
 * Activity/audit timeline built from real review runs (existing list
 * endpoints only). There is no global history endpoint for fix
 * requests, patches, executions, or verifications, so those stay
 * per-finding in the Review workspace — this page says so instead of
 * inventing records.
 */
export function HistoryPage() {
  const { token } = useAuth();
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [projectCount, setProjectCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState('ALL');

  const reload = useCallback(async () => {
    if (!token) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const page = await listProjects(apiClient(), token, { size: 20 });
      setProjectCount(page.totalElements);
      const settled = await Promise.allSettled(
        page.content.map(async (project) => {
          const history = await listReviews(apiClient(), token, project.id);
          return history.content.map((review) => ({
            key: review.id,
            project,
            review,
          }));
        }),
      );
      const all: HistoryEntry[] = [];
      for (const result of settled) {
        if (result.status === 'fulfilled') {
          all.push(...result.value);
        }
      }
      all.sort((a, b) => b.review.createdAt.localeCompare(a.review.createdAt));
      setEntries(all);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load history.');
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const visible = entries.filter((entry) => {
    if (statusFilter === 'ALL') return true;
    if (statusFilter === 'ACTIVE') return !isTerminal(entry.review.status);
    return entry.review.status === statusFilter;
  });

  return (
    <div className="space-y-6">
      <WorkspaceCrumb items={[{ label: 'History' }]} />
      <PageHeader
        title="History"
        description={`Activity across ${projectCount} projects — newest first. Fix, patch, execution, and verification records are tracked per finding in the Review workspace.`}
      />
      {error && <ErrorAlert message={error} onRetry={() => void reload()} />}

      <Card
        title={`Activity (${visible.length})`}
        subtitle="Analysis runs from existing review endpoints. Nothing here is synthesized."
        actions={
          <label className="flex items-center gap-2 text-sm">
            <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Status
            </span>
            <select
              aria-label="Filter history by status"
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
              className={selectClass}
            >
              <option value="ALL">All</option>
              <option value="ACTIVE">Active</option>
              <option value="COMPLETED">Completed</option>
              <option value="FAILED">Failed</option>
            </select>
          </label>
        }
      >
        {loading ? (
          <>
            <LoadingState label="Loading history…" />
            <SkeletonList rows={5} />
          </>
        ) : entries.length === 0 ? (
          <EmptyState
            title="History will appear here."
            body="No analysis runs recorded yet. Open a project and start your first analysis — every run lands on this timeline with its evidence."
            action={
              <Link
                to="/projects"
                className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700"
              >
                Go to Projects
              </Link>
            }
          />
        ) : visible.length === 0 ? (
          <EmptyState
            title="No matching activity."
            body="No runs match the selected status filter."
          />
        ) : (
          <ol className="relative space-y-4 border-l-2 border-indigo-100 pl-5">
            {visible.map((entry) => (
              <li key={entry.key} className="relative">
                <span
                  aria-hidden="true"
                  className={`absolute -left-[27px] top-4 h-3 w-3 rounded-full ring-4 ring-white ${
                    entry.review.status === 'COMPLETED'
                      ? 'bg-emerald-500'
                      : entry.review.status === 'FAILED'
                        ? 'bg-red-500'
                        : 'bg-sky-500'
                  }`}
                />
                <div className="rounded-xl border border-indigo-100 bg-white px-4 py-3 shadow-sm shadow-indigo-100/50">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={entryTone(entry.review.status)}>{entry.review.status}</Badge>
                    <Link
                      to={`/projects/${entry.project.id}`}
                      className="text-sm font-bold text-indigo-950 hover:underline"
                    >
                      {entry.project.name}
                    </Link>
                    <span className="ml-auto text-xs text-slate-500">
                      {formatDate(entry.review.createdAt)}
                    </span>
                  </div>
                  <p className="mt-1.5 flex items-center gap-2 text-sm text-slate-600">
                    <ProjectAvatar name={entry.project.name} />
                    <span>{describeEntry(entry)}</span>
                  </p>
                  <div className="mt-2 flex flex-wrap gap-2">
                    <Link
                      to={`/projects/${entry.project.id}`}
                      className="rounded-lg px-2 py-1 text-xs font-semibold text-indigo-700 hover:bg-indigo-50 hover:underline"
                    >
                      Open project
                    </Link>
                    <Link
                      to={`/review?project=${entry.project.id}`}
                      className="rounded-lg px-2 py-1 text-xs font-semibold text-indigo-700 hover:bg-indigo-50 hover:underline"
                    >
                      Inspect in Review
                    </Link>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        )}
      </Card>

      <Card
        title="Fixes, patches, executions, verifications"
        subtitle="Why they are not listed above."
      >
        <p className="text-sm leading-relaxed text-slate-600">
          The backend exposes fix requests, patches, executions, and
          verifications per finding or per patch — there is no global history
          endpoint for them yet. Open any finding in the{' '}
          <Link to="/review" className="font-semibold text-indigo-700 hover:underline">
            Review workspace
          </Link>{' '}
          to see its Fix Request → Patch → Execution → Verification chain with
          the persisted verdict and evidence.
        </p>
      </Card>
    </div>
  );
}
