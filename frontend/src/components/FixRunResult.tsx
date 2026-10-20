import type { FixRunChangedFileResponse, FixRunDetailResponse } from '../api/types';
import { secondaryButtonClass } from './ui';

export interface FixRunResultProps {
  detail: FixRunDetailResponse;
  /** Patch id currently being reverted (disables that row's button). */
  revertingPatchId?: string | null;
  revertedPatchIds?: string[];
  onRevert?: (file: FixRunChangedFileResponse) => void;
  onAccept?: () => void;
  accepted?: boolean;
}

function SummaryTile({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: 'green' | 'amber' | 'red';
}) {
  const toneClass =
    tone === 'green'
      ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
      : tone === 'amber'
        ? 'border-amber-200 bg-amber-50 text-amber-700'
        : 'border-red-200 bg-red-50 text-red-700';
  return (
    <div className={`rounded-xl border px-4 py-3 ${toneClass}`}>
      <div className="text-2xl font-bold leading-none">{value}</div>
      <div className="mt-1 text-xs font-semibold uppercase tracking-wide">{label}</div>
    </div>
  );
}

/**
 * Before/after summary of a fix run and its automatic re-check.
 *
 * When the sandbox was unavailable nothing was actually verified, so the
 * component says so plainly instead of implying the fix is confirmed.
 */
export function FixRunResult({
  detail,
  revertingPatchId = null,
  revertedPatchIds = [],
  onRevert,
  onAccept,
  accepted = false,
}: FixRunResultProps) {
  const { summary, changedFiles } = detail;
  const applied = detail.verificationVerdict === 'VERIFIED';
  const skipped = detail.sandboxSkipped;

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-sm font-semibold text-indigo-950">After re-check</h3>
        <p className="mt-0.5 text-xs text-slate-500">
          {skipped
            ? 'Not verified: sandbox unavailable. The changes were applied but could not be checked.'
            : applied
              ? 'Verified against the sandbox build, tests, and findings gate.'
              : 'The re-check did not pass.'}
        </p>
      </div>

      <div className="grid grid-cols-3 gap-3">
        <SummaryTile label="Fixed" value={summary.fixed} tone="green" />
        <SummaryTile label="Still open" value={summary.stillOpen} tone="amber" />
        <SummaryTile label="New" value={summary.newlyIntroduced} tone="red" />
      </div>

      <div className="flex flex-wrap items-center gap-2">
        {skipped ? (
          <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-600 ring-1 ring-inset ring-slate-500/20">
            Not verified: sandbox unavailable
          </span>
        ) : (
          <span
            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset ${
              applied
                ? 'bg-emerald-50 text-emerald-700 ring-emerald-600/25'
                : 'bg-red-50 text-red-700 ring-red-600/25'
            }`}
          >
            {applied ? 'Verified' : 'Rejected'}
          </span>
        )}
        {detail.error && <span className="break-words text-xs text-red-700">{detail.error}</span>}
      </div>

      <div>
        <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-400">
          Changed files ({changedFiles.length})
        </h4>
        {changedFiles.length === 0 ? (
          <p className="mt-2 text-sm text-slate-500">No files were changed.</p>
        ) : (
          <ul className="mt-2 space-y-3">
            {changedFiles.map((file) => {
              const key = `${file.patchId}:${file.path}`;
              const reverted = revertedPatchIds.includes(file.patchId);
              return (
                <li key={key} className="overflow-hidden rounded-xl border border-slate-200">
                  <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 bg-slate-50 px-3 py-2">
                    <span className="min-w-0 break-all font-mono text-xs text-slate-700">{file.path}</span>
                    <div className="flex items-center gap-2">
                      {reverted && (
                        <span className="text-[11px] font-semibold uppercase text-slate-500">
                          Reverted
                        </span>
                      )}
                      {onRevert && !reverted && (
                        <button
                          type="button"
                          onClick={() => onRevert(file)}
                          disabled={revertingPatchId === file.patchId}
                          className="rounded-lg border border-slate-300 bg-white px-2.5 py-1 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
                        >
                          {revertingPatchId === file.patchId ? 'Reverting…' : 'Revert'}
                        </button>
                      )}
                    </div>
                  </div>
                  {file.diff && (
                    <pre className="max-h-64 overflow-auto bg-slate-900 px-3 py-2 text-[11px] leading-relaxed text-slate-100">
                      <code>{file.diff}</code>
                    </pre>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>

      {onAccept && (
        <div className="flex items-center gap-3">
          <button
            type="button"
            className={secondaryButtonClass}
            onClick={onAccept}
            disabled={accepted}
          >
            {accepted ? 'Accepted' : 'Accept changes'}
          </button>
          {accepted && <span className="text-xs text-emerald-700">Kept the applied changes.</span>}
        </div>
      )}
    </div>
  );
}
