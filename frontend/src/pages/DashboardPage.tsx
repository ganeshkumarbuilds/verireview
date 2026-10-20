import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { getDashboardSummary, type DashboardSummaryResponse } from '../api/checks';
import { apiClient, useAuth } from '../auth/AuthContext';
import {
  EmptyState,
  ErrorAlert,
  LoadingState,
  primaryButtonClass,
  secondaryButtonClass,
  quietButtonClass,
} from '../components/ui';
import { formatRelativeTime } from '../components/workflow';
import {
  FolderOpen,
  SearchCode,
  ShieldCheck,
  Sparkles,
  Wrench,
} from 'lucide-react';

const REFRESH_INTERVAL_MS = 30_000;

interface Feature {
  title: string;
  description: string;
  href: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
}

/**
 * The four pipeline features. Every card opens its own page; no card mixes
 * another feature's inputs.
 */
const FEATURES: Feature[] = [
  {
    title: 'Generate',
    description: 'Create a new project from a prompt and tech stack.',
    href: '/generate',
    label: 'Start generation',
    icon: Sparkles,
  },
  {
    title: 'Review',
    description: 'Bring in code and investigate findings.',
    href: '/review',
    label: 'Open review',
    icon: SearchCode,
  },
  {
    title: 'Fix',
    description: 'Request and apply fixes for open findings.',
    href: '/fix',
    label: 'Fix findings',
    icon: Wrench,
  },
  {
    title: 'Verify',
    description: 'Build, test, and verify fixes in isolated sandboxes.',
    href: '/verify',
    label: 'Run verification',
    icon: ShieldCheck,
  },
];

function FeatureCard({ icon: Icon, title, description, href, label }: Feature) {
  return (
    <div className="flex h-full flex-col rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <div className="mb-4 flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
        <Icon className="h-5 w-5" aria-hidden="true" />
      </div>
      <h3 className="text-base font-semibold text-slate-900">{title}</h3>
      <p className="mt-1 flex-1 text-sm leading-relaxed text-slate-500">{description}</p>
      <Link to={href} className={`${secondaryButtonClass} mt-4 w-full`}>
        {label}
      </Link>
    </div>
  );
}

function StatTile({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">{label}</p>
      <p className="mt-2 text-3xl font-bold tabular-nums tracking-tight text-slate-900">
        {value.toLocaleString()}
      </p>
    </div>
  );
}

export function DashboardPage() {
  const { token } = useAuth();
  const [summary, setSummary] = useState<DashboardSummaryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [visible, setVisible] = useState(true);

  const reload = useCallback(async () => {
    if (!token) {
      setLoading(false);
      return;
    }
    setError(null);
    try {
      const data = await getDashboardSummary(apiClient(), token);
      setSummary(data);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load the dashboard.');
    } finally {
      setLoading(false);
    }
  }, [token]);

  // Refresh on mount and every 30s while the tab is visible.
  useEffect(() => {
    if (!visible) return undefined;
    void reload();
    const timer = window.setInterval(() => {
      void reload();
    }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [visible, reload]);

  useEffect(() => {
    const handleVisibility = () => setVisible(!document.hidden);
    document.addEventListener('visibilitychange', handleVisibility);
    return () => document.removeEventListener('visibilitychange', handleVisibility);
  }, []);

  const handleRetry = useCallback(() => {
    setLoading(true);
    void reload();
  }, [reload]);

  // Null-safe everywhere: missing data reads as 0 / empty.
  const totalFilesVerified = summary?.totalFilesVerified ?? 0;
  const totalIssuesFound = summary?.totalIssuesFound ?? 0;
  const totalIssuesFixed = summary?.totalIssuesFixed ?? 0;
  const projects = summary?.projects ?? [];

  return (
    <div className="mx-auto max-w-[1100px] space-y-8 px-4 py-8 sm:px-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">Dashboard</h1>
          <p className="mt-1 text-sm leading-relaxed text-slate-600">
            Generate, review, fix, and verify your code in one place.
          </p>
        </div>
        <Link to="/review" className={`${primaryButtonClass} shrink-0`}>
          Review existing code
        </Link>
      </div>

      {error && <ErrorAlert message={error} onRetry={handleRetry} />}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {FEATURES.map((feature) => (
          <FeatureCard key={feature.title} {...feature} />
        ))}
      </div>

      <section className="space-y-4" aria-labelledby="overall-heading">
        <h2 id="overall-heading" className="text-lg font-semibold text-slate-900">
          Overall
        </h2>
        <div className="grid gap-4 sm:grid-cols-3">
          <StatTile label="Files verified" value={totalFilesVerified} />
          <StatTile label="Issues found" value={totalIssuesFound} />
          <StatTile label="Issues fixed" value={totalIssuesFixed} />
        </div>
      </section>

      <section className="space-y-4" aria-labelledby="projects-heading">
        <h2 id="projects-heading" className="text-lg font-semibold text-slate-900">
          Your Projects
        </h2>
        <div className="rounded-2xl border border-slate-200 bg-white shadow-sm">
          {loading && !summary ? (
            <div className="px-5 py-4">
              <LoadingState label="Loading projects…" />
            </div>
          ) : error && !summary ? (
            <div className="px-5 py-6">
              <p className="text-sm text-slate-600">
                Projects could not be loaded. Fix the error above and retry.
              </p>
            </div>
          ) : projects.length === 0 ? (
            <div className="p-5">
              <EmptyState
                title="No projects yet"
                body="Bring in a ZIP, a GitHub repository or pasted code — or generate a new project."
                action={
                  <Link to="/review" className={primaryButtonClass}>
                    Add code to review
                  </Link>
                }
              />
            </div>
          ) : (
            <>
              <div className="hidden overflow-x-auto lg:block">
                <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-200 text-left text-xs font-semibold uppercase tracking-wider text-slate-500">
                    <th scope="col" className="px-5 py-3">Project</th>
                    <th scope="col" className="px-5 py-3">Last activity</th>
                    <th scope="col" className="px-5 py-3">Issues found / fixed</th>
                    <th scope="col" className="px-5 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {projects.map((project) => (
                    <tr key={project.id} className="hover:bg-slate-50">
                      <td className="max-w-[16rem] truncate px-5 py-3">
                        <Link
                          to={`/review?project=${project.id}`}
                          className="font-medium text-slate-900 hover:text-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                        >
                          {project.name}
                        </Link>
                      </td>
                      <td className="whitespace-nowrap px-5 py-3 text-slate-500">
                        {project.lastActivityAt ? formatRelativeTime(project.lastActivityAt) : '—'}
                      </td>
                      <td className="whitespace-nowrap px-5 py-3 text-slate-700">
                        <span className="font-mono tabular-nums">{project.issuesFound.toLocaleString()}</span>
                        <span className="mx-1 text-slate-400">/</span>
                        <span className="font-mono tabular-nums">{project.issuesFixed.toLocaleString()}</span>
                      </td>
                      <td className="whitespace-nowrap px-5 py-3 text-right">
                        <Link
                          to={`/review?project=${project.id}`}
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
            <ul className="divide-y divide-slate-100 lg:hidden">
              {projects.map((project) => (
                <li
                  key={project.id}
                  className="flex flex-wrap items-start justify-between gap-3 px-4 py-3"
                >
                  <div className="min-w-0 flex-1">
                    <Link
                      to={`/review?project=${project.id}`}
                      className="break-words font-medium text-slate-900 hover:text-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                    >
                      {project.name}
                    </Link>
                    <p className="mt-0.5 text-xs text-slate-500">
                      {project.lastActivityAt
                        ? `Last activity ${formatRelativeTime(project.lastActivityAt)}`
                        : 'No activity yet'}
                    </p>
                    <p className="mt-0.5 text-xs text-slate-500">
                      {project.issuesFound.toLocaleString()} issues found ·{' '}
                      {project.issuesFixed.toLocaleString()} fixed
                    </p>
                  </div>
                  <Link
                    to={`/review?project=${project.id}`}
                    className={quietButtonClass}
                    aria-label={`Review ${project.name}`}
                  >
                    Review
                  </Link>
                </li>
              ))}
            </ul>
            </>
          )}
        </div>
      </section>

      <p className="flex items-center gap-2 text-xs text-slate-400">
        <FolderOpen className="h-3.5 w-3.5" aria-hidden="true" />
        Totals refresh every 30 seconds while this tab is open.
      </p>
    </div>
  );
}
