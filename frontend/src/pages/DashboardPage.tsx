import { useCallback, useEffect, useState, useRef } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { listProjects } from '../api/projects';
import { getDashboardSummary, getCheckStatusInfo, type DashboardSummaryResponse } from '../api/checks';
import type { ProjectResponse, FeatureSummary, ProjectHealthScore, RunSummary } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Card,
  CircularGauge,
  EmptyState,
  ErrorAlert,
  PageHeader,
  Progress,
  SkeletonCard,
  SkeletonList,
  StatCard,
  StatChip,
  primaryButtonClass,
  quietButtonClass,
} from '../components/ui';
import {
  ProjectAvatar,
  formatRelativeTime,
  severityTone,
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
  const navigate = useNavigate();
  const [summary, setSummary] = useState<DashboardSummaryResponse | null>(null);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [totalProjects, setTotalProjects] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [wakeUpMessage, setWakeUpMessage] = useState<string | null>(null);
  const refreshTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const hasRunningRuns = summary?.features.some(f => f.running > 0) ?? false;

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

  // Auto-refresh every 30s while a run is in progress
  useEffect(() => {
    if (hasRunningRuns) {
      refreshTimerRef.current = setInterval(() => {
        void reload();
      }, 30_000);
    }
    return () => {
      if (refreshTimerRef.current) {
        clearInterval(refreshTimerRef.current);
      }
    };
  }, [hasRunningRuns, reload]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // Computed from summary
  const criticalHigh = summary?.openIssuesBySeverity
    .filter((s: { severity: string; count: number }) => s.severity === 'CRITICAL' || s.severity === 'HIGH')
    .reduce((sum: number, s: { count: number }) => sum + s.count, 0) ?? 0;

  const totalFindings = summary?.openIssuesBySeverity
    .reduce((sum: number, s: { count: number }) => sum + s.count, 0) ?? 0;

  const severityBreakdown = summary?.openIssuesBySeverity.map(s => ({
    label: s.severity,
    count: s.count,
    tone: severityTone(s.severity) as 'red' | 'amber' | 'blue' | 'green' | 'gray',
  })) ?? [];

  // Health score trend direction
  const healthScore = summary?.latestHealthScore?.score ?? null;
  const trend = summary?.trend ?? [];
  let trendDirection: 'up' | 'down' | 'neutral' = 'neutral';
  if (trend.length >= 2) {
    const last = trend[trend.length - 1].totalScore;
    const prev = trend[trend.length - 2].totalScore;
    if (last > prev + 2) trendDirection = 'up';
    else if (last < prev - 2) trendDirection = 'down';
  }

  const handleRetry = useCallback(() => {
    setError(null);
    void reload();
  }, [reload]);

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
      {error && <ErrorAlert message={error} onRetry={handleRetry} />}
      {wakeUpMessage && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 animate-pulse" role="status">
          {wakeUpMessage}
        </div>
      )}

      {/* 4 Pipeline Feature Cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {summary?.features.map((feature: FeatureSummary) => {
          const statusInfo = getCheckStatusInfo(
            (feature.running > 0 ? 'RUNNING' :
              feature.failed > 0 ? 'FAILED' :
                feature.succeeded > 0 ? 'SUCCEEDED' :
                  feature.skipped > 0 ? 'SKIPPED' : 'QUEUED') as any
          );
          const hasRuns = feature.totalRuns > 0;
          const progressPct = hasRuns ? Math.round((feature.succeeded / feature.totalRuns) * 100) : 0;

          return (
            <Card
              key={feature.feature}
              title={feature.feature.charAt(0) + feature.feature.slice(1).toLowerCase()}
              subtitle={hasRuns ? `Runs: ${feature.totalRuns} · Last: ${feature.lastRunAt ? formatRelativeTime(feature.lastRunAt) : 'Never'}` : 'Never run'}
              actions={<Badge tone={statusInfo.color}>{statusInfo.icon} {statusInfo.label}</Badge>}
            >
              <div className="space-y-3">
                {/* Three stat chips in one row */}
                <div className="grid grid-cols-3 gap-2">
                  <StatChip label="Succeeded" value={feature.succeeded} tone="green" />
                  <StatChip label="Failed" value={feature.failed} tone="red" />
                  <StatChip label="Running" value={feature.running} tone="blue" />
                </div>

                {/* Progress bar */}
                <div className="space-y-1">
                  <div className="flex justify-between text-xs font-medium text-slate-500">
                    <span>Progress</span>
                    <span>{progressPct}%</span>
                  </div>
                  <Progress value={progressPct} className="h-2" />
                </div>

                {/* Action button */}
                <div className="flex gap-2 pt-1">
                  {hasRuns ? (
                    <Link to="/projects" className={quietButtonClass}>
                      View projects →
                    </Link>
                  ) : (
                    <Link to="/projects" className={primaryButtonClass}>
                      Start {feature.feature.charAt(0) + feature.feature.slice(1).toLowerCase()}
                    </Link>
                  )}
                </div>
              </div>
            </Card>
          );
        })}
      </div>

      {/* Health Score & Pipeline Progress - 5 stat cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5 items-stretch">
        <StatCard
          title="Health Score"
          value={<CircularGauge value={healthScore} size={80} strokeWidth={6} trendDirection={trendDirection} />}
          subtitle={<span className="text-xs text-slate-500">Overall project health</span>}
          onClick={() => navigate('/projects')}
        />

        <StatCard
          title="Open Issues"
          value={totalFindings}
          subtitle={<span className="text-sm text-slate-500">{criticalHigh} critical/high</span>}
          severityBreakdown={severityBreakdown}
          onClick={() => navigate('/projects')}
        />

        <StatCard
          title="Pipeline"
          value={
            <>
              {summary?.features.find(f => f.feature === 'FIX')?.succeeded ?? 0} / {summary?.features.find(f => f.feature === 'VERIFY')?.succeeded ?? 0}
            </>
          }
          subtitle={<span className="text-sm text-slate-500">Fixed / Verified · Open: {summary?.features.find(f => f.feature === 'REVIEW')?.totalRuns ?? 0}</span>}
          onClick={() => navigate('/projects')}
        />

        <StatCard
          title="Projects"
          value={totalProjects}
          subtitle={<Link to="/projects" className={quietButtonClass}>Open projects</Link>}
          onClick={() => navigate('/projects')}
        />

        <StatCard
          title="Activity"
          value={summary?.lastRuns.length ?? 0}
          subtitle={<span className="text-sm text-slate-500">Last 10 runs across all features</span>}
          onClick={() => navigate('/projects')}
        />
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

      {/* Your Projects */}
      <Card title="Your Projects" subtitle="Per-project feature status and health score">
        {loading ? (
          <div className="space-y-3">
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
          </div>
        ) : projects.length === 0 ? (
          <EmptyState
            title="No projects yet"
            body="Create a project shell or upload a ZIP to start your first analysis."
            icon="📦"
            action={
              <Link to="/projects" className={primaryButtonClass}>
                Create or upload project
              </Link>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm" role="grid">
              <thead>
                <tr className="border-b border-indigo-100 text-left text-xs font-semibold uppercase tracking-wider text-slate-500">
                  <th className="pb-2 pr-4">Project</th>
                  <th className="pb-2 pr-4">Last Run</th>
                  <th className="pb-2 pr-4">Status</th>
                  <th className="pb-2 pr-4">Health</th>
                  <th className="pb-2 pr-4">Open Issues</th>
                  <th className="pb-2">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-indigo-50">
                {projects.map(({ project, healthScore, checks }) => (
                  <tr key={project.id} className="hover:bg-indigo-50/50 transition-colors">
                    <td className="py-3 pr-4">
                      <Link to={`/projects/${project.id}`} className="flex items-center gap-3">
                        <ProjectAvatar name={project.name} />
                        <span className="font-medium text-indigo-950 truncate max-w-[200px]">{project.name}</span>
                      </Link>
                    </td>
                    <td className="py-3 pr-4 text-slate-500">
                      {checks.generate?.lastRunAt || checks.review?.lastRunAt || checks.fix?.lastRunAt || checks.verify?.lastRunAt
                        ? formatRelativeTime(
                            ([checks.generate, checks.review, checks.fix, checks.verify]
                              .filter(Boolean) as FeatureSummary[])
                              .filter(c => c.lastRunAt)
                              .sort((a, b) => new Date(b.lastRunAt!).getTime() - new Date(a.lastRunAt!).getTime())[0].lastRunAt!
                          )
                        : '—'}
                    </td>
                    <td className="py-3 pr-4">
                      <div className="flex flex-wrap gap-1">
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
                      </div>
                    </td>
                    <td className="py-3 pr-4">
                      {healthScore ? (
                        <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold ${
                          healthScore.score >= 80 ? 'bg-emerald-50 text-emerald-700' :
                          healthScore.score >= 50 ? 'bg-amber-50 text-amber-700' : 'bg-red-50 text-red-700'
                        }`}>
                          {healthScore.score}
                        </span>
                      ) : (
                        <span className="text-slate-400">—</span>
                      )}
                    </td>
                    <td className="py-3 pr-4 text-slate-600">
                      {healthScore ? healthScore.openFindingsTotal : 0}
                    </td>
                    <td className="py-3">
                      <Link
                        to={`/projects/${project.id}`}
                        className={quietButtonClass}
                        aria-label={`Review ${project.name}`}
                      >
                        Review
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Recent Activity */}
      <Card title="Recent Activity" subtitle="Last 10 runs across all features">
        {loading ? (
          <SkeletonList rows={5} />
        ) : summary?.lastRuns.length === 0 ? (
          <EmptyState
            title="No runs yet"
            body="Start a generation, review, fix, or verify to see activity here."
            icon="📋"
          />
        ) : (
          <ul className="space-y-2" role="list">
            {summary?.lastRuns.map((run: RunSummary) => (
              <li
                key={run.runId}
                className="flex items-center gap-3 rounded-xl border border-indigo-100 bg-indigo-50/40 px-3 py-2.5"
              >
                <Badge tone={
                  run.status === 'SUCCEEDED' ? 'green' :
                    run.status === 'FAILED' ? 'red' :
                      run.status === 'RUNNING' ? 'blue' : 'gray'
                }>
                  {run.feature}
                </Badge>
                <span className="min-w-0 flex-1 text-sm">
                  <span className="block font-medium text-indigo-950 truncate">
                    {run.feature} {run.status.toLowerCase()}
                  </span>
                  <span className="block text-xs text-slate-500">
                    {run.startedAt ? formatRelativeTime(run.startedAt) : 'N/A'} · {run.totalIssues} issues
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {/* Health Trend Sparkline */}
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

  // Gradient for area under curve
  const gradient = ctx.createLinearGradient(0, padding, 0, padding + innerHeight);
  gradient.addColorStop(0, 'rgba(99, 102, 241, 0.15)');
  gradient.addColorStop(1, 'rgba(99, 102, 241, 0)');

  ctx.fillStyle = gradient;
  ctx.beginPath();
  ctx.moveTo(padding, padding + innerHeight);

  trend.forEach((point, i) => {
    const x = padding + (innerWidth / (trend.length - 1)) * i;
    const y = padding + innerHeight - ((point.totalScore - minScore) / range) * innerHeight;
    if (i === 0) ctx.lineTo(x, y);
    else ctx.lineTo(x, y);
  });

  ctx.lineTo(padding + innerWidth, padding + innerHeight);
  ctx.closePath();
  ctx.fill();

  // Draw line
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