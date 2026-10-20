import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError } from '../../../api/client';
import { isTerminal, listFindings, listReviews, triggerAnalysis } from '../../../api/analysis';
import type { FindingResponse, ProjectResponse, ReviewResponse } from '../../../api/types';
import { apiClient, useAuth } from '../../../auth/AuthContext';
import { useProjectList } from '../../../hooks/useProjectList';

const POLL_MS = 3000;

/**
 * State for the Review page: source intake result → analysis runs → findings.
 * Only review endpoints are called here; fixing and verification have their
 * own feature pages.
 */
export function useReviewWorkspace(initialProjectId: string | null) {
  const { token } = useAuth();
  const {
    projects,
    loading: projectsLoading,
    error: projectsError,
    addProject,
    reload: reloadProjects,
  } = useProjectList();

  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(initialProjectId);
  const [reviews, setReviews] = useState<ReviewResponse[]>([]);
  const [reviewsLoading, setReviewsLoading] = useState(false);
  const [selectedReviewId, setSelectedReviewId] = useState<string | null>(null);
  const [findings, setFindings] = useState<FindingResponse[]>([]);
  const [findingsLoading, setFindingsLoading] = useState(false);
  const [findingsNonce, setFindingsNonce] = useState(0);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [severityFilter, setSeverityFilter] = useState('ALL');
  const [analyzerFilter, setAnalyzerFilter] = useState('ALL');
  const [sourceFilter, setSourceFilter] = useState('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  // Keep the selection valid once the owner-scoped project list arrives.
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
        return history.content[0]?.id ?? null;
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

  const activeReview = reviews.find((review) => !isTerminal(review.status)) ?? null;

  useEffect(() => {
    if (!activeReview || !token) return undefined;
    const timer = window.setInterval(() => {
      void reloadReviews();
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [activeReview, reloadReviews, token]);

  useEffect(() => {
    if (activeReview) setSelectedReviewId(activeReview.id);
  }, [activeReview]);

  const selectProject = useCallback((projectId: string) => {
    setSelectedProjectId(projectId);
    setSelectedReviewId(null);
    setFindings([]);
    setSeverityFilter('ALL');
    setAnalyzerFilter('ALL');
    setSourceFilter('ALL');
    setSearchQuery('');
    setError(null);
  }, []);

  const startAnalysis = async () => {
    if (!token || !selectedProjectId || starting) return;
    setStarting(true);
    setError(null);
    try {
      const review = await triggerAnalysis(apiClient(), token, selectedProjectId);
      setSelectedReviewId(review.id);
      await reloadReviews();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start the analysis.');
    } finally {
      setStarting(false);
    }
  };

  /** A successful import selects the new project and moves to the analysis step. */
  const handleImported = useCallback(
    (project: ProjectResponse) => {
      addProject(project);
      setSelectedProjectId(project.id);
      setSelectedReviewId(null);
      setFindings([]);
      setError(null);
    },
    [addProject],
  );

  const selectedProject = projects.find((project) => project.id === selectedProjectId) ?? null;
  const selectedReview = reviews.find((review) => review.id === selectedReviewId) ?? null;

  const analyzers = useMemo(
    () => Array.from(new Set(findings.map((finding) => finding.analyzer ?? 'unknown'))).sort(),
    [findings],
  );

  const visibleFindings = useMemo(() => {
    return findings.filter((finding) => {
      if (severityFilter !== 'ALL' && finding.severity !== severityFilter) return false;
      if (analyzerFilter !== 'ALL' && (finding.analyzer ?? 'unknown') !== analyzerFilter) return false;
      if (sourceFilter !== 'ALL' && finding.source !== sourceFilter) return false;
      const query = searchQuery.trim().toLowerCase();
      if (query) {
        const haystack = [finding.title, finding.description, finding.filePath, finding.rule]
          .filter((part): part is string => part != null)
          .join('\n')
          .toLowerCase();
        if (!haystack.includes(query)) return false;
      }
      return true;
    });
  }, [analyzerFilter, findings, searchQuery, severityFilter, sourceFilter]);

  const clearFilters = useCallback(() => {
    setSeverityFilter('ALL');
    setAnalyzerFilter('ALL');
    setSourceFilter('ALL');
    setSearchQuery('');
  }, []);

  const overviewStats = useMemo(() => {
    const countBy = (predicate: (finding: FindingResponse) => boolean) =>
      findings.filter(predicate).length;
    return [
      ['total', findings.length] as const,
      ['critical', countBy((finding) => finding.severity === 'CRITICAL')] as const,
      ['high', countBy((finding) => finding.severity === 'HIGH')] as const,
      ['medium', countBy((finding) => finding.severity === 'MEDIUM')] as const,
      ['low', countBy((finding) => finding.severity === 'LOW')] as const,
      ['deterministic', countBy((finding) => finding.source === 'DETERMINISTIC')] as const,
      ['ai', countBy((finding) => finding.source === 'AI')] as const,
      ['verified', countBy((finding) => finding.source === 'VERIFIED')] as const,
    ];
  }, [findings]);

  return {
    projects,
    projectsLoading,
    projectsError,
    selectedProjectId,
    selectedProject,
    selectProject,
    handleImported,
    reloadProjects,
    reviews,
    reviewsLoading,
    selectedReviewId,
    setSelectedReviewId,
    selectedReview,
    activeReview,
    findings,
    visibleFindings,
    findingsLoading,
    findingsNonce,
    setFindingsNonce,
    starting,
    startAnalysis,
    error,
    reloadReviews,
    severityFilter,
    setSeverityFilter,
    analyzerFilter,
    setAnalyzerFilter,
    sourceFilter,
    setSourceFilter,
    searchQuery,
    setSearchQuery,
    clearFilters,
    analyzers,
    overviewStats,
  };
}
