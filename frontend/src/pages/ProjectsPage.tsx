import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { listFindings, listReviews } from '../api/analysis';
import { createProject, listProjects, uploadZip } from '../api/projects';
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
  inputClass,
  primaryButtonClass,
} from '../components/ui';
import {
  ProjectAvatar,
  formatDate,
  reviewStatusTone,
} from '../components/workflow';

interface ProjectCardData {
  project: ProjectResponse;
  latest: ReviewResponse | null;
  findings: FindingResponse[];
}

/**
 * Projects workspace: list, manual shells, and ZIP uploads.
 * Renders behind RequireAuth; every call is owner-scoped server-side.
 * Cards show real backend state only (latest analysis + finding summary).
 */
export function ProjectsPage() {
  const { token } = useAuth();
  const [projects, setProjects] = useState<ProjectCardData[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [zipName, setZipName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    if (!token) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const page = await listProjects(apiClient(), token, { size: 50 });
      setTotal(page.totalElements);
      const enriched = await Promise.all(
        page.content.map(async (project) => {
          let latest: ReviewResponse | null = null;
          let findings: FindingResponse[] = [];
          try {
            const history = await listReviews(apiClient(), token, project.id);
            latest = history.content[0] ?? null;
            if (latest) {
              try {
                findings = (await listFindings(apiClient(), token, latest.id)).content;
              } catch {
                findings = [];
              }
            }
          } catch {
            latest = null;
          }
          return { project, latest, findings };
        }),
      );
      setProjects(enriched);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load projects.');
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const handleCreate = async (event: FormEvent) => {
    event.preventDefault();
    if (!token || !name.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await createProject(apiClient(), token, {
        name: name.trim(),
        description: description.trim() || undefined,
      });
      setName('');
      setDescription('');
      await reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create the project.');
    } finally {
      setBusy(false);
    }
  };

  const handleUpload = async (event: FormEvent) => {
    event.preventDefault();
    if (!token || !file || !zipName.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await uploadZip(apiClient(), token, { file, name: zipName.trim() });
      setZipName('');
      setFile(null);
      await reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Upload failed.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title="Projects"
        description="Your codebases. Create an empty shell or import a ZIP, then open a project to analyze and review it. Every project is private to your account."
        actions={
          <Link to="/generate" className={primaryButtonClass}>
            Generate project
          </Link>
        }
      />
      {error && <ErrorAlert message={error} onRetry={() => void reload()} />}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card
          title="New project shell"
          subtitle="Start empty, then upload code or trigger an analysis later."
        >
          <form onSubmit={handleCreate} aria-label="Create project form" className="space-y-3">
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Project name
              </p>
              <input
                aria-label="Project name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="e.g. billing-service"
                maxLength={200}
                className={inputClass}
              />
            </div>
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Description <span className="font-normal normal-case">(optional)</span>
              </p>
              <input
                aria-label="Project description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                placeholder="What does this project do?"
                className={inputClass}
              />
            </div>
            <button
              type="submit"
              disabled={busy || !name.trim()}
              className={primaryButtonClass}
            >
              {busy ? 'Working…' : 'Create project'}
            </button>
          </form>
        </Card>
        <Card
          title="Upload ZIP (max 50 MB, 2000 files)"
          subtitle="Import a codebase snapshot for sandboxed analysis."
        >
          <form onSubmit={handleUpload} aria-label="Upload ZIP form" className="space-y-3">
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Project name
              </p>
              <input
                aria-label="Upload project name"
                value={zipName}
                onChange={(event) => setZipName(event.target.value)}
                placeholder="e.g. billing-service"
                maxLength={200}
                className={inputClass}
              />
            </div>
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                ZIP file
              </p>
              <input
                aria-label="ZIP file"
                type="file"
                accept=".zip"
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                className="w-full text-sm text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-indigo-50 file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-indigo-700 file:transition-colors hover:file:bg-indigo-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
              />
              {file && (
                <p className="mt-1 truncate text-xs text-slate-500">
                  Selected: {file.name} · {(file.size / 1024 / 1024).toFixed(2)} MB
                </p>
              )}
            </div>
            <button
              type="submit"
              disabled={busy || !file || !zipName.trim()}
              className={primaryButtonClass}
            >
              {busy ? 'Working…' : 'Upload and import'}
            </button>
          </form>
        </Card>
      </div>
      <Card
        title={`Your projects (${total})`}
        subtitle="Status, finding summary, and last analysis — select a project to open its workspace."
      >
        {loading ? (
          <>
            <LoadingState label="Loading…" />
            <SkeletonList rows={4} />
          </>
        ) : projects.length === 0 ? (
          <EmptyState
            title="No projects yet."
            body="Create a shell or upload a ZIP above to get started."
          />
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2">
            {projects.map(({ project, latest, findings }) => {
              const critHigh = findings.filter(
                (finding) => finding.severity === 'CRITICAL' || finding.severity === 'HIGH',
              ).length;
              return (
                <li
                  key={project.id}
                  className="flex flex-col rounded-xl border border-indigo-100 bg-white p-4 shadow-sm shadow-indigo-100/50 transition-colors hover:border-indigo-200"
                >
                  <div className="flex items-start gap-3">
                    <ProjectAvatar name={project.name} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-bold text-indigo-950">{project.name}</p>
                      <p className="mt-0.5 truncate text-xs text-slate-500">
                        {project.language ?? project.sourceType} · {project.fileCount} files
                      </p>
                    </div>
                    {latest ? (
                      <Badge tone={reviewStatusTone(latest.status)}>{latest.status}</Badge>
                    ) : (
                      <Badge tone="gray">NEVER ANALYZED</Badge>
                    )}
                  </div>
                  <p className="mt-3 text-xs leading-relaxed text-slate-600">
                    {latest ? (
                      <>
                        {latest.findingCount} findings
                        {findings.length > 0 && ` · ${critHigh} crit/high`} · last run{' '}
                        {formatDate(latest.createdAt)}
                      </>
                    ) : (
                      'No analysis yet — open the project and run your first analysis.'
                    )}
                  </p>
                  <div className="mt-3 flex items-center gap-2 border-t border-indigo-50 pt-3">
                    <Link
                      to={`/projects/${project.id}`}
                      aria-label={`Open project ${project.name}`}
                      className={primaryButtonClass}
                    >
                      Open
                    </Link>
                    {latest && (
                      <Link
                        to={`/review?project=${project.id}`}
                        aria-label={`Review findings for ${project.name}`}
                        className="rounded-lg px-3 py-2 text-sm font-semibold text-indigo-700 transition-colors hover:bg-indigo-50 hover:text-indigo-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                      >
                        Review findings
                      </Link>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>
    </div>
  );
}
