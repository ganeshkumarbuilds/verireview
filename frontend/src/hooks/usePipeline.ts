import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { getPipeline } from '../api/pipeline';
import type { PipelineResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';

/** Refresh cadence while a stage is running (spec: 3–5s). */
export const PIPELINE_POLL_MS = 4000;

export interface UsePipelineResult {
  pipeline: PipelineResponse | null;
  loading: boolean;
  error: string | null;
  /** True while any stage is RUNNING (drives the fallback poller). */
  active: boolean;
  refresh: () => Promise<void>;
}

/**
 * Loads the real agent pipeline for a project.
 *
 * Live updates use a fallback poller: the backend's SSE stream requires a
 * Bearer header that the browser `EventSource` API cannot send, so we poll
 * every {@link PIPELINE_POLL_MS} *only while a stage is running*, pausing while
 * the tab is hidden. Previous values are kept on refresh so the view never
 * flickers to an empty state.
 */
export function usePipeline(projectId: string | null | undefined): UsePipelineResult {
  const { token } = useAuth();
  const [pipeline, setPipeline] = useState<PipelineResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cancelledRef = useRef(false);

  const load = useCallback(async () => {
    if (!token || !projectId) {
      setPipeline(null);
      return;
    }
    try {
      const data = await getPipeline(apiClient(), token, projectId);
      if (!data || !Array.isArray(data.stages)) {
        throw new ApiError(500, 'PIPELINE_MALFORMED', 'The pipeline response was not understood.');
      }
      if (!cancelledRef.current) {
        // Replace wholesale only on success; a failed refresh keeps the last
        // known values so the pipeline does not flicker.
        setPipeline(data);
        setError(null);
      }
    } catch (err) {
      if (!cancelledRef.current) {
        setError(err instanceof ApiError ? err.message : 'Could not load the pipeline.');
      }
    }
  }, [token, projectId]);

  useEffect(() => {
    cancelledRef.current = false;
    if (!projectId || !token) {
      setPipeline(null);
      setLoading(false);
      return () => {
        cancelledRef.current = true;
      };
    }
    setLoading(true);
    void load().finally(() => {
      if (!cancelledRef.current) {
        setLoading(false);
      }
    });
    return () => {
      cancelledRef.current = true;
    };
  }, [load, projectId, token]);

  const active = pipeline?.stages?.some((stage) => stage.state === 'RUNNING') ?? false;

  useEffect(() => {
    if (!active || !projectId || !token) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      if (!document.hidden) {
        void load();
      }
    }, PIPELINE_POLL_MS);
    const onVisibility = () => {
      if (!document.hidden) {
        void load();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [active, load, projectId, token]);

  return { pipeline, loading, error, active, refresh: load };
}
