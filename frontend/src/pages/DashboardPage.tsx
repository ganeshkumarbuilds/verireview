import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { isTerminal, listFindings, listReviews } from '../api/analysis';
import { listProjects } from '../api/projects';
import type { FindingResponse, ProjectResponse, ReviewResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  PageHeader,
  SkeletonList,
  Stat,
  primaryButtonClass,
  quietButtonClass,
} from '../components/ui';
import {
  FindingSourcesCard,
  ProjectAvatar,
  formatDate,
  reviewStatusTone,
} from '../components/workflow';

interface RecentProject {
  project: ProjectResponse;
  latest: ReviewResponse | null;
  findings: FindingResponse[];
}

interface ActivityEntry {
  key: string;
  projectId: string;
  projectName: string;
  review: ReviewResponse;
}

/** Dashboard backed by real project/review/finding data (existing endpoints only).
 *  Fix/patch/execution/verification activity has no global list endpoint, so
 *  the dashboard reports what the backend provides: projects, analyses, and
 *  findings. Per-finding fix progress lives in the Review workspace. */
export function DashboardPage() {
  const { token } = useAuth();
  const [recent, setRecent] = useState<RecentProject[]>([]);
  const [totalProjects, setTotalProjects] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (!token) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const page = await listProjects(apiClient(), token, { size: 5 });
      setTotalProjects(page.totalElements);
      const withReviews = await Promise.all(
        page.content.map(async (project) => {
          const history = await listReviews(apiClient(), token, project.id);
          const latest = history.content[0] ?? null;
          let findings: FindingResponse[] = [];
          if (latest) {
            try {
              const findingsPage = await listFindings(apiClient(), token, latest.id);
              findings = findingsPage.content;
            } catch {
              findings = [];
            }
          }
          return { project, latest, findings };
        }),
      );
      setRecent(withReviews);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load the dashboard.');
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const totalFiles = recent.reduce((sum, item) => sum + item.project.fileCount, 0);
  const analyzed = recent.filter((item) => item.latest !== null).length;
  const allFindings = recent.flatMap((item) => item.findings);
  const criticalHigh = allFindings.filter(
    (finding) => finding.severity === 'CRITICAL' || finding.severity === 'HIGH',
  ).length;
  const verifiedFindings = allFindings.filter(
    (finding) => finding.source === 'VERIFIED',
  ).length;
  const activeAnalyses = recent.filter(
    (item) => item.latest !== null && !isTerminal(item.latest.status),
  ).length;

  const activity: ActivityEntry[] = recent
    .filter((item) => item.latest !== null)
    .map((item) => ({
      key: (item.latest as ReviewResponse).id,
      projectId: item.project.id,
      projectName: item.project.name,
      review: item.latest as ReviewResponse,
    }))
    .sort((a, b) => b.review.createdAt.localeCompare(a.review.createdAt))
    .slice(0, 5);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Dashboard"
        description="What is happening with your projects — analyses, findings, and next actions at a glance."
        actions={
          <Link to="/projects" className={primaryButtonClass}>
            Review existing code
          </Link>
        }
      />
      {error && <ErrorAlert message={error} onRetry={() => void reload()} />}

      <Card
        title="Start a workflow"
        subtitle="Generate a new project or review an existing codebase."
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="rounded-xl border border-indigo-100 bg-indigo-50/50 p-4">
            <p className="text-sm font-bold text-indigo-950">Review existing code</p>
            <p className="mt-1 text-sm leading-relaxed text-slate-600">
              Upload or import a project, run the sandboxed analyzers, and work
              findings through Fix → Execute → Verify.
            </p>
            <div className="mt-3">
              <Link to="/projects" className={primaryButtonClass}>
                Open Projects
              </Link>
            </div>
          </div>
          <div className="rounded-xl border border-indigo-100 bg-indigo-50/50 p-4">
            <p className="text-sm font-bold text-indigo-950">Generate a project</p>
            <p className="mt-1 text-sm leading-relaxed text-slate-600">
              Describe a requirement, choose the stack, and let the AI service
              draft a starter project you can review like any other codebase.
            </p>
            <div className="mt-3">
              <Link to="/generate" className={primaryButtonClass}>
                Start generating
              </Link>
            </div>
          </div>
        </div>
      </Card>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card title="Projects">
          {loading ? (
            <LoadingState label="Loading…" />
          ) : (
            <Stat
              label="Total"
              value={totalProjects}
              hint={
                <Link to="/projects" className={quietButtonClass}>
                  Open projects
                </Link>
              }
            />
          )}
        </Card>
        <Card title="Files indexed">
          {loading ? (
            <LoadingState label="Loading…" />
          ) : (
            <Stat label="Total" value={totalFiles} hint={<span>Across your recent projects.</span>} />
          )}
        </Card>
        <Card title="Findings">
          {loading ? (
            <LoadingState label="Loading…" />
          ) : (
            <Stat
              label="In latest reviews"
              value={allFindings.length}
              hint={
                <span>
                  {criticalHigh} critical/high · {verifiedFindings} verified
                </span>
              }
            />
          )}
        </Card>
        <Card title="Analyses">
          {loading ? (
            <LoadingState label="Loading…" />
          ) : (
            <Stat
              label="Coverage"
              value={`${analyzed}/${recent.length}`}
              hint={
                <span>
                  {activeAnalyses > 0
                    ? `${activeAnalyses} running now.`
                    : 'Recent projects with at least one analysis.'}
                </span>
              }
            />
          )}
        </Card>
      </div>

      {criticalHigh > 0 && !loading && (
        <div
          role="alert"
          className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"
        >
          <span className="font-bold">{criticalHigh} critical/high findings</span> need attention
          in your recent reviews.{' '}
          <Link to="/review" className="font-semibold underline hover:text-red-900">
            Open the Review workspace
          </Link>
          .
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-5">
        <div className="lg:col-span-3">
          <Card
            title="Recent projects"
            subtitle="Your five most recent projects with their latest analysis state."
          >
            {loading ? (
              <>
                <LoadingState label="Loading…" />
                <SkeletonList rows={3} />
              </>
            ) : recent.length === 0 ? (
              <EmptyState
                title="No projects yet."
                body="Create a project shell or upload a ZIP to start your first analysis."
                action={
                  <Link to="/projects" className={quietButtonClass}>
                    Create or upload one
                  </Link>
                }
              />
            ) : (
              <ul className="divide-y divide-indigo-50">
                {recent.map(({ project, latest, findings }) => (
                  <li key={project.id}>
                    <Link
                      to={`/projects/${project.id}`}
                      className="flex items-center gap-3 rounded-xl px-3 py-3 transition-colors hover:bg-indigo-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-600"
                    >
                      <ProjectAvatar name={project.name} />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-semibold text-indigo-950">
                          {project.name}
                        </span>
                        <span className="block truncate text-xs text-slate-500">
                          {project.fileCount} files
                          {latest
                            ? ` · ${latest.findingCount} findings · ${findings.filter((finding) => finding.severity === 'CRITICAL' || finding.severity === 'HIGH').length} crit/high`
                            : ''}
                        </span>
                      </span>
                      <span className="ml-auto shrink-0">
                        {latest ? (
                          <Badge tone={reviewStatusTone(latest.status)}>{latest.status}</Badge>
                        ) : (
                          <Badge tone="gray">NEVER ANALYZED</Badge>
                        )}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
        <div className="lg:col-span-2">
          <Card title="Recent activity" subtitle="Latest analysis runs across your projects.">
            {loading ? (
              <>
                <LoadingState label="Loading…" />
                <SkeletonList rows={3} />
              </>
            ) : activity.length === 0 ? (
              <EmptyState
                title="No analysis activity yet."
                body="Run your first analysis from any project to see it here. Fix, patch, and verification activity is tracked per finding in the Review workspace."
              />
            ) : (
              <ul className="space-y-3">
                {activity.map((entry) => (
                  <li
                    key={entry.key}
                    className="flex items-start gap-3 rounded-xl border border-indigo-100 bg-indigo-50/40 px-3 py-2.5"
                  >
                    <span
                      aria-hidden="true"
                      className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${
                        entry.review.status === 'COMPLETED'
                          ? 'bg-emerald-500'
                          : entry.review.status === 'FAILED'
                            ? 'bg-red-500'
                            : 'bg-sky-500'
                      }`}
                    />
                    <span className="min-w-0 flex-1 text-sm">
                      <span className="block font-medium text-indigo-950">
                        Analysis {entry.review.status.toLowerCase()} ·{' '}
                        {entry.review.findingCount} findings
                      </span>
                      <span className="block text-xs text-slate-500">
                        {formatDate(entry.review.createdAt)}
                      </span>
                    </span>
                    <Link
                      to={`/projects/${entry.projectId}`}
                      aria-label={`Open project ${entry.projectName}`}
                      className="shrink-0 rounded-lg px-2 py-1 text-xs font-semibold text-indigo-700 hover:bg-indigo-50 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600"
                    >
                      Open
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>
      <FindingSourcesCard />
    </div>
  );
}
