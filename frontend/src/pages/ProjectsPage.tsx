import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { createProject, listProjects, uploadZip } from '../api/projects';
import type { ProjectResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import { Card } from '../components/ui';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/**
 * Phase 5 projects workspace: list, manual shells, and ZIP uploads.
 * Renders behind RequireAuth; every call is owner-scoped server-side.
 */
export function ProjectsPage() {
  useDocumentTitle('Projects');
  const { token } = useAuth();
  const [projects, setProjects] = useState<ProjectResponse[]>([]);
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
      setProjects(page.content);
      setTotal(page.totalElements);
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
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">Projects</h1>
      {error && (
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 p-2 text-sm text-red-700">
          {error}
        </p>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="New project shell">
          <form onSubmit={handleCreate} aria-label="Create project form" className="space-y-2">
            <input
              aria-label="Project name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Project name"
              maxLength={200}
              className="w-full rounded-lg border border-slate-300 bg-white px-2 py-1.5 text-sm text-slate-900 placeholder:text-slate-400"
            />
            <input
              aria-label="Project description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              placeholder="Description (optional)"
              className="w-full rounded-lg border border-slate-300 bg-white px-2 py-1.5 text-sm text-slate-900 placeholder:text-slate-400"
            />
            <button
              type="submit"
              disabled={busy || !name.trim()}
              className="rounded-lg bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-60"
            >
              Create project
            </button>
          </form>
        </Card>
        <Card title="Upload ZIP (max 50 MB, 2000 files)">
          <form onSubmit={handleUpload} aria-label="Upload ZIP form" className="space-y-2">
            <input
              aria-label="Upload project name"
              value={zipName}
              onChange={(event) => setZipName(event.target.value)}
              placeholder="Project name"
              maxLength={200}
              className="w-full rounded-lg border border-slate-300 bg-white px-2 py-1.5 text-sm text-slate-900 placeholder:text-slate-400"
            />
            <input
              aria-label="ZIP file"
              type="file"
              accept=".zip"
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              className="w-full text-sm"
            />
            <button
              type="submit"
              disabled={busy || !file || !zipName.trim()}
              className="rounded-lg bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-60"
            >
              Upload and import
            </button>
          </form>
        </Card>
      </div>
      <Card title={`Your projects (${total})`}>
        {loading ? (
          <p>Loading…</p>
        ) : projects.length === 0 ? (
          <p>No projects yet. Create a shell or upload a ZIP above.</p>
        ) : (
          <ul className="divide-y divide-slate-200">
            {projects.map((project) => (
              <li key={project.id} className="flex items-center gap-3 py-2">
                <div className="min-w-0 flex-1">
                  <Link
                    to={`/projects/${project.id}`}
                    className="truncate text-sm font-medium text-indigo-600 hover:underline"
                  >
                    {project.name}
                  </Link>
                  <p className="truncate text-xs">
                    {project.sourceType} · {project.fileCount} files
                  </p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
