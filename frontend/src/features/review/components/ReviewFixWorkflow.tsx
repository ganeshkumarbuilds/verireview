import { useCallback, useEffect, useMemo, useState } from 'react';
import type { FindingResponse, FixRunDetailResponse, ReviewFixPreviewResponse, ReviewResponse } from '../../../api/types';
import { previewReviewFix, approveReviewFix } from '../../../api/analysis';
import { listFixRuns, revertPatch } from '../../../api/fixRuns';
import { apiClient, useAuth } from '../../../auth/AuthContext';
import { Badge, Card, ErrorAlert, primaryButtonClass, secondaryButtonClass } from '../../../components/ui';

const MAX_ROUNDS = 5;

export function ReviewFixWorkflow({
  projectId,
  review,
  findings,
  onRunComplete,
}: {
  projectId: string;
  review: ReviewResponse;
  findings: FindingResponse[];
  onRunComplete: () => void;
}) {
  const { token } = useAuth();
  const fixRound = review.fixRound ?? 0;
  const unresolved = useMemo(() => findings.filter((finding) => finding.status === 'OPEN'), [findings]);
  const [selected, setSelected] = useState<string[]>([]);
  const [preview, setPreview] = useState<ReviewFixPreviewResponse | null>(null);
  const [rounds, setRounds] = useState<FixRunDetailResponse[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reloadFixRuns = useCallback(async () => {
    if (!token || !projectId) return;
    try {
      const runs = await listFixRuns(apiClient(), token, projectId);
      setRounds(runs);
    } catch {
      // Non-fatal if fix run history cannot be listed
    }
  }, [projectId, token]);

  useEffect(() => {
    void reloadFixRuns();
  }, [reloadFixRuns, review.id, review.fixRound]);

  if (!token) return null;

  if (fixRound >= MAX_ROUNDS) {
    return (
      <Card title="Fix limit reached" subtitle="Five fix rounds are allowed for one review.">
        <p className="text-sm text-slate-700">
          Current server-authoritative round: {fixRound} of {MAX_ROUNDS}.
          {unresolved.length > 0
            ? ` ${unresolved.length} finding(s) remain unresolved. Start a new review after inspecting the remaining findings.`
            : ' All findings have been addressed.'}
        </p>
        {rounds.length > 0 && (
          <div className="mt-5 border-t border-slate-200 pt-4">
            <h3 className="font-semibold text-indigo-950">Round history</h3>
            <p className="mt-2 text-sm text-slate-600">Current server-authoritative round: {fixRound} of {MAX_ROUNDS}.</p>
            {rounds.map((run, index) => (
              <div key={run.checkRunId} className="mt-2 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-200 p-2 text-sm">
                <span>Round {rounds.length - index}: before/after findings {run.summary.stillOpen + run.summary.fixed}/{run.summary.stillOpen}</span>
                <button type="button" className={secondaryButtonClass} disabled={busy} onClick={() => void revert(run, rounds.length - index)}>Revert</button>
              </div>
            ))}
          </div>
        )}
      </Card>
    );
  }

  if (unresolved.length === 0) {
    if (fixRound > 0) {
      return (
        <Card title="All findings resolved" subtitle={`Completed in ${fixRound} of ${MAX_ROUNDS} server-authoritative fix rounds.`}>
          <p className="text-sm text-slate-700">No open findings remain in this review.</p>
          {rounds.length > 0 && (
            <div className="mt-5 border-t border-slate-200 pt-4">
              <h3 className="font-semibold text-indigo-950">Round history</h3>
              <p className="mt-2 text-sm text-slate-600">Current server-authoritative round: {fixRound} of {MAX_ROUNDS}.</p>
              {rounds.map((run, index) => (
                <div key={run.checkRunId} className="mt-2 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-200 p-2 text-sm">
                  <span>Round {rounds.length - index}: before/after findings {run.summary.stillOpen + run.summary.fixed}/{run.summary.stillOpen}</span>
                  <button type="button" className={secondaryButtonClass} disabled={busy} onClick={() => void revert(run, rounds.length - index)}>Revert</button>
                </div>
              ))}
            </div>
          )}
        </Card>
      );
    }
    return null;
  }

  const toggle = (id: string) => setSelected((current) => current.includes(id)
    ? current.filter((value) => value !== id) : [...current, id]);

  const openPreview = async () => {
    if (selected.length === 0 || busy) return;
    setBusy(true); setError(null);
    try {
      setPreview(await previewReviewFix(apiClient(), token, review.id, selected));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not prepare the fix preview.');
    } finally { setBusy(false); }
  };

  const confirm = async () => {
    if (!preview || busy) return;
    setBusy(true); setError(null);
    try {
      await approveReviewFix(apiClient(), token, projectId, selected);
      setPreview(null); setSelected([]);
      await reloadFixRuns();
      onRunComplete();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The fix could not be started.');
    } finally { setBusy(false); }
  };

  const revert = async (run: FixRunDetailResponse, roundNumber: number) => {
    if (busy) return;
    if (!window.confirm(`Revert round ${roundNumber}?`)) {
      return;
    }
    setBusy(true); setError(null);
    try {
      for (const changed of run.changedFiles) {
        if (changed.patchId) {
          await revertPatch(apiClient(), token, changed.patchId);
        }
      }
      await reloadFixRuns();
      onRunComplete();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The fix round could not be reverted.');
    } finally { setBusy(false); }
  };

  return (
    <Card title="Fix findings" subtitle="Select findings, review the exact affected files, then explicitly approve the fix.">
      {error && <ErrorAlert message={error} onRetry={() => setError(null)} />}
      <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-slate-500">
        Current round: {fixRound} of {MAX_ROUNDS} (server-authoritative)
      </p>
      <div className="space-y-2">
        <label className="flex items-center gap-2 text-sm font-semibold">
          <input type="checkbox" checked={selected.length === unresolved.length} onChange={() => setSelected(selected.length === unresolved.length ? [] : unresolved.map((f) => f.id))} />
          Select all
        </label>
        {unresolved.map((finding) => (
          <label key={finding.id} className="flex items-start gap-2 rounded-lg border border-slate-200 p-2">
            <input type="checkbox" checked={selected.includes(finding.id)} onChange={() => toggle(finding.id)} />
            <span><Badge tone={finding.severity === 'CRITICAL' || finding.severity === 'HIGH' ? 'red' : 'amber'}>{finding.severity}</Badge> {finding.title} · {finding.filePath ?? 'project'}</span>
          </label>
        ))}
      </div>
      <button type="button" className={`${primaryButtonClass} mt-3`} disabled={busy || selected.length === 0} onClick={() => void openPreview()}>
        {busy ? 'Preparing…' : `Fix selected (${selected.length})`}
      </button>

      {preview && (
        <div role="dialog" aria-modal="true" aria-labelledby="review-fix-title" className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/40 p-4">
          <div className="w-full max-w-lg rounded-2xl bg-white p-5 shadow-xl">
            <h2 id="review-fix-title" className="text-lg font-semibold text-indigo-950">Confirm fix</h2>
            <p className="mt-1 text-sm text-slate-600">Only Confirm approves the selected findings and starts the fix run.</p>
            <ul className="my-4 list-disc pl-5 text-sm">{preview.files.map((file) => <li key={file} className="font-mono">{file}</li>)}</ul>
            <div className="flex justify-end gap-2">
              <button type="button" className={secondaryButtonClass} disabled={busy} onClick={() => setPreview(null)}>Cancel</button>
              <button type="button" className={primaryButtonClass} disabled={busy} onClick={() => void confirm()}>{busy ? 'Applying…' : 'Confirm'}</button>
            </div>
          </div>
        </div>
      )}

      {fixRound > 0 && (
        <div className="mt-5 border-t border-slate-200 pt-4">
          <h3 className="font-semibold text-indigo-950">Round history</h3>
          <p className="mt-2 text-sm">Current server-authoritative round: {fixRound} of {MAX_ROUNDS}.</p>
          {rounds.map((run, index) => (
            <div key={run.checkRunId} className="mt-2 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-200 p-2 text-sm">
              <span>Round {rounds.length - index}: before/after findings {run.summary.stillOpen + run.summary.fixed}/{run.summary.stillOpen}</span>
              <button type="button" className={secondaryButtonClass} disabled={busy} onClick={() => void revert(run, rounds.length - index)}>Revert</button>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}
