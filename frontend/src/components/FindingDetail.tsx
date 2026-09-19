import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { createFixRequest, listFixRequests } from '../api/fixRequests';
import type { FindingResponse, FixRequestResponse } from '../api/types';
import { apiClient, useAuth } from '../auth/AuthContext';
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
        className="absolute inset-0 h-full w-full bg-slate-900/40"
      />
      <div className="absolute inset-y-0 right-0 flex w-full max-w-lg flex-col bg-white shadow-xl">
        <div className="flex items-start gap-2 border-b border-slate-200 p-4">
          <div className="min-w-0 flex-1">
            <div className="mb-1.5 flex flex-wrap items-center gap-1.5">
              <Badge tone={severityTone(finding.severity)}>{finding.severity}</Badge>
              <Badge tone={sourceTone(finding.source)}>{finding.source}</Badge>
              <Badge tone="gray">{finding.status}</Badge>
            </div>
            <h3 className="font-mono text-sm font-semibold text-slate-900">
              {finding.rule ?? finding.title}
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close finding details"
            className="rounded-lg px-2 py-1 text-lg leading-none text-slate-500 hover:bg-slate-100"
          >
            ×
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-4 text-sm">
          <section>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Message
            </h4>
            <p className="text-slate-800">{finding.description || finding.title}</p>
          </section>

          <section>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Location
            </h4>
            <p className="font-mono text-xs text-slate-700">{lineRef}</p>
            {finding.filePath && (
              <button
                type="button"
                onClick={() => onViewFile(finding.filePath as string)}
                className="mt-1 text-sm text-indigo-600 hover:underline"
              >
                View in file
              </button>
            )}
          </section>

          <section>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Remediation guidance
            </h4>
            {meta.suggestedFixHint ? (
              <p className="text-slate-800">{meta.suggestedFixHint}</p>
            ) : (
              <p className="text-slate-500">
                No remediation guidance recorded for this finding.
              </p>
            )}
          </section>

          <section>
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Evidence
            </h4>
            <dl className="space-y-1 text-sm">
              <div className="flex gap-2">
                <dt className="text-slate-500">Analyzer</dt>
                <dd>{meta.analyzer ?? finding.analyzer ?? '—'}</dd>
              </div>
              {meta.toolSeverity && (
                <div className="flex gap-2">
                  <dt className="text-slate-500">Tool severity</dt>
                  <dd>{meta.toolSeverity}</dd>
                </div>
              )}
              {meta.confidence != null && (
                <div className="flex gap-2">
                  <dt className="text-slate-500">Confidence</dt>
                  <dd>{Math.round(meta.confidence * 100)}%</dd>
                </div>
              )}
              {meta.model && (
                <div className="flex gap-2">
                  <dt className="text-slate-500">Model</dt>
                  <dd className="break-all">{meta.model}</dd>
                </div>
              )}
              <div className="flex gap-2">
                <dt className="text-slate-500">Category</dt>
                <dd>{finding.category}</dd>
              </div>
            </dl>
          </section>

          <section className="rounded-lg border border-slate-200 bg-slate-50 p-3">
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Fix request
            </h4>

            {fixLoading ? (
              <p className="text-sm text-slate-500">Loading fix requests…</p>
            ) : fixError ? (
              <p role="alert" className="text-sm text-red-600">
                {fixError}
              </p>
            ) : fixRequests.length === 0 ? (
              <p className="text-sm text-slate-500">No fix requests yet.</p>
            ) : (
              <ul className="mb-3 space-y-2">
                {fixRequests.map((req) => (
                  <li
                    key={req.id}
                    className="flex items-start justify-between gap-2 rounded-md border border-slate-200 bg-white px-3 py-2"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
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
              <p role="status" className="mb-2 rounded-md bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
                {submitSuccess}
              </p>
            )}

            {hasActiveRequest ? (
              <p className="text-sm text-amber-700">
                An active fix request already exists for this finding.
              </p>
            ) : (
              <button
                type="button"
                onClick={() => setDialogOpen(true)}
                className="w-full rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500"
              >
                Fix this issue
              </button>
            )}
          </section>
        </div>
      </div>

      {dialogOpen && (
        <div
          className="absolute inset-0 z-10 flex items-center justify-center bg-slate-900/40 p-4"
          role="dialog"
          aria-label="Fix request dialog"
        >
          <div className="w-full max-w-md rounded-xl border border-slate-200 bg-white p-5 shadow-xl">
            <h3 className="text-base font-semibold text-slate-900">Fix this issue</h3>
            <div className="mt-3 rounded-lg border border-slate-200 bg-slate-50 p-3">
              <p className="font-mono text-sm font-medium text-slate-800">
                {finding.rule ?? finding.title}
              </p>
              <p className="mt-1 text-xs text-slate-500">
                {finding.filePath ?? '—'}
                {finding.lineStart != null ? `:${finding.lineStart}` : ''}
                {' · '}
                {finding.severity} · {finding.analyzer ?? 'unknown'}
              </p>
              {finding.description && (
                <p className="mt-2 line-clamp-3 text-sm text-slate-600">{finding.description}</p>
              )}
            </div>

            <label className="mt-4 block">
              <span className="mb-1 block text-sm font-medium text-slate-700">
                Scope note <span className="font-normal text-slate-400">(optional)</span>
              </span>
              <textarea
                aria-label="Scope note"
                value={scopeNote}
                onChange={(e) => setScopeNote(e.target.value)}
                placeholder="Add context for the fix (e.g., preferred approach, constraints)…"
                maxLength={5000}
                rows={3}
                className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm placeholder:text-slate-400 focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500"
              />
              <span className="mt-1 block text-right text-xs text-slate-400">
                {scopeNote.length}/5000
              </span>
            </label>

            {submitError && (
              <p role="alert" className="mt-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">
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
                className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleSubmit()}
                disabled={submitting}
                className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white hover:bg-indigo-500 disabled:opacity-50"
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
