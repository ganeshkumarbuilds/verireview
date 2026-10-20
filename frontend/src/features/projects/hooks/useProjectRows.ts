import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../../api/client';
import { listReviews } from '../../../api/analysis';
import type { ProjectResponse, ReviewResponse } from '../../../api/types';
import { apiClient, useAuth } from '../../../auth/AuthContext';
import { useProjectList } from '../../../hooks/useProjectList';

export interface ProjectRow {
  project: ProjectResponse;
  latestReview: ReviewResponse | null;
  status: string;
  lastActivityAt: string;
}

/**
 * Projects list only: one row per owner project plus its latest review state.
 * Intake, generation, fixing and verification stay on their feature pages.
 */
export function useProjectRows() {
  const { token } = useAuth();
  const {
    projects,
    loading: projectsLoading,
    error: projectsError,
    reload: reloadProjects,
  } = useProjectList();
  const [rows, setRows] = useState<ProjectRow[]>([]);
  const [enriching, setEnriching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const enrich = useCallback(async () => {
    if (!token || projects.length === 0) {
      setRows(
        projects.map((project) => ({
          project,
          latestReview: null,
          status: project.status,
          lastActivityAt: project.updatedAt,
        })),
      );
      return;
    }
    setEnriching(true);
    setError(null);
    try {
      const enriched = await Promise.all(
        projects.map(async (project) => {
          try {
            const history = await listReviews(apiClient(), token, project.id);
            const latestReview = history.content[0] ?? null;
            return {
              project,
              latestReview,
              status: latestReview?.status ?? project.status,
              lastActivityAt: latestReview?.createdAt ?? project.updatedAt,
            } satisfies ProjectRow;
          } catch {
            // A single project's history must not hide the list.
            return {
              project,
              latestReview: null,
              status: project.status,
              lastActivityAt: project.updatedAt,
            } satisfies ProjectRow;
          }
        }),
      );
      setRows(enriched);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load project status.');
    } finally {
      setEnriching(false);
    }
  }, [projects, token]);

  useEffect(() => {
    void enrich();
  }, [enrich]);

  const reload = useCallback(async () => {
    await reloadProjects();
  }, [reloadProjects]);

  return {
    rows,
    loading: projectsLoading || enriching,
    error: error ?? projectsError,
    reload,
  };
}
