import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import { getExecution, listExecutions, startExecution } from '../api/executions';
import { applyPatch, getPatchForFixRequest, proposePatch } from '../api/patches';
import {
  getVerificationByExecution,
  runVerification,
} from '../api/verifications';
import type {
  ExecutionRunResponse,
  ExecutionStatus,
  FixRequestResponse,
  PatchResponse,
  PatchStatus,
  VerificationRunResponse,
} from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import { Badge } from './ui';

const DEFAULT_POLL_MS = 3000;
const LOG_PREVIEW_CHARS = 4000;

function isActiveExecution(status: ExecutionStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING';
}

function patchTone(status: PatchStatus): 'amber' | 'blue' | 'red' | 'gray' {
  switch (status) {
    case 'PROPOSED':
      return 'amber';
    case 'APPLIED':
      return 'blue';
    case 'REJECTED':
      return 'red';
    default:
      return 'gray';
  }
}

function executionTone(status: ExecutionStatus): 'blue' | 'green' | 'red' | 'gray' {
  switch (status) {
    case 'PENDING':
    case 'RUNNING':
      return 'blue';
    case 'SUCCESS':
      return 'green';
    case 'FAILED':
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

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function truncateLog(value: string | null): string | null {
  if (value == null) return null;
  if (value.length <= LOG_PREVIEW_CHARS) return value;
  return `${value.slice(0, LOG_PREVIEW_CHARS)}\n…[truncated, ${value.length - LOG_PREVIEW_CHARS} more chars]`;
}

type PatchState = 'loading' | 'empty' | 'ready' | 'error';
type BusyAction = 'propose' | 'apply' | 'execute' | 'verify' | null;

interface FixWorkflowProps {
  fixRequest: FixRequestResponse;
  /** Poll interval for active executions (ms). Overridable in tests. */
  pollMs?: number;
  /** Called when the fix request itself changed (propose moves REQUESTED → IN_PROGRESS). */
  onFixRequestChanged?: () => void;
}

/**
 * Fix → Apply → Execute → Verify workflow for one fix request.
 * All state comes from owner-scoped backend APIs; VERIFIED/REJECTED is
 * displayed exactly as the backend persisted it (never derived client-side).
 */
export function FixWorkflow({ fixRequest, pollMs = DEFAULT_POLL_MS, onFixRequestChanged }: FixWorkflowProps) {
  const { token } = useAuth();
  const [patchState, setPatchState] = useState<PatchState>('loading');
  const [patch, setPatch] = useState<PatchResponse | null>(null);
  const [patchError, setPatchError] = useState<string | null>(null);
  const [busy, setBusy] = useState<BusyAction>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [execution, setExecution] = useState<ExecutionRunResponse | null>(null);
  const [verification, setVerification] = useState<VerificationRunResponse | null>(null);
  const [verificationError, setVerificationError] = useState<string | null>(null);
  const pollTimer = useRef<number | null>(null);

  const stopPolling = useCallback(() => {
    if (pollTimer.current !== null) {
      window.clearInterval(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  useEffect(() => stopPolling, [stopPolling]);

  const loadVerificationFor = useCallback(
    async (authToken: string, executionId: string) => {
      try {
        const found = await getVerificationByExecution(apiClient(), authToken, executionId);
        setVerification(found);
      } catch (err) {
        if (err instanceof ApiError && err.status === 404) {
          setVerification(null);
        } else {
          setVerificationError(err instanceof ApiError ? err.message : 'Could not load verification.');
        }
      }
    },
    [],
  );

  /** Recover prior execution + verification for a patch (e.g. after reload). */
  const recoverRunState = useCallback(
    async (authToken: string, currentPatch: PatchResponse) => {
      try {
        const runs = await listExecutions(apiClient(), authToken, fixRequest.projectId);
        const latest = runs.find((run) => run.patchId === currentPatch.id) ?? null;
        setExecution(latest);
        if (latest && !isActiveExecution(latest.status)) {
          await loadVerificationFor(authToken, latest.id);
        } else {
          setVerification(null);
        }
      } catch {
        // Recovery is best-effort: the workflow stays usable without history.
        setExecution(null);
      }
    },
    [fixRequest.projectId, loadVerificationFor],
  );

  const loadPatch = useCallback(async () => {
    if (!token) return;
    stopPolling();
    setPatchState('loading');
    setPatchError(null);
    setActionError(null);
    setVerificationError(null);
    setExecution(null);
    setVerification(null);
    try {
      const found = await getPatchForFixRequest(apiClient(), token, fixRequest.id);
      setPatch(found);
      setPatchState('ready');
      await recoverRunState(token, found);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setPatch(null);
        setPatchState('empty');
      } else {
        setPatch(null);
        setPatchState('error');
        setPatchError(err instanceof ApiError ? err.message : 'Could not load the patch.');
      }
    }
  }, [token, fixRequest.id, recoverRunState, stopPolling]);

  useEffect(() => {
    void loadPatch();
  }, [loadPatch]);

  // Poll while the execution is active; stop on terminal state, then load verification.
  useEffect(() => {
    if (!token || !execution || !isActiveExecution(execution.status)) {
      return undefined;
    }
    const executionId = execution.id;
    const tick = async () => {
      try {
        const updated = await getExecution(apiClient(), token, executionId);
        setExecution(updated);
        if (!isActiveExecution(updated.status)) {
          stopPolling();
          await loadVerificationFor(token, executionId);
        }
      } catch {
        stopPolling();
      }
    };
    stopPolling();
    pollTimer.current = window.setInterval(() => void tick(), pollMs);
    return stopPolling;
  }, [token, execution, pollMs, loadVerificationFor, stopPolling]);

  const fail = (err: unknown, fallback: string) => {
    setActionError(err instanceof ApiError ? err.message : fallback);
  };

  const handlePropose = async () => {
    if (!token || busy) return;
    setBusy('propose');
    setActionError(null);
    try {
      const created = await proposePatch(apiClient(), token, fixRequest.id);
      setPatch(created);
      setPatchState('ready');
      setExecution(null);
      setVerification(null);
      onFixRequestChanged?.();
    } catch (err) {
      fail(err, 'Could not propose a patch.');
    } finally {
      setBusy(null);
    }
  };

  const handleApply = async () => {
    if (!token || !patch || busy) return;
    setBusy('apply');
    setActionError(null);
    try {
      const updated = await applyPatch(apiClient(), token, patch.id);
      setPatch(updated);
    } catch (err) {
      fail(err, 'Could not apply the patch.');
    } finally {
      setBusy(null);
    }
  };

  const handleExecute = async () => {
    if (!token || !patch || busy) return;
    setBusy('execute');
    setActionError(null);
    setVerificationError(null);
    try {
      const run = await startExecution(apiClient(), token, fixRequest.projectId, patch.id);
      setExecution(run);
      if (!isActiveExecution(run.status)) {
        await loadVerificationFor(token, run.id);
      }
    } catch (err) {
      fail(err, 'Could not start the execution.');
    } finally {
      setBusy(null);
    }
  };

  const handleVerify = async () => {
    if (!token || !execution || busy) return;
    setBusy('verify');
    setActionError(null);
    try {
      const result = await runVerification(apiClient(), token, execution.id);
      setVerification(result);
    } catch (err) {
      fail(err, 'Could not run verification.');
    } finally {
      setBusy(null);
    }
  };

  const executionActive = execution != null && isActiveExecution(execution.status);
  const executionTerminal = execution != null && !isActiveExecution(execution.status);
  const canPropose = patchState === 'empty' && fixRequest.status === 'REQUESTED';

  return (
    <section aria-label="Fix apply execute verify workflow" className="rounded-lg border border-slate-200 bg-slate-50 p-3">
      <h4 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
        Fix → Apply → Execute → Verify
      </h4>

      {actionError && (
        <p role="alert" className="mb-2 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">
          {actionError}
        </p>
      )}

      {/* Step 1–2: patch */}
      {patchState === 'loading' && <p className="text-sm text-slate-500">Loading patch…</p>}
      {patchState === 'error' && (
        <div className="space-y-2">
          <p role="alert" className="text-sm text-red-600">{patchError}</p>
          <button
            type="button"
            onClick={() => void loadPatch()}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Retry
          </button>
        </div>
      )}
      {patchState === 'empty' && (
        <div className="space-y-2">
          <p className="text-sm text-slate-500">No patch yet for this fix request.</p>
          {canPropose ? (
            <button
              type="button"
              onClick={() => void handlePropose()}
              disabled={busy !== null}
              className="w-full rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-50"
            >
              {busy === 'propose' ? 'Proposing…' : 'Propose patch'}
            </button>
          ) : (
            <p className="text-sm text-slate-500">
              A patch can be proposed while the fix request is REQUESTED (current: {fixRequest.status}).
            </p>
          )}
        </div>
      )}
      {patchState === 'ready' && patch && (
        <div className="space-y-3">
          <div className="rounded-md border border-slate-200 bg-white px-3 py-2">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={patchTone(patch.status)}>{patch.status}</Badge>
              <span className="text-xs text-slate-500">
                {patch.filesChanged} files · +{patch.additions} −{patch.deletions} · {formatDate(patch.createdAt)}
              </span>
            </div>
            {patch.validationError && (
              <p className="mt-1 text-xs text-red-600">{patch.validationError}</p>
            )}
            <details className="mt-2">
              <summary className="cursor-pointer text-sm text-indigo-600 hover:underline">
                View diff
              </summary>
              <pre className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-2 font-mono text-xs text-slate-800">
                {patch.diff}
              </pre>
            </details>
            {patch.status === 'PROPOSED' && (
              <button
                type="button"
                onClick={() => void handleApply()}
                disabled={busy !== null}
                className="mt-2 w-full rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-50"
              >
                {busy === 'apply' ? 'Applying…' : 'Apply patch'}
              </button>
            )}
            {patch.status === 'REJECTED' && (
              <p className="mt-2 text-sm text-red-600">This patch was rejected and cannot be executed.</p>
            )}
          </div>

          {/* Step 3: execution */}
          {patch.status === 'APPLIED' && (
            <div className="rounded-md border border-slate-200 bg-white px-3 py-2">
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-400">
                Sandbox execution
              </p>
              {!execution && (
                <div className="space-y-2">
                  <p className="text-sm text-slate-500">No executions yet for this patch.</p>
                  <button
                    type="button"
                    onClick={() => void handleExecute()}
                    disabled={busy !== null}
                    className="w-full rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-50"
                  >
                    {busy === 'execute' ? 'Starting…' : 'Run in sandbox'}
                  </button>
                </div>
              )}
              {execution && (
                <div className="space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={executionTone(execution.status)}>{execution.status}</Badge>
                    <Badge tone={buildTone(execution.buildStatus)}>build: {execution.buildStatus}</Badge>
                    {execution.exitCode != null && (
                      <span className="text-xs text-slate-500">exit {execution.exitCode}</span>
                    )}
                    {execution.durationMs != null && (
                      <span className="text-xs text-slate-500">{execution.durationMs} ms</span>
                    )}
                  </div>
                  {executionActive && (
                    <p className="text-sm text-slate-500">Execution in progress…</p>
                  )}
                  {executionTerminal && (execution.stdout || execution.stderr) && (
                    <details>
                      <summary className="cursor-pointer text-sm text-indigo-600 hover:underline">
                        View logs
                      </summary>
                      {execution.stdout && (
                        <pre className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-2 font-mono text-xs text-slate-800">
                          {truncateLog(execution.stdout)}
                        </pre>
                      )}
                      {execution.stderr && (
                        <pre className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap rounded-lg border border-red-200 bg-red-50 p-2 font-mono text-xs text-red-800">
                          {truncateLog(execution.stderr)}
                        </pre>
                      )}
                    </details>
                  )}
                  {executionTerminal && (
                    <button
                      type="button"
                      onClick={() => void handleExecute()}
                      disabled={busy !== null}
                      className="mt-1 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                    >
                      {busy === 'execute' ? 'Starting…' : 'Re-run in sandbox'}
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Step 4: verification */}
          {executionTerminal && (
            <div className="rounded-md border border-slate-200 bg-white px-3 py-2">
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-400">
                Verification
              </p>
              {verificationError && (
                <p role="alert" className="mb-1 text-sm text-red-600">{verificationError}</p>
              )}
              {!verification && (
                <div className="space-y-2">
                  <p className="text-sm text-slate-500">Not verified yet.</p>
                  <button
                    type="button"
                    onClick={() => void handleVerify()}
                    disabled={busy !== null}
                    className="w-full rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-500 disabled:opacity-50"
                  >
                    {busy === 'verify' ? 'Verifying…' : 'Verify'}
                  </button>
                </div>
              )}
              {verification && (
                <div className="space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={verdictTone(verification.verdict)}>{verification.verdict}</Badge>
                    <Badge tone={buildTone(verification.buildStatus)}>build: {verification.buildStatus}</Badge>
                    <span className="text-xs text-slate-500">
                      tests {verification.testsPassed}/{verification.testsTotal} passed
                      {verification.testsFailed > 0 && ` · ${verification.testsFailed} failed`}
                      {verification.testsSkipped > 0 && ` · ${verification.testsSkipped} skipped`}
                    </span>
                  </div>
                  {verification.verdict === 'VERIFIED' ? (
                    <p className="text-sm text-emerald-700">
                      Backend-verified: build passed, tests passed, no new CRITICAL/HIGH findings.
                    </p>
                  ) : verification.verdict === 'REJECTED' ? (
                    <p className="text-sm text-red-600">
                      Rejected by backend policy: build, tests, or new CRITICAL/HIGH findings failed.
                    </p>
                  ) : (
                    <p className="text-sm text-slate-500">Verification pending.</p>
                  )}
                  {verification.logRef && (
                    <details>
                      <summary className="cursor-pointer text-sm text-indigo-600 hover:underline">
                        View verification evidence
                      </summary>
                      <pre className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-2 font-mono text-xs text-slate-800">
                        {truncateLog(verification.logRef)}
                      </pre>
                    </details>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </section>
  );
}
