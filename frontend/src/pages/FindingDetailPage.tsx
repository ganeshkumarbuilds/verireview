import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { getReview, listFindings } from '../api/analysis';
import { getProject } from '../api/projects';
import type { FindingResponse, ProjectResponse, ReviewResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import { FindingDetail } from '../components/FindingDetail';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  PageHeader,
  SkeletonList,
} from '../components/ui';
import {
  WorkspaceCrumb,
  severityTone,
  sourceTone,
} from '../components/workflow';

/**
 * Focused investigation page for one finding. The fix pipeline
 * (Finding → Fix Request → Proposed Patch → Apply → Execute → Verify)
 * is the existing FindingDetail + FixWorkflow pair, rendered as the
 * route body so the user always knows what is wrong, what the fix
 * changes, and whether execution/verification passed. Verdicts are
 * displayed exactly as the backend persisted them — never derived here.
 */
export function FindingDetailPage() {
  const { projectId, reviewId, findingId } = useParams<{
    projectId: string;
    reviewId: string;
    findingId: string;
  }>();
  const { token } = useAuth();
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectResponse | null>(null);
  const [review, setReview] = useState<ReviewResponse | null>(null);
  const [finding, setFinding] = useState<FindingResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token || !projectId || !reviewId || !findingId) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    void (async () => {
      try {
        const [detail, reviewDetail, findingsPage] = await Promise.all([
          getProject(apiClient(), token, projectId),
          getReview(apiClient(), token, reviewId),
          listFindings(apiClient(), token, reviewId),
        ]);
        if (cancelled) return;
        setProject(detail);
        setReview(reviewDetail);
        const target = findingsPage.content.find((item) => item.id === findingId) ?? null;
        setFinding(target);
        if (!target) {
          setError('This finding is not part of the selected review.');
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.message : 'Could not load the finding.');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, projectId, reviewId, findingId]);

  const backToReview = () => {
    if (projectId) {
      navigate(`/review?project=${projectId}`, { replace: true });
    } else {
      navigate('/review', { replace: true });
    }
  };

  if (loading) {
    return (
      <div className="space-y-4">
        <LoadingState label="Loading finding…" />
        <SkeletonList rows={4} />
      </div>
    );
  }

  if ((error && !finding) || (!loading && !finding)) {
    return (
      <div className="space-y-6">
        <WorkspaceCrumb
          items={[
            { label: 'Review', to: projectId ? `/review?project=${projectId}` : '/review' },
            { label: 'Finding' },
          ]}
        />
        <PageHeader title="Finding" description="The requested finding could not be opened." />
        <ErrorAlert message={error ?? 'Finding not found.'} onRetry={backToReview} retryLabel="Back to Review" />
        <Card title="What you can do next" subtitle="No data was changed.">
          <EmptyState
            title="Finding unavailable"
            body="It may belong to a different review, or you may lack access to this project."
            action={
              <Link
                to={projectId ? `/review?project=${projectId}` : '/review'}
                className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-700"
              >
                Back to Review
              </Link>
            }
          />
        </Card>
      </div>
    );
  }

  if (!finding) {
    return null;
  }

  return (
    <div className="space-y-6">
      <WorkspaceCrumb
        items={[
          { label: 'Review', to: projectId ? `/review?project=${projectId}` : '/review' },
          ...(project ? [{ label: project.name, to: `/projects/${project.id}` }] : []),
          { label: finding.rule ?? finding.title },
        ]}
      />
      <PageHeader
        title={finding.rule ?? finding.title}
        description={
          finding.description ??
          `Finding in ${finding.filePath ?? 'an unknown file'} reported by ${finding.analyzer ?? 'an analyzer'}.`
        }
      />

      <Card
        title="Investigation context"
        subtitle="What is wrong, where it is, and which review reported it."
        actions={
          <>
            <Badge tone={severityTone(finding.severity)}>{finding.severity}</Badge>
            <Badge tone={sourceTone(finding.source)}>{finding.source}</Badge>
          </>
        }
      >
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Analyzer / rule</dt>
            <dd className="mt-1 font-mono font-medium text-slate-800">
              {finding.analyzer ?? 'unknown'} · {finding.rule ?? '—'}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Location</dt>
            <dd className="mt-1 font-mono font-medium text-slate-800">
              {finding.filePath ?? '—'}
              {finding.lineStart != null ? `:${finding.lineStart}` : ''}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Category / status</dt>
            <dd className="mt-1 font-medium text-slate-800">
              {finding.category} · {finding.status}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">Review</dt>
            <dd className="mt-1 font-medium text-slate-800">
              {review ? `${review.status} · ${review.findingCount} findings` : '—'}
            </dd>
          </div>
        </dl>
      </Card>

      <FindingDetail
        finding={finding}
        onClose={backToReview}
        onViewFile={() => {
          if (projectId) {
            navigate(`/projects/${projectId}`);
          }
        }}
      />
    </div>
  );
}
