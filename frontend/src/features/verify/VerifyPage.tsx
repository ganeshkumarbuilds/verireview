import { useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { FeaturePage } from '../../components/FeaturePage';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  secondaryButtonClass,
  primaryButtonClass,
  selectClass,
} from '../../components/ui';
import { formatRelativeTime } from '../../components/workflow';
import { useVerification } from './hooks/useVerification';

const STEPS = ['Pick project', 'Build & test', 'Results'] as const;

function executionTone(status: string): 'blue' | 'green' | 'red' | 'gray' {
  switch (status) {
    case 'PENDING':
    case 'RUNNING':
      return 'blue';
    case 'SUCCESS':
      return 'green';
    case 'FAILED':
    case 'FAILURE':
    case 'TIMEOUT':
      return 'red';
    default:
      return 'gray';
  }
}

function buildTone(status: string): 'blue' | 'green' | 'red' | 'gray' {
  switch (status) {
    case 'PENDING':
    case 'RUNNING':
      return 'blue';
    case 'SUCCESS':
      return 'green';
    case 'FAILURE':
    case 'TIMEOUT':
      return 'red';
    default:
      return 'gray';
  }
}

function verdictTone(verdict: string): 'amber' | 'green' | 'red' | 'gray' {
  switch (verdict) {
    case 'VERIFIED':
      return 'green';
    case 'REJECTED':
      return 'red';
    case 'PENDING':
      return 'amber';
    default:
      return 'gray';
  }
}

/**
 * Verify only: pick a project, rebuild its latest applied fix in the sandbox,
 * and read the backend's verification verdict and evidence.
 */
export function VerifyPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialProjectId = searchParams.get('project');
  const {
    projects,
    projectsLoading,
    projectsError,
    reloadProjects,
    selectedProjectId,
    selectedProject,
    selectProject,
    executions,
    executionsLoading,
    latestExecution,
    active,
    verification,
    verificationLoading,
    busy,
    error,
    reloadExecutions,
    runBuildAndTest,
    verify,
  } = useVerification(initialProjectId);

  useEffect(() => {
    setSearchParams(selectedProjectId ? { project: selectedProjectId } : {}, { replace: true });
  }, [selectedProjectId, setSearchParams]);

  const step = verification ? 2 : latestExecution ? 1 : 0;
  const canVerify = latestExecution !== null && !active && busy === null;
  const combinedError = error ?? projectsError;

  return (
    <FeaturePage
      title="Verify"
      description="Run build and tests for a fixed project in the isolated sandbox, then read the persisted verdict. Verification is never inferred in the browser."
      steps={STEPS}
      current={step}
      actions={
        <button
          type="button"
          onClick={() => void verify()}
          disabled={!canVerify}
          className={primaryButtonClass}
        >
          {busy === 'verify' ? 'Verifying…' : 'Run verification'}
        </button>
      }
    >
      {combinedError && (
        <ErrorAlert
          message={combinedError}
          onRetry={() => {
            void reloadProjects();
            void reloadExecutions();
          }}
        />
      )}

      <Card
        title="Step 1 — Pick a project"
        subtitle="Only projects owned by your account are listed."
      >
        {projectsLoading ? (
          <LoadingState label="Loading projects…" />
        ) : projectsError && projects.length === 0 ? (
          <p className="text-sm text-slate-600">
            Projects could not be loaded. Fix the error above and retry.
          </p>
        ) : projects.length === 0 ? (
          <EmptyState
            title="No projects to verify yet."
            body="Bring code into Review first. Verification runs on a fix that was applied in the sandbox."
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
              <span className="text-sm text-slate-500">
                Updated {formatRelativeTime(selectedProject.updatedAt)}
              </span>
            )}
          </div>
        )}
      </Card>

      {selectedProjectId && (
        <Card
          title="Step 2 — Build & test"
          subtitle="The newest sandbox run for this project. Re-running uses its applied patch."
        >
          {executionsLoading && executions.length === 0 ? (
            <LoadingState label="Loading sandbox runs…" />
          ) : error && executions.length === 0 ? (
            <p className="py-3 text-sm text-slate-600">
              Sandbox runs could not be loaded. Fix the error above and retry.
            </p>
          ) : !latestExecution ? (
            <EmptyState
              title="No sandbox runs yet."
              body="Verification runs on an applied fix. Open Fix, apply a patch, then come back to verify it."
              action={
                <Link to={`/fix?project=${selectedProjectId}`} className={primaryButtonClass}>
                  Go to Fix
                </Link>
              }
            />
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={executionTone(latestExecution.status)}>
                  {latestExecution.status}
                </Badge>
                <Badge tone={buildTone(latestExecution.buildStatus)}>
                  build: {latestExecution.buildStatus}
                </Badge>
                {latestExecution.exitCode != null && (
                  <span className="text-xs tabular-nums text-slate-500">
                    exit {latestExecution.exitCode}
                  </span>
                )}
                {latestExecution.durationMs != null && (
                  <span className="text-xs tabular-nums text-slate-500">
                    {latestExecution.durationMs} ms
                  </span>
                )}
                <span className="text-xs text-slate-400">
                  {formatRelativeTime(latestExecution.createdAt)}
                </span>
              </div>

              {active && (
                <p className="flex items-center gap-2 text-sm text-slate-500">
                  <span
                    aria-hidden="true"
                    className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-indigo-200 border-t-indigo-600"
                  />
                  Sandbox run in progress…
                </p>
              )}

              {latestExecution.stdout && (
                <details>
                  <summary className="cursor-pointer text-sm font-medium text-indigo-700 hover:underline">
                    View build output
                  </summary>
                  <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-xl border border-indigo-100 bg-white p-3 font-mono text-xs leading-relaxed text-slate-800">
                    {latestExecution.stdout}
                  </pre>
                </details>
              )}
              {latestExecution.stderr && (
                <details>
                  <summary className="cursor-pointer text-sm font-medium text-red-700 hover:underline">
                    View error output
                  </summary>
                  <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-xl border border-red-200 bg-red-50 p-3 font-mono text-xs leading-relaxed text-red-800">
                    {latestExecution.stderr}
                  </pre>
                </details>
              )}

              <button
                type="button"
                onClick={() => void runBuildAndTest()}
                disabled={busy !== null || active || !latestExecution.patchId}
                className={secondaryButtonClass}
                title={
                  latestExecution.patchId
                    ? 'Re-run build and tests in the sandbox'
                    : 'This execution has no applied patch to re-run'
                }
              >
                {busy === 'build' ? 'Starting…' : 'Re-run build & test'}
              </button>
            </div>
          )}
        </Card>
      )}

      {selectedProjectId && latestExecution && (
        <Card
          title="Step 3 — Results"
          subtitle="The verdict below is exactly what the backend persisted."
        >
          {verificationLoading ? (
            <LoadingState label="Loading verification…" />
          ) : !verification ? (
            <EmptyState
              title="Not verified yet."
              body="Run verification against the latest sandbox execution to record a verdict."
              action={
                <button
                  type="button"
                  onClick={() => void verify()}
                  disabled={!canVerify}
                  className={primaryButtonClass}
                >
                  {busy === 'verify' ? 'Verifying…' : 'Run verification'}
                </button>
              }
            />
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={verdictTone(verification.verdict)}>{verification.verdict}</Badge>
                <Badge tone={buildTone(verification.buildStatus)}>
                  build: {verification.buildStatus}
                </Badge>
                <span className="text-xs tabular-nums text-slate-500">
                  tests {verification.testsPassed}/{verification.testsTotal} passed
                  {verification.testsFailed > 0 && ` · ${verification.testsFailed} failed`}
                  {verification.testsSkipped > 0 && ` · ${verification.testsSkipped} skipped`}
                </span>
                {verification.durationMs != null && (
                  <span className="text-xs tabular-nums text-slate-500">
                    {verification.durationMs} ms
                  </span>
                )}
              </div>

              {verification.verdict === 'VERIFIED' ? (
                <p className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
                  Backend-verified: build passed, tests passed, no new CRITICAL/HIGH findings.
                </p>
              ) : verification.verdict === 'REJECTED' ? (
                <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                  Rejected by backend policy: build, tests, or new CRITICAL/HIGH findings failed.
                </p>
              ) : (
                <p className="text-sm text-slate-500">Verification pending.</p>
              )}

              {verification.sandboxSkipped && (
                <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
                  Sandbox was unavailable for this run, so no result is claimed as verified.
                </p>
              )}

              {verification.logRef && (
                <details>
                  <summary className="cursor-pointer text-sm font-medium text-indigo-700 hover:underline">
                    View verification evidence
                  </summary>
                  <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap rounded-xl border border-indigo-100 bg-white p-3 font-mono text-xs leading-relaxed text-slate-800">
                    {verification.logRef}
                  </pre>
                </details>
              )}
            </div>
          )}
        </Card>
      )}
    </FeaturePage>
  );
}
