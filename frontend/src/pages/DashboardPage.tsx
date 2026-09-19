import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { listReviews } from '../api/analysis';
import { listProjects } from '../api/projects';
import type { ProjectResponse, ReviewResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import { Badge, Card } from '../components/ui';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

interface RecentProject {
  project: ProjectResponse;
  latest: ReviewResponse | null;
}

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

/** Dashboard backed by real project/review data (existing endpoints only). */
export function DashboardPage() {
  useDocumentTitle('Dashboard');
  const { token } = useAuth();
  const [recent, setRecent] = useState<RecentProject[]>([]);
  const [totalProjects, setTotalProjects] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (!token) {
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
          return { project, latest: history.content[0] ?? null };
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

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold text-slate-900">Dashboard</h1>
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}
      <div className="grid gap-4 sm:grid-cols-3">
        <Card title="Projects">
          <p className="text-2xl font-semibold text-slate-900">
            {loading ? '—' : totalProjects}
          </p>
          <p>
            <Link to="/projects" className="text-indigo-600 hover:underline">
              Open projects
            </Link>
          </p>
        </Card>
        <Card title="Files indexed">
          <p className="text-2xl font-semibold text-slate-900">{loading ? '—' : totalFiles}</p>
          <p>Across your recent projects.</p>
        </Card>
        <Card title="Analyzed recently">
          <p className="text-2xl font-semibold text-slate-900">
            {loading ? '—' : `${analyzed}/${recent.length}`}
          </p>
          <p>Recent projects with at least one analysis.</p>
        </Card>
      </div>
      <Card title="Recent projects">
        {loading ? (
          <p>Loading…</p>
        ) : recent.length === 0 ? (
          <p>
            No projects yet.{' '}
            <Link to="/projects" className="text-indigo-600 hover:underline">
              Create or upload one
            </Link>{' '}
            to start analyzing.
          </p>
        ) : (
          <ul className="divide-y divide-slate-200">
            {recent.map(({ project, latest }) => (
              <li key={project.id} className="flex items-center gap-3 py-2">
                <Link
                  to={`/projects/${project.id}`}
                  className="truncate text-sm font-medium text-indigo-600 hover:underline"
                >
                  {project.name}
                </Link>
                <span className="ml-auto">
                  {latest ? (
                    <Badge tone={statusTone(latest.status)}>{latest.status}</Badge>
                  ) : (
                    <Badge tone="gray">NEVER ANALYZED</Badge>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Finding sources">
        <div className="flex flex-wrap gap-2">
          <Badge tone="blue">DETERMINISTIC</Badge>
          <Badge tone="violet">AI</Badge>
          <Badge tone="green">VERIFIED</Badge>
        </div>
      </Card>
    </div>
  );
}
