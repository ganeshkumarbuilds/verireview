import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { FeaturePage } from '../../components/FeaturePage';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  SkeletonList,
  primaryButtonClass,
  selectClass,
} from '../../components/ui';
import { formatRelativeTime } from '../../components/workflow';
import { FindingsPanel } from './components/FindingsPanel';
import { ReviewRunPipeline } from './components/ReviewRunPipeline';
import { ReviewFixWorkflow } from './components/ReviewFixWorkflow';
import { SourceIntake } from './components/SourceIntake';
import { useReviewWorkspace } from './hooks/useReviewWorkspace';

const STEPS = ['Choose source', 'Run analysis', 'Findings'] as const;

/**
 * Review only: choose existing code (ZIP/GitHub/paste or an existing
 * project), run an analysis, and read its findings. Fix requests and
 * re-verification open from a finding, inside this page.
 */
export function ReviewPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialProjectId = searchParams.get('project');
  const workspace = useReviewWorkspace(initialProjectId);
  const {
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
    setFindingsNonce,
    visibleFindings,
    findingsLoading,
    starting,
    startAnalysis,
    aiModel,
    setAiModel,
    aiApiKey,
    setAiApiKey,
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
    retryStage,
  } = workspace;

  // Keep deep links shareable: /review?project=<id>.
  useEffect(() => {
    setSearchParams(selectedProjectId ? { project: selectedProjectId } : {}, { replace: true });
  }, [selectedProjectId, setSearchParams]);

  const step = selectedReview ? 2 : selectedProjectId ? 1 : 0;
  const combinedError = error ?? projectsError;

  return (
    <FeaturePage
      title="Review"
      description="Bring in existing code, run an analysis, and investigate findings. Open a finding to request a fix, apply the patch, and re-verify it inline."
      steps={STEPS}
      current={step}
      actions={
        selectedProjectId ? (
          <button
            type="button"
            onClick={() => void startAnalysis()}
            disabled={starting || activeReview !== null}
            className={primaryButtonClass}
          >
            {starting ? 'Starting…' : activeReview ? 'Analysis running…' : 'Start analysis'}
          </button>
        ) : undefined
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
        title="Step 1 — Choose a source"
        subtitle="Upload a ZIP, import a public GitHub repository, paste files, or continue with a project you already have."
      >
        <SourceIntake onImported={handleImported} />

        <div className="my-5 flex items-center gap-3" aria-hidden="true">
          <span className="h-px flex-1 bg-slate-200" />
          <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">or</span>
          <span className="h-px flex-1 bg-slate-200" />
        </div>

        {projectsLoading ? (
          <LoadingState label="Loading projects…" />
        ) : projectsError && projects.length === 0 ? (
          <p className="py-3 text-sm text-slate-600">
            Projects could not be loaded. Fix the error above and retry.
          </p>
        ) : projects.length === 0 ? (
          <EmptyState
            title="No projects to review yet."
            body="Import a codebase above to create your first project. It is selected automatically when the import finishes."
          />
        ) : (
          <label className="flex flex-wrap items-center gap-2 text-sm">
            <span className="shrink-0 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Existing project
            </span>
            <select
              aria-label="Select project"
              value={selectedProjectId ?? ''}
              onChange={(event) => selectProject(event.target.value)}
              className={`${selectClass} min-w-0 flex-1 sm:max-w-md`}
            >
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name} · {project.sourceType} · {project.fileCount} files
                </option>
              ))}
            </select>
          </label>
        )}
      </Card>

      {selectedProject && (
        <Card
          title="Step 2 — Run analysis"
          subtitle={`${selectedProject.name} is ready. Start an analysis or inspect an earlier run.`}
          actions={
            <button
              type="button"
              onClick={() => void startAnalysis()}
              disabled={starting || activeReview !== null}
              className={primaryButtonClass}
            >
              {starting ? 'Starting…' : activeReview ? 'Analysis running…' : 'Start analysis'}
            </button>
          }
        >
          <div className="mb-4 flex flex-wrap items-center gap-2 text-sm text-slate-600">
            <Badge tone="blue">{selectedProject.sourceType}</Badge>
            <span>{selectedProject.fileCount.toLocaleString()} files</span>
            {selectedProject.language && <span>· {selectedProject.language}</span>}
            <span className="text-slate-400">
              · updated {formatRelativeTime(selectedProject.updatedAt)}
            </span>
          </div>
          <div className="mb-4 grid gap-3 rounded-xl border border-slate-200 bg-slate-50 p-3 sm:grid-cols-2">
            <label className="text-sm">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wider text-slate-500">
                AI model (optional)
              </span>
              <input
                aria-label="AI review model"
                value={aiModel}
                onChange={(event) => setAiModel(event.target.value)}
                placeholder="openai/gpt-4o-mini"
                className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2"
              />
            </label>
            <label className="text-sm">
              <span className="mb-1 block text-xs font-semibold uppercase tracking-wider text-slate-500">
                AI provider API key (optional)
              </span>
              <input
                type="password"
                aria-label="AI review provider API key"
                value={aiApiKey}
                onChange={(event) => setAiApiKey(event.target.value)}
                placeholder="Used for this run only"
                className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2"
              />
              <span className="mt-1 block text-xs text-slate-500">
                Leave blank to run only in-process checks.
              </span>
            </label>
          </div>

          {reviewsLoading && reviews.length === 0 ? (
            <>
              <LoadingState label="Loading analyses…" />
              <SkeletonList rows={2} />
            </>
          ) : error && reviews.length === 0 ? (
            <p className="py-3 text-sm text-slate-600">
              Analyses could not be loaded. Fix the error above and retry.
            </p>
          ) : reviews.length === 0 ? (
            <EmptyState
              title="No analysis yet."
              body="Start your first analysis to collect deterministic findings for this project."
              action={
                <button
                  type="button"
                  onClick={() => void startAnalysis()}
                  disabled={starting}
                  className={primaryButtonClass}
                >
                  {starting ? 'Starting…' : 'Start analysis'}
                </button>
              }
            />
          ) : (
            <ul className="flex flex-wrap gap-2" aria-label="Analysis runs">
              {reviews.map((review) => {
                const isSelected = review.id === selectedReviewId;
                return (
                  <li key={review.id}>
                    <button
                      type="button"
                      onClick={() => setSelectedReviewId(review.id)}
                      aria-pressed={isSelected}
                      className={`flex items-center gap-2 rounded-full border px-3 py-1.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 ${
                        isSelected
                          ? 'border-indigo-600 bg-indigo-600 font-semibold text-white'
                          : 'border-indigo-200 bg-white text-slate-700 hover:border-indigo-300 hover:bg-indigo-50'
                      }`}
                    >
                      {review.status} · {review.findingCount}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
          {activeReview && (
            <p className="mt-3 text-sm text-slate-600">
              Analysis in progress… findings update when it completes.
            </p>
          )}
        </Card>
      )}

      {selectedReview && (
        <ReviewRunPipeline review={selectedReview} onRetry={(stage) => void retryStage(stage)} />
      )}

      {selectedReview && (
        <ReviewFixWorkflow
          projectId={selectedProjectId ?? selectedReview.projectId}
          review={selectedReview}
          findings={findings}
          onRunComplete={() => {
            void reloadReviews();
            setFindingsNonce((value) => value + 1);
            setSelectedReviewId(selectedReview.id);
          }}
        />
      )}

      {selectedReview && (
        <FindingsPanel
          projectId={selectedProjectId}
          review={selectedReview}
          findings={findings}
          visibleFindings={visibleFindings}
          analyzers={analyzers}
          loading={findingsLoading}
          stats={overviewStats}
          severityFilter={severityFilter}
          onSeverityFilter={setSeverityFilter}
          analyzerFilter={analyzerFilter}
          onAnalyzerFilter={setAnalyzerFilter}
          sourceFilter={sourceFilter}
          onSourceFilter={setSourceFilter}
          searchQuery={searchQuery}
          onSearchQuery={setSearchQuery}
          onClearFilters={clearFilters}
          error={error}
        />
      )}

      {selectedProjectId && selectedReview && (
        <p className="text-sm text-slate-500">
          Select a finding below to request a fix, apply the patch, and re-verify it
          inside this review. Verification evidence stays linked to the finding.
        </p>
      )}
    </FeaturePage>
  );
}
