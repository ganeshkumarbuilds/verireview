import { useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { FeaturePage } from '../../components/FeaturePage';
import { AgentPipelineView } from '../../components/AgentPipelineView';
import { ConfirmFixDialog } from '../../components/ConfirmFixDialog';
import { FixRunResult } from '../../components/FixRunResult';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  SkeletonList,
  primaryButtonClass,
  secondaryButtonClass,
  selectClass,
} from '../../components/ui';
import { severityTone, sourceTone } from '../../components/workflow';
import { useFixWorkspace } from './hooks/useFixWorkspace';

const STEPS = ['Pick project', 'Select findings', 'Confirm', 'Fix'] as const;

/**
 * Fix only: choose a project with open findings, select findings, confirm the
 * change, then review the applied patch and the automatic re-check. Nothing
 * is uploaded or generated here.
 */
export function FixPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialProjectId = searchParams.get('project');
  const workspace = useFixWorkspace(initialProjectId);
  const {
    projects,
    projectsLoading,
    projectsError,
    selectedProjectId,
    selectedProject,
    selectProject,
    reloadProjects,
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
  } = workspace;

  useEffect(() => {
    setSearchParams(selectedProjectId ? { project: selectedProjectId } : {}, { replace: true });
  }, [selectedProjectId, setSearchParams]);

  const step = fixResult
    ? 3
    : confirmOpen
      ? 2
      : selectedProjectId
        ? 1
        : 0;
  const combinedError = error ?? projectsError;

  return (
    <FeaturePage
      title="Fix"
      description="Pick a project with open findings, choose what to fix, and approve the change. The backend applies the patch and re-checks it automatically."
      steps={STEPS}
      current={step}
      actions={
        <button
          type="button"
          onClick={() => setConfirmOpen(true)}
          disabled={selectedFindingIds.length === 0 || fixBusy}
          className={primaryButtonClass}
        >
          Fix selected ({selectedFindingIds.length})
        </button>
      }
    >
      {combinedError && (
        <ErrorAlert
          message={combinedError}
          onRetry={() => {
            void reloadProjects();
            void reloadReviews();
          }}
        />
      )}

      <Card
        title="Step 1 — Pick a project"
        subtitle="Only findings that belong to your account are listed."
      >
        {projectsLoading ? (
          <LoadingState label="Loading projects…" />
        ) : projectsError && projects.length === 0 ? (
          <p className="text-sm text-slate-600">
            Projects could not be loaded. Fix the error above and retry.
          </p>
        ) : projects.length === 0 ? (
          <EmptyState
            title="No projects to fix yet."
            body="Bring code into Review first; findings become fixable after an analysis."
            action={
              <Link to="/review" className={primaryButtonClass}>
                Go to Review
              </Link>
            }
          />
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex min-w-0 flex-1 flex-wrap items-center gap-2 text-sm sm:max-w-xl">
              <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Project
              </span>
              <select
                aria-label="Select project"
                value={selectedProjectId ?? ''}
                onChange={(event) => selectProject(event.target.value)}
                className={`${selectClass} min-w-0 flex-1`}
              >
                {projects.map((project) => (
                  <option key={project.id} value={project.id}>
                    {project.name} · {project.fileCount} files
                  </option>
                ))}
              </select>
            </label>
            {selectedProject && (
              <Link
                to={`/review?project=${selectedProject.id}`}
                className="text-sm font-semibold text-indigo-700 hover:underline"
              >
                Review this project
              </Link>
            )}
          </div>
        )}
      </Card>

      {selectedProject && (
        <Card
          title="Step 2 — Select findings"
          subtitle={
            selectedReview
              ? `Latest analysis: ${selectedReview.status} · ${selectedReview.findingCount} findings.`
              : 'Select findings to fix.'
          }
        >
          {reviewsLoading && reviews.length === 0 ? (
            <>
              <LoadingState label="Loading analysis…" />
              <SkeletonList rows={2} />
            </>
          ) : error && reviews.length === 0 ? (
            <p className="py-3 text-sm text-slate-600">
              Analysis could not be loaded. Fix the error above and retry.
            </p>
          ) : !selectedReview ? (
            <EmptyState
              title="No analysis yet."
              body="Run an analysis in Review first, then come back to fix its findings."
              action={
                <Link to={`/review?project=${selectedProject.id}`} className={primaryButtonClass}>
                  Run analysis in Review
                </Link>
              }
            />
          ) : findingsLoading && findings.length === 0 ? (
            <LoadingState label="Loading findings…" />
          ) : openFindings.length === 0 ? (
            <EmptyState
              title="No open findings."
              body="Everything reported by this analysis is already resolved. Nothing to fix."
              action={
                <Link to={`/review?project=${selectedProject.id}`} className={primaryButtonClass}>
                  Open Review
                </Link>
              }
            />
          ) : (
            <>
              <div className="mb-3 flex flex-wrap items-center gap-3 rounded-xl border border-indigo-100 bg-white px-3 py-2">
                <label className="flex items-center gap-2 text-sm font-medium text-slate-700">
                  <input
                    type="checkbox"
                    aria-label="Select all findings"
                    checked={allSelected}
                    onChange={toggleAll}
                    className="h-4 w-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-600"
                  />
                  Select all ({openFindings.length})
                </label>
                <span className="text-xs text-slate-500">
                  {selectedFindingIds.length} selected
                </span>
              </div>
              <ul className="space-y-2">
                {openFindings.map((finding) => (
                  <li key={finding.id} className="flex items-start gap-3">
                    <input
                      type="checkbox"
                      aria-label={`Select finding ${finding.title}`}
                      checked={selectedFindingIds.includes(finding.id)}
                      onChange={() => toggleFinding(finding.id)}
                      className="mt-4 h-4 w-4 shrink-0 rounded border-slate-300 text-indigo-600 focus:ring-indigo-600"
                    />
                    <Link
                      to={`/projects/${selectedProject.id}/reviews/${selectedReview.id}/findings/${finding.id}`}
                      aria-label={`Investigate finding ${finding.rule ?? finding.title}`}
                      className="block flex-1 space-y-1.5 rounded-xl border border-indigo-100 bg-white px-4 py-3 shadow-sm shadow-indigo-100/50 transition-all hover:border-indigo-200 hover:shadow focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={severityTone(finding.severity)}>{finding.severity}</Badge>
                        <Badge tone={sourceTone(finding.source)}>{finding.source}</Badge>
                        <Badge tone="violet">{finding.analyzer ?? 'unknown'}</Badge>
                        <span className="break-words font-mono text-sm font-semibold text-indigo-950">
                          {finding.rule ?? finding.title}
                        </span>
                      </div>
                      {finding.description && (
                        <p className="line-clamp-2 text-sm leading-relaxed text-slate-600">
                          {finding.description}
                        </p>
                      )}
                      <p className="break-all font-mono text-xs text-slate-500">
                        {finding.filePath ?? '—'}
                        {finding.lineStart != null ? `:${finding.lineStart}` : ''}
                      </p>
                    </Link>
                  </li>
                ))}
              </ul>
            </>
          )}
        </Card>
      )}

      {selectedProject && (
        <Card
          title="Fix pipeline"
          subtitle="Import → Analyze → Review → Fix → Verify, driven by real runs."
        >
          {pipelineLoading && !pipeline ? (
            <LoadingState label="Loading pipeline…" />
          ) : pipelineError && !pipeline ? (
            <ErrorAlert message={pipelineError} onRetry={() => void refreshPipeline()} />
          ) : pipeline ? (
            <AgentPipelineView stages={pipeline.stages} onRetry={retryStage} />
          ) : (
            <p className="text-sm text-slate-500">Pipeline is not available for this project yet.</p>
          )}
        </Card>
      )}

      {fixResult && (
        <Card
          title={`Step 4 — Fix result${selectedProject ? ` — ${selectedProject.name}` : ''}`}
          subtitle="What changed, and what the automatic re-check found."
        >
          <FixRunResult
            detail={fixResult}
            revertingPatchId={revertingPatchId}
            revertedPatchIds={revertedPatchIds}
            onRevert={(file) => void handleRevert(file)}
            onAccept={() => setAccepted(true)}
            accepted={accepted}
          />
          {fixError && (
            <p role="alert" className="mt-3 text-sm text-red-700">
              {fixError}
            </p>
          )}
          <div className="mt-4 flex flex-wrap gap-2">
            <Link to={`/review?project=${selectedProjectId}`} className={secondaryButtonClass}>
              Inspect findings
            </Link>
            <Link
              to={`/verify?project=${selectedProjectId}`}
              className={primaryButtonClass}
            >
              Verify in sandbox
            </Link>
          </div>
        </Card>
      )}

      <ConfirmFixDialog
        open={confirmOpen}
        findings={selectedFindings.map((finding) => ({
          id: finding.id,
          title: finding.title,
          severity: finding.severity,
          filePath: finding.filePath,
          lineStart: finding.lineStart,
        }))}
        files={selectedFiles}
        busy={fixBusy}
        error={fixError}
        onCancel={() => {
          if (!fixBusy) {
            setConfirmOpen(false);
          }
        }}
        onConfirm={() => void runFix(selectedFindingIds)}
      />
    </FeaturePage>
  );
}
