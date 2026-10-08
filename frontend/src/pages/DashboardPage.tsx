import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { listProjects } from '../api/projects';
import { getDashboardSummary, getCheckStatusInfo, getHealthScoreColor, type DashboardSummaryResponse } from '../api/checks';
import type { ProjectResponse, FeatureSummary, ProjectHealthScore, RunSummary } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  PageHeader,
  Progress,
  SkeletonList,
  Stat,
  primaryButtonClass,
  quietButtonClass,
} from '../components/ui';
import {
  ProjectAvatar,
  formatDate,
} from '../components/workflow';

interface ProjectSummary {
  project: ProjectResponse;
  healthScore: ProjectHealthScore | null;
  checks: {
    generate: FeatureSummary | null;
    review: FeatureSummary | null;
    fix: FeatureSummary | null;
    verify: FeatureSummary | null;
  };
}

/** Dashboard backed by unified check runs and health scores. */
export function DashboardPage() {
  const { token } = useAuth();
  const [summary, setSummary] = useState<DashboardSummaryResponse | null>(null);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [totalProjects, setTotalProjects] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [wakeUpMessage, setWakeUpMessage] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (!token) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    setWakeUpMessage(null);
    try {
      const [projectsPage, dashboardSummary] = await Promise.all([
        listProjects(apiClient(), token, { size: 20 }),
        getDashboardSummary(apiClient(), token),
      ]);
      setTotalProjects(projectsPage.totalElements);
      setSummary(dashboardSummary);

      // Build project summaries with health scores
      const projectSummaries: ProjectSummary[] = projectsPage.content.map(project => {
        const healthScore = dashboardSummary.latestHealthScore?.projectId === project.id
          ? dashboardSummary.latestHealthScore
          : null;
        
        const checks = {
          generate: dashboardSummary.features.find(f => f.feature === 'GENERATE') ?? null,
          review: dashboardSummary.features.find(f => f.feature === 'REVIEW') ?? null,
          fix: dashboardSummary.features.find(f => f.feature === 'FIX') ?? null,
          verify: dashboardSummary.features.find(f => f.feature === 'VERIFY') ?? null,
        };

        return { project, healthScore, checks };
      });
      setProjects(projectSummaries);
    } catch (err) {
      if (err instanceof ApiError && (err.status === 0 || err.status === 503)) {
        setWakeUpMessage('Backend waking up (free tier)… retrying in 3s');
        setTimeout(() => void reload(), 3000);
      } else {
        setError(err instanceof ApiError ? err.message : 'Could not load the dashboard.');
      }
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // Computed from summary
  const criticalHigh = summary?.openIssuesBySeverity
    .filter((s: { severity: string; count: number }) => s.severity === 'CRITICAL' || s.severity === 'HIGH')
    .reduce((sum: number, s: { count: number }) => sum + s.count, 0) ?? 0;

  const totalFindings = summary?.openIssuesBySeverity
    .reduce((sum: number, s: { count: number }) => sum + s.count, 0) ?? 0;

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
      {wakeUpMessage && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 animate-pulse">
          {wakeUpMessage}
        </div>
      )}

      {/* 4 Feature Cards with Live Status */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {summary?.features.map((feature: { feature: string; totalRuns: number; running: number; failed: number; succeeded: number; skipped: number; lastRunAt: string | null }) => {
          const statusInfo = getCheckStatusInfo(
            (feature.running > 0 ? 'RUNNING' :
              feature.failed > 0 ? 'FAILED' :
                feature.succeeded > 0 ? 'SUCCEEDED' :
                  feature.skipped > 0 ? 'SKIPPED' : 'QUEUED') as any
          );
          return (
            <Card
              key={feature.feature}
              title={feature.feature.charAt(0) + feature.feature.slice(1).toLowerCase()}
              subtitle={`Runs: ${feature.totalRuns} · Last: ${feature.lastRunAt ? formatDate(feature.lastRunAt) : 'Never'}`}
            >
              <div className="space-y-3">
                <div className="flex items-center gap-3">
                  <Badge tone={statusInfo.color}>{statusInfo.icon} {statusInfo.label}</Badge>
                  <div className="flex-1">
                    <div className="flex justify-between text-xs font-semibold text-slate-500 mb-1">
                      <span>Succeeded: {feature.succeeded}</span>
                      <span>Failed: {feature.failed}</span>
                      <span>Running: {feature.running}</span>
                    </div>
                    <Progress value={feature.totalRuns > 0 ? Math.round((feature.succeeded / feature.totalRuns) * 100) : 0} className="h-2" />
                  </div>
                </div>
                <Link
                  to="/projects"
                  className={quietButtonClass}
                  style={{ fontSize: '0.875rem' }}
                >
                  View projects →
                </Link>
              </div>
            </Card>
          );
        })}
      </div>

      {/* Health Score & Pipeline Progress */}
      <div className="grid gap-4 lg:grid-cols-5">
        <Card title="Health Score">
          {loading ? (
            <LoadingState label="Loading…" />
          ) : (
            <Stat
              label="Overall"
              value={`${summary?.latestHealthScore?.score ?? 0}`}
              hint={
                <span className={`text-sm font-semibold ${
                  getHealthScoreColor(summary?.latestHealthScore?.score ?? 0) === 'emerald' ? 'text-emerald-600' :
                  getHealthScoreColor(summary?.latestHealthScore?.score ?? 0) === 'amber' ? 'text-amber-600' : 'text-red-600'
                }`}>
                  {getHealthScoreColor(summary?.latestHealthScore?.score ?? 0).toUpperCase()}
                </span>
              }
            />
          )}
        </Card>
        <Card title="Open Issues">
          {loading ? (
            <LoadingState label="Loading…" />
          ) : (
            <Stat
              label="Total"
              value={totalFindings}
              hint={
                <span>{criticalHigh} critical/high</span>
              }
            />
          )}
        </Card>
        <Card title="Pipeline">
          {loading ? (
            <LoadingState label="Loading…" />
          ) : (
            <Stat
              label="Fixed / Verified"
              value={`${summary?.features.find((f: { feature: string; succeeded: number }) => f.feature === 'FIX')?.succeeded ?? 0} / ${summary?.features.find((f: { feature: string; succeeded: number }) => f.feature === 'VERIFY')?.succeeded ?? 0}`}
              hint={
                <span>
                  Open: {summary?.features.find((f: { feature: string; totalRuns: number }) => f.feature === 'REVIEW')?.totalRuns ?? 0}
                </span>
              }
            />
          )}
        </Card>
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
        <Card title="Activity">
          {loading ? (
            <LoadingState label="Loading…" />
          ) : (
            <Stat
              label="Recent Runs"
              value={summary?.lastRuns.length ?? 0}
              hint={<span>Last 10 runs across all features</span>}
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
          in your projects.{' '}
          <Link to="/projects" className="font-semibold underline hover:text-red-900">
            Open Projects
          </Link>
          .
        </div>
      )}

      {/* Project List with Per-Project Status Chips */}
      <Card title="Your Projects" subtitle="Per-project feature status and health score">
        {loading ? (
          <>
            <LoadingState label="Loading…" />
            <SkeletonList rows={5} />
          </>
        ) : projects.length === 0 ? (
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
            {projects.map(({ project, healthScore, checks }) => (
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
                      {project.fileCount} files · Health: {healthScore?.score ?? 0}
                    </span>
                  </span>
                  <span className="ml-auto shrink-0 flex flex-wrap gap-1">
                    {[
                      { key: 'generate', label: 'Gen', check: checks.generate },
                      { key: 'review', label: 'Rev', check: checks.review },
                      { key: 'fix', label: 'Fix', check: checks.fix },
                      { key: 'verify', label: 'Ver', check: checks.verify },
                    ].map(({ key, label, check }) => (
                      <Badge
                        key={key}
                        tone={
                          (check?.running ?? 0) > 0 ? 'blue' :
                            (check?.failed ?? 0) > 0 ? 'red' :
                              (check?.succeeded ?? 0) > 0 ? 'green' : 'gray'
                        }
                      >
                        {label}
                      </Badge>
                    ))}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {/* Recent Activity */}
      <Card title="Recent Activity" subtitle="Last 10 runs across all features">
        {loading ? (
          <LoadingState label="Loading…" />
        ) : summary?.lastRuns.length === 0 ? (
          <EmptyState
            title="No runs yet."
            body="Start a generation, review, fix, or verify to see activity here."
          />
        ) : (
          <ul className="space-y-3">
            {summary?.lastRuns.map((run: RunSummary) => (
              <li
                key={run.runId}
                className="flex items-start gap-3 rounded-xl border border-indigo-100 bg-indigo-50/40 px-3 py-2.5"
              >
                <Badge tone={
                  run.status === 'SUCCEEDED' ? 'green' :
                    run.status === 'FAILED' ? 'red' :
                      run.status === 'RUNNING' ? 'blue' : 'gray'
                }>
                  {run.feature}
                </Badge>
                <span className="min-w-0 flex-1 text-sm">
                  <span className="block font-medium text-indigo-950">
                    {run.feature} {run.status.toLowerCase()}
                  </span>
<span className="block text-xs text-slate-500">
                      {run.startedAt ? formatDate(run.startedAt) : 'N/A'} · {run.totalIssues} issues
                    </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {/* Trend Sparkline */}
      {summary?.trend && summary.trend.length > 1 && (
        <Card title="Health Trend (Last 10)" subtitle="Overall health score over time">
          <div className="h-32 relative">
            <canvas
              id="health-trend"
              className="w-full h-full"
              ref={el => {
                if (el) drawTrend(el, summary.trend!);
              }}
            />
          </div>
        </Card>
      )}
    </div>
  );
}

function drawTrend(canvas: HTMLCanvasElement, trend: Array<{ timestamp: string; totalScore: number }>) {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx.scale(dpr, dpr);

  const width = rect.width;
  const height = rect.height;
  const padding = 20;
  const innerWidth = width - padding * 2;
  const innerHeight = height - padding * 2;

  // Clear
  ctx.clearRect(0, 0, width, height);

  // Grid
  ctx.strokeStyle = '#e2e8f0';
  ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const y = padding + (innerHeight / 4) * i;
    ctx.beginPath();
    ctx.moveTo(padding, y);
    ctx.lineTo(width - padding, y);
    ctx.stroke();
  }

  // Draw line
  const scores = trend.map(t => t.totalScore);
  const minScore = Math.min(0, ...scores);
  const maxScore = Math.max(100, ...scores);
  const range = maxScore - minScore || 1;

  ctx.strokeStyle = '#6366f1';
  ctx.lineWidth = 2;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();

  trend.forEach((point, i) => {
    const x = padding + (innerWidth / (trend.length - 1)) * i;
    const y = padding + innerHeight - ((point.totalScore - minScore) / range) * innerHeight;
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });
  ctx.stroke();

  // Draw points
  ctx.fillStyle = '#6366f1';
  trend.forEach((point, i) => {
    const x = padding + (innerWidth / (trend.length - 1)) * i;
    const y = padding + innerHeight - ((point.totalScore - minScore) / range) * innerHeight;
    ctx.beginPath();
    ctx.arc(x, y, 3, 0, Math.PI * 2);
    ctx.fill();
  });

  // Labels
  ctx.fillStyle = '#64748b';
  ctx.font = '10px system-ui';
  ctx.textAlign = 'center';
  ctx.fillText('100', padding - 15, padding + 10);
  ctx.fillText('0', padding - 15, padding + innerHeight + 4);
}