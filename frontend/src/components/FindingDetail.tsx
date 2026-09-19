import type { FindingResponse } from '../api/types';
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

interface FindingDetailProps {
  finding: FindingResponse;
  onClose: () => void;
  onViewFile: (path: string) => void;
}

/** Slide-over detail panel with clean code-review UX. */
export function FindingDetail({ finding, onClose, onViewFile }: FindingDetailProps) {
  const meta = parseEvidence(finding.evidence);
  const location =
    finding.filePath ?? '—';
  const lineRef =
    finding.lineStart != null
      ? `${location}:${finding.lineStart}${
          finding.lineEnd != null && finding.lineEnd !== finding.lineStart
            ? `-${finding.lineEnd}`
            : ''
        }`
      : location;

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
        </div>
      </div>
    </div>
  );
}
