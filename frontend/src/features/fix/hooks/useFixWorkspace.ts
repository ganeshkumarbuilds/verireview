import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError } from '../../../api/client';
import { listFindings, listReviews } from '../../../api/analysis';
import { createProjectFixRequests, getFixRun, revertPatch, startFixRun } from '../../../api/fixRuns';
import type {
  FindingResponse,
  FixRunChangedFileResponse,
  FixRunDetailResponse,
  PipelineStageResponse,
  ReviewResponse,
} from '../../../api/types';
import { apiClient, useAuth } from '../../../auth/AuthContext';
import { useProjectList } from '../../../hooks/useProjectList';
import { usePipeline } from '../../../hooks/usePipeline';

/**
 * Fix only: pick a project, select open findings, confirm, then show what the
 * backend changed and what its automatic re-check found.
 */
export function useFixWorkspace(initialProjectId: string | null) {
  const { token } = useAuth();
  const {
    projects,
    loading: projectsLoading,
    error: projectsError,
    reload: reloadProjects,
  } = useProjectList();

  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(initialProjectId);
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [reviewsLoading, setReviewsLoading] = useState(false);
  const [selectedReviewId, setSelectedReviewId] = useState<string | null>(null);
  const [findings, setFindings] = useState<FindingResponse[]>([]);
  const [findingsLoading, setFindingsLoading] = useState(false);
  const [findingsNonce, setFindingsNonce] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const [selectedFindingIds, setSelectedFindingIds] = useState<string[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [fixBusy, setFixBusy] = useState(false);
  const [fixError, setFixError] = useState<string | null>(null);
  const [fixResult, setFixResult] = useState<FixRunDetailResponse | null>(null);
  const [lastFixFindingIds, setLastFixFindingIds] = useState<string[]>([]);
  const [revertingPatchId, setRevertingPatchId] = useState<string | null>(null);
  const [revertedPatchIds, setRevertedPatchIds] = useState<string[]>([]);
  const [accepted, setAccepted] = useState(false);

  const {
    pipeline,
    loading: pipelineLoading,
    error: pipelineError,
    refresh: refreshPipeline,
  } = usePipeline(selectedProjectId);

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

  const reloadReviews = useCallback(async () => {
    if (!token || !selectedProjectId) {
      setReviews([]);
      setSelectedReviewId(null);
      return;
    }
    setReviewsLoading(true);
    try {
      const history = await listReviews(apiClient(), token, selectedProjectId);
      setError(null);
      setReviews(history.content);
      setSelectedReviewId((current) => {
        if (current && history.content.some((review) => review.id === current)) return current;
        const completed = history.content.find((review) => review.status === 'COMPLETED');
        return completed?.id ?? history.content[0]?.id ?? null;
      });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load analyses.');
    } finally {
      setReviewsLoading(false);
    }
  }, [selectedProjectId, token]);

  useEffect(() => {
    void reloadReviews();
  }, [reloadReviews]);

  useEffect(() => {
    if (!token || !selectedReviewId) {
      setFindings([]);
      return;
    }
    let cancelled = false;
    setFindingsLoading(true);
    void (async () => {
      try {
        const page = await listFindings(apiClient(), token, selectedReviewId);
        if (!cancelled) {
          setFindings(page.content);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.message : 'Could not load findings.');
        }
      } finally {
        if (!cancelled) setFindingsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [findingsNonce, selectedReviewId, token]);

  const openFindings = useMemo(
    () => findings.filter((finding) => finding.status === 'OPEN'),
    [findings],
  );

  const selectProject = useCallback((projectId: string) => {
    setSelectedProjectId(projectId);
    setSelectedReviewId(null);
    setFindings([]);
    setSelectedFindingIds([]);
    setFixResult(null);
    setFixError(null);
    setError(null);
    setAccepted(false);
    setRevertedPatchIds([]);
  }, []);

  const clearSelection = useCallback(() => {
    setSelectedFindingIds([]);
    setFixError(null);
  }, []);

  const toggleFinding = useCallback((findingId: string) => {
    setSelectedFindingIds((current) =>
      current.includes(findingId)
        ? current.filter((id) => id !== findingId)
        : [...current, findingId],
    );
  }, []);

  const toggleAll = useCallback(() => {
    setSelectedFindingIds((current) => {
      const allSelected =
        openFindings.length > 0 && openFindings.every((finding) => current.includes(finding.id));
      return allSelected ? [] : openFindings.map((finding) => finding.id);
    });
  }, [openFindings]);

  const selectedFindings = findings.filter((finding) => selectedFindingIds.includes(finding.id));
  const selectedFiles = Array.from(
    new Set(
      selectedFindings
        .map((finding) => finding.filePath)
        .filter((path): path is string => Boolean(path)),
    ),
  );

  const runFix = async (findingIds: string[]) => {
    if (!token || !selectedProjectId || findingIds.length === 0) return;
    setFixBusy(true);
    setFixError(null);
    try {
      // Consent first: approved fix requests are recorded before anything changes.
      await createProjectFixRequests(apiClient(), token, selectedProjectId, findingIds);
      const detail = await startFixRun(apiClient(), token, selectedProjectId, findingIds);
      setLastFixFindingIds(findingIds);
      setFixResult(detail);
      setRevertedPatchIds([]);
      setAccepted(false);
      setConfirmOpen(false);
      setSelectedFindingIds([]);
      setFindingsNonce((nonce) => nonce + 1);
      await refreshPipeline();
    } catch (err) {
      setFixError(err instanceof ApiError ? err.message : 'Could not apply the fix.');
    } finally {
      setFixBusy(false);
    }
  };

  const retryStage = (_stage: PipelineStageResponse) => {
    if (lastFixFindingIds.length === 0) {
      setFixError('Select findings and confirm a fix to retry this stage.');
      return;
    }
    void runFix(lastFixFindingIds);
  };

  const handleRevert = async (file: FixRunChangedFileResponse) => {
    if (!token) return;
    setRevertingPatchId(file.patchId);
    setFixError(null);
    try {
      await revertPatch(apiClient(), token, file.patchId);
      setRevertedPatchIds((current) => Array.from(new Set([...current, file.patchId])));
      setFindingsNonce((nonce) => nonce + 1);
      await refreshPipeline();
      if (fixResult && selectedProjectId) {
        const refreshed = await getFixRun(
          apiClient(),
          token,
          selectedProjectId,
          fixResult.checkRunId,
        );
        setFixResult(refreshed);
      }
    } catch (err) {
      setFixError(err instanceof ApiError ? err.message : 'Could not revert the change.');
    } finally {
      setRevertingPatchId(null);
    }
  };

  const selectedProject = projects.find((project) => project.id === selectedProjectId) ?? null;
  const selectedReview = reviews.find((review) => review.id === selectedReviewId) ?? null;
  const allSelected =
    openFindings.length > 0 && openFindings.every((finding) => selectedFindingIds.includes(finding.id));

  return {
    projects,
    projectsLoading,
    projectsError,
    reloadProjects,
    selectedProjectId,
    selectedProject,
    selectProject,
    reviews,
    reviewsLoading,
    selectedReview,
    findings,
    openFindings,
    findingsLoading,
    error,
    reloadReviews,
    selectedFindingIds,
    selectedFindings,
    selectedFiles,
    toggleFinding,
    toggleAll,
    allSelected,
    clearSelection,
    confirmOpen,
    setConfirmOpen,
    fixBusy,
    fixError,
    fixResult,
    revertingPatchId,
    revertedPatchIds,
    accepted,
    setAccepted,
    runFix,
    handleRevert,
    pipeline,
    pipelineLoading,
    pipelineError,
    refreshPipeline,
    retryStage,
  };
}
