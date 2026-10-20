import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../../api/client';
import { getExecution, listExecutions, startExecution } from '../../../api/executions';
import { getVerificationByExecution, runVerification } from '../../../api/verifications';
import type { ExecutionRunResponse, VerificationRunResponse } from '../../../api/types';
import { apiClient, useAuth } from '../../../auth/AuthContext';
import { useProjectList } from '../../../hooks/useProjectList';

const POLL_MS = 3000;

function isActive(execution: ExecutionRunResponse | null): boolean {
  return execution?.status === 'PENDING' || execution?.status === 'RUNNING';
}

/**
 * Verify only: pick a project, run its latest applied patch in the sandbox,
 * then ask the backend for the persisted verification verdict. No verdict is
 * ever derived in the browser.
 */
export function useVerification(initialProjectId: string | null) {
  const { token } = useAuth();
  const {
    projects,
    loading: projectsLoading,
    error: projectsError,
    reload: reloadProjects,
  } = useProjectList();

  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(initialProjectId);
  const [executions, setExecutions] = useState<ExecutionRunResponse[]>([]);
  const [executionsLoading, setExecutionsLoading] = useState(false);
  const [verification, setVerification] = useState<VerificationRunResponse | null>(null);
  const [verificationLoading, setVerificationLoading] = useState(false);
  const [busy, setBusy] = useState<'build' | 'verify' | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (projectsLoading) return;
    setSelectedProjectId((current) => {
      if (current && projects.some((project) => project.id === current)) return current;
      if (initialProjectId && projects.some((project) => project.id === initialProjectId)) {
        return initialProjectId;
      }
      return projects[0]?.id ?? null;
    });
  }, [initialProjectId, projects, projectsLoading]);

  const reloadExecutions = useCallback(async () => {
    if (!token || !selectedProjectId) {
      setExecutions([]);
      return;
    }
    setExecutionsLoading(true);
    setError(null);
    try {
      const runs = await listExecutions(apiClient(), token, selectedProjectId);
      setExecutions(
        [...runs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
      );
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load sandbox runs.');
    } finally {
      setExecutionsLoading(false);
    }
  }, [selectedProjectId, token]);

  useEffect(() => {
    setVerification(null);
    void reloadExecutions();
  }, [reloadExecutions]);

  const latestExecution = executions[0] ?? null;

  // Load the persisted verdict whenever the latest run is terminal.
  useEffect(() => {
    if (!token || !latestExecution || isActive(latestExecution)) {
      return;
    }
    let cancelled = false;
    setVerificationLoading(true);
    void (async () => {
      try {
        const found = await getVerificationByExecution(apiClient(), token, latestExecution.id);
        if (!cancelled) setVerification(found);
      } catch (err) {
        if (!cancelled) {
          if (err instanceof ApiError && err.status === 404) {
            setVerification(null);
          } else {
            setError(err instanceof ApiError ? err.message : 'Could not load verification.');
          }
        }
      } finally {
        if (!cancelled) setVerificationLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [latestExecution, token]);

  // Poll an active sandbox run; terminal state then triggers the verdict load.
  useEffect(() => {
    if (!token || !latestExecution || !isActive(latestExecution)) return undefined;
    const executionId = latestExecution.id;
    const timer = window.setInterval(() => {
      void (async () => {
        try {
          const updated = await getExecution(apiClient(), token, executionId);
          setExecutions((current) => {
            const rest = current.filter((run) => run.id !== updated.id);
            return [updated, ...rest];
          });
        } catch {
          // Keep the last known state; the next tick may succeed.
        }
      })();
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [latestExecution, token]);

  const selectProject = useCallback((projectId: string) => {
    setSelectedProjectId(projectId);
    setExecutions([]);
    setVerification(null);
    setError(null);
  }, []);

  const runBuildAndTest = async () => {
    if (!token || !selectedProjectId || !latestExecution?.patchId || busy) return;
    setBusy('build');
    setError(null);
    setVerification(null);
    try {
      const run = await startExecution(
        apiClient(),
        token,
        selectedProjectId,
        latestExecution.patchId,
      );
      setExecutions((current) => [run, ...current.filter((item) => item.id !== run.id)]);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start the sandbox run.');
    } finally {
      setBusy(null);
    }
  };

  const verify = async () => {
    if (!token || !latestExecution || busy) return;
    setBusy('verify');
    setError(null);
    try {
      const result = await runVerification(apiClient(), token, latestExecution.id);
      setVerification(result);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not run verification.');
    } finally {
      setBusy(null);
    }
  };

  return {
    projects,
    projectsLoading,
    projectsError,
    reloadProjects,
    selectedProjectId,
    selectedProject: projects.find((project) => project.id === selectedProjectId) ?? null,
    selectProject,
    executions,
    executionsLoading,
    latestExecution,
    active: isActive(latestExecution),
    reloadExecutions,
    verification,
    verificationLoading,
    busy,
    error,
    runBuildAndTest,
    verify,
  };
}
