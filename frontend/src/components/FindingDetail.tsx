import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { createFixRequest, listFixRequests } from '../api/fixRequests';
import type { FindingResponse, FixRequestResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
import { FixWorkflow } from './FixWorkflow';
import { Badge } from './ui';

export interface EvidenceMeta {
  analyzer?: string;
  rule?: string;
  toolSeverity?: string;
  model?: string;
  promptVersion?: string;
  confidence?: number;
  suggestedFixHint?: string;
  claimedSource?: string;
}

/** Best-effort parse of the evidence JSON blob; never throws. */
export function parseEvidence(evidence: string | null): EvidenceMeta {
  if (!evidence) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(evidence);
    if (typeof parsed !== 'object' || parsed === null) {
      return {};
    }
    const record = parsed as Record<string, unknown>;
    const text = (key: string): string | undefined => {
      const value = record[key];
      return typeof value === 'string' && value.length > 0 ? value : undefined;
    };
    const meta: EvidenceMeta = {};
    const analyzer = text('analyzer');
    if (analyzer) meta.analyzer = analyzer;
    const rule = text('rule');
    if (rule) meta.rule = rule;
    const toolSeverity = text('toolSeverity');
    if (toolSeverity) meta.toolSeverity = toolSeverity;
    const model = text('model');
    if (model) meta.model = model;
    const promptVersion = text('promptVersion');
    if (promptVersion) meta.promptVersion = promptVersion;
    const hint = text('suggestedFixHint');
    if (hint) meta.suggestedFixHint = hint;
    const claimed = text('claimedSource');
    if (claimed) meta.claimedSource = claimed;
    const confidence = record['confidence'];
    if (typeof confidence === 'number' && Number.isFinite(confidence)) {
      meta.confidence = Math.min(1, Math.max(0, confidence));
    }
    return meta;
  } catch {
    return {};
  }
}

function sourceTone(source: string): 'blue' | 'violet' | 'green' | 'gray' {
  switch (source) {
    case 'DETERMINISTIC':
      return 'blue';
    case 'AI':
      return 'violet';
    case 'VERIFIED':
      return 'green';
    default:
      return 'gray';
  }
}

function severityTone(severity: string): 'red' | 'amber' | 'blue' | 'gray' {
  switch (severity) {
    case 'CRITICAL':
    case 'HIGH':
      return 'red';
    case 'MEDIUM':
      return 'amber';
    case 'LOW':
      return 'blue';
    default:
      return 'gray';
  }
}

function fixStatusTone(status: string): 'amber' | 'blue' | 'green' | 'red' | 'gray' {
  switch (status) {
    case 'REQUESTED':
      return 'amber';
    case 'IN_PROGRESS':
      return 'blue';
    case 'COMPLETED':
      return 'green';
    case 'FAILED':
      return 'red';
    case 'CANCELLED':
      return 'gray';
    default:
      return 'gray';
  }
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

interface FindingDetailProps {
  finding: FindingResponse;
  onClose: () => void;
  onViewFile: (path: string) => void;
}

/** Slide-over detail panel with clean code-review UX. */
export function FindingDetail({ finding, onClose, onViewFile }: FindingDetailProps) {
  const { token } = useAuth();
  const meta = parseEvidence(finding.evidence);
  const location = finding.filePath ?? '—';
  const lineRef =
    finding.lineStart != null
      ? `${location}:${finding.lineStart}${
          finding.lineEnd != null && finding.lineEnd !== finding.lineStart
            ? `-${finding.lineEnd}`
            : ''
        }`
      : location;

  const [fixRequests, setFixRequests] = useState<FixRequestResponse[]>([]);
  const [fixLoading, setFixLoading] = useState(true);
  const [fixError, setFixError] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [scopeNote, setScopeNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitSuccess, setSubmitSuccess] = useState<string | null>(null);

  const loadFixRequests = useCallback(async () => {
    if (!token) return;
    setFixLoading(true);
    setFixError(null);
    try {
      const list = await listFixRequests(apiClient(), token, finding.id);
      setFixRequests(list);
    } catch (err) {
      setFixError(err instanceof ApiError ? err.message : 'Could not load fix requests.');
    } finally {
      setFixLoading(false);
    }
  }, [token, finding.id]);

  useEffect(() => {
    void loadFixRequests();
  }, [loadFixRequests]);

  // Reset dialog state when finding changes
  useEffect(() => {
    setDialogOpen(false);
    setScopeNote('');
    setSubmitError(null);
    setSubmitSuccess(null);
  }, [finding.id]);

  const hasActiveRequest = fixRequests.some(
    (r) => r.status === 'REQUESTED' || r.status === 'IN_PROGRESS',
  );

  // History is newest-first (backend orders by createdAt desc): drive the
  // Apply → Execute → Verify workflow from the latest fix request.
  const latestFixRequest = fixRequests.length > 0 ? fixRequests[0] : null;

  const handleSubmit = async () => {
    if (!token) return;
    setSubmitting(true);
    setSubmitError(null);
    setSubmitSuccess(null);
    try {
      const created = await createFixRequest(apiClient(), token, finding.id, scopeNote);
      setSubmitSuccess(`Fix requested — status: ${created.status}`);
      setDialogOpen(false);
      setScopeNote('');
      await loadFixRequests();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setSubmitError('An active fix request already exists for this finding.');
        await loadFixRequests();
      } else {
        setSubmitError(err instanceof ApiError ? err.message : 'Could not create fix request.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-label="Finding details">
      <div
        aria-hidden="true"
        onClick={onClose}
        className="absolute inset-0 h-full w-full bg-indigo-950/40 backdrop-blur-[2px]"
      />
      <div className="absolute inset-y-0 right-0 flex w-full max-w-lg flex-col border-l border-indigo-100 bg-white shadow-2xl">
        <div className="flex shrink-0 items-start gap-2 border-b border-indigo-100 bg-indigo-50/40 p-4">
          <div className="min-w-0 flex-1">
            <div className="mb-1.5 flex flex-wrap items-center gap-1.5">
              <Badge tone={severityTone(finding.severity)}>{finding.severity}</Badge>
              <Badge tone={sourceTone(finding.source)}>{finding.source}</Badge>
              <Badge tone="gray">{finding.status}</Badge>
            </div>
            <h3 className="font-mono text-sm font-bold text-indigo-950">
              {finding.rule ?? finding.title}
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close finding details"
            className="rounded-lg px-2 py-1 text-xl leading-none text-slate-500 transition-colors hover:bg-indigo-50 hover:text-indigo-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
          >
            ×
          </button>
        </div>

        <div className="flex-1 space-y-5 overflow-y-auto p-4 text-sm sm:p-5">
          <section>
            <h4 className="mb-1.5 text-xs font-bold uppercase tracking-wider text-slate-500">
              Message
            </h4>
            <p className="leading-relaxed text-slate-800">{finding.description || finding.title}</p>
          </section>

          <section>
            <h4 className="mb-1.5 text-xs font-bold uppercase tracking-wider text-slate-500">
              Location
            </h4>
            <p className="rounded-lg bg-indigo-50/60 px-3 py-2 font-mono text-xs text-indigo-950">{lineRef}</p>
            {finding.filePath && (
              <button
                type="button"
                onClick={() => onViewFile(finding.filePath as string)}
                className="mt-1.5 rounded-md text-sm font-medium text-indigo-700 transition-colors hover:text-indigo-900 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
              >
                View in file
              </button>
            )}
          </section>

          <section>
            <h4 className="mb-1.5 text-xs font-bold uppercase tracking-wider text-slate-500">
              Remediation guidance
            </h4>
            {meta.suggestedFixHint ? (
              <p className="leading-relaxed text-slate-800">{meta.suggestedFixHint}</p>
            ) : (
              <p className="text-slate-500">
                No remediation guidance recorded for this finding.
              </p>
            )}
          </section>

          <section>
            <h4 className="mb-1.5 text-xs font-bold uppercase tracking-wider text-slate-500">
              Evidence
            </h4>
            <dl className="space-y-1.5 rounded-xl border border-indigo-100 bg-indigo-50/40 p-3 text-sm">
              <div className="flex gap-2">
                <dt className="shrink-0 font-medium text-slate-500">Analyzer</dt>
                <dd className="ml-auto text-right font-medium text-slate-800">{meta.analyzer ?? finding.analyzer ?? '—'}</dd>
              </div>
              {meta.toolSeverity && (
                <div className="flex gap-2">
                  <dt className="shrink-0 font-medium text-slate-500">Tool severity</dt>
                  <dd className="ml-auto text-right font-medium text-slate-800">{meta.toolSeverity}</dd>
                </div>
              )}
              {meta.confidence != null && (
                <div className="flex gap-2">
                  <dt className="shrink-0 font-medium text-slate-500">Confidence</dt>
                  <dd className="ml-auto text-right font-medium text-slate-800">{Math.round(meta.confidence * 100)}%</dd>
                </div>
              )}
              {meta.model && (
                <div className="flex gap-2">
                  <dt className="shrink-0 font-medium text-slate-500">Model</dt>
                  <dd className="ml-auto break-all text-right font-medium text-slate-800">{meta.model}</dd>
                </div>
              )}
              <div className="flex gap-2">
                <dt className="shrink-0 font-medium text-slate-500">Category</dt>
                <dd className="ml-auto text-right font-medium text-slate-800">{finding.category}</dd>
              </div>
            </dl>
          </section>

          <section className="rounded-xl border border-indigo-100 bg-indigo-50/50 p-4">
            <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-indigo-500">
              Fix request
            </h4>

            {fixLoading ? (
              <p className="py-2 text-sm text-slate-500">Loading fix requests…</p>
            ) : fixError ? (
              <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                {fixError}
              </p>
            ) : fixRequests.length === 0 ? (
              <p className="py-1 text-sm text-slate-500">No fix requests yet.</p>
            ) : (
              <ul className="mb-3 space-y-2">
                {fixRequests.map((req) => (
                  <li
                    key={req.id}
                    className="flex items-start justify-between gap-2 rounded-xl border border-indigo-100 bg-white px-3 py-2.5 shadow-sm"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={fixStatusTone(req.status)}>{req.status}</Badge>
                        <span className="text-xs text-slate-500">{formatDate(req.createdAt)}</span>
                      </div>
                      {req.scopeNote && (
                        <p className="mt-1 truncate text-xs text-slate-600">{req.scopeNote}</p>
                      )}
                      {req.error && <p className="mt-1 text-xs text-red-600">{req.error}</p>}
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {submitSuccess && (
              <p role="status" className="mb-2 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
                {submitSuccess}
              </p>
            )}

            {hasActiveRequest ? (
              <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
                An active fix request already exists for this finding.
              </p>
            ) : (
              <button
                type="button"
                onClick={() => setDialogOpen(true)}
                className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm shadow-indigo-200 transition-colors hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
              >
                Fix this issue
              </button>
            )}
          </section>

          {latestFixRequest && (
            <FixWorkflow
              key={latestFixRequest.id}
              fixRequest={latestFixRequest}
              onFixRequestChanged={() => void loadFixRequests()}
            />
          )}
        </div>
      </div>

      {dialogOpen && (
        <div
          className="absolute inset-0 z-10 flex items-center justify-center bg-indigo-950/40 p-4 backdrop-blur-[2px]"
          role="dialog"
          aria-label="Fix request dialog"
        >
          <div className="w-full max-w-md rounded-2xl border border-indigo-100 bg-white p-5 shadow-2xl sm:p-6">
            <h3 className="text-base font-bold tracking-tight text-indigo-950">Fix this issue</h3>
            <div className="mt-3 rounded-xl border border-indigo-100 bg-indigo-50/60 p-3">
              <p className="font-mono text-sm font-semibold text-indigo-950">
                {finding.rule ?? finding.title}
              </p>
              <p className="mt-1 font-mono text-xs text-slate-500">
                {finding.filePath ?? '—'}
                {finding.lineStart != null ? `:${finding.lineStart}` : ''}
                {' · '}
                {finding.severity} · {finding.analyzer ?? 'unknown'}
              </p>
              {finding.description && (
                <p className="mt-2 line-clamp-3 text-sm leading-relaxed text-slate-600">{finding.description}</p>
              )}
            </div>

            <label className="mt-4 block">
              <span className="mb-1 block text-sm font-semibold text-slate-700">
                Scope note <span className="font-normal text-slate-400">(optional)</span>
              </span>
              <textarea
                aria-label="Scope note"
                value={scopeNote}
                onChange={(e) => setScopeNote(e.target.value)}
                placeholder="Add context for the fix (e.g., preferred approach, constraints)…"
                maxLength={5000}
                rows={3}
                className="w-full rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm placeholder:text-slate-400 transition-colors hover:border-indigo-300 focus:border-indigo-600 focus:outline-none focus:ring-2 focus:ring-indigo-600/30"
              />
              <span className="mt-1 block text-right text-xs tabular-nums text-slate-400">
                {scopeNote.length}/5000
              </span>
            </label>

            {submitError && (
              <p role="alert" className="mt-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                {submitError}
              </p>
            )}

            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setDialogOpen(false);
                  setSubmitError(null);
                }}
                disabled={submitting}
                className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleSubmit()}
                disabled={submitting}
                className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm shadow-indigo-200 transition-colors hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:opacity-50"
              >
                {submitting ? 'Submitting…' : 'Submit request'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
