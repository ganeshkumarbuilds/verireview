import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { listProjects } from '../api/projects';
import type { ProjectResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';

export interface UseProjectListResult {
  projects: ProjectResponse[];
  loading: boolean;
  error: string | null;
  reload: () => Promise<void>;
  /** Merge a just-imported project without waiting for a full reload. */
  addProject: (project: ProjectResponse) => void;
  setError: (message: string | null) => void;
}

/**
 * Owner-scoped project list shared by the feature pages that start from an
 * existing project (Review, Fix, Verify, Projects). The backend remains the
 * source of truth: this hook only caches the latest successful response.
 */
export function useProjectList(size = 50): UseProjectListResult {
  const { token } = useAuth();
  const [projects, setProjects] = useState<ProjectResponse[]>([]);
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
      const page = await listProjects(apiClient(), token, { size });
      setProjects(page.content);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load projects.');
    } finally {
      setLoading(false);
    }
  }, [size, token]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const addProject = useCallback((project: ProjectResponse) => {
    setProjects((current) => [project, ...current.filter((item) => item.id !== project.id)]);
  }, []);

  return { projects, loading, error, reload, addProject, setError };
}
