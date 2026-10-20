import { useRef } from 'react';
import { useFocusTrap } from '../hooks/useFocusTrap';
import { secondaryButtonClass } from './ui';

export interface ConfirmFixTarget {
  id: string;
  title: string;
  severity: string;
  filePath: string | null;
  lineStart: number | null;
}

export interface ConfirmFixDialogProps {
  open: boolean;
  /** Findings the user selected; shown so the user sees exactly what will change. */
  findings: ConfirmFixTarget[];
  /** Distinct file paths that the fix is expected to touch. */
  files: string[];
  busy?: boolean;
  error?: string | null;
  onCancel: () => void;
  onConfirm: () => void;
}

/**
 * Explicit confirmation step before anything is changed.
 *
 * The backend will not create a patch or run the coding agent until the user
 * approves here (AGENTS.md rule 20: no destructive change without approval).
 */
export function ConfirmFixDialog({
  open,
  findings,
  files,
  busy = false,
  error = null,
  onCancel,
  onConfirm,
}: ConfirmFixDialogProps) {
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  const dialogRef = useFocusTrap<HTMLDivElement>(
    open,
    () => {
      if (!busy) {
        onCancel();
      }
    },
    confirmRef,
  );

  if (!open) {
    return null;
  }

  const count = findings.length;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      role="presentation"
      onClick={() => {
        if (!busy) {
          onCancel();
        }
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-fix-title"
        tabIndex={-1}
        className="w-full max-w-lg overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="border-b border-slate-100 px-5 py-4">
          <h2 id="confirm-fix-title" className="text-base font-semibold text-indigo-950">
            Fix {count} selected {count === 1 ? 'finding' : 'findings'}?
          </h2>
          <p className="mt-1 text-sm text-slate-500">
            The coding agent will propose changes and the backend will apply them, then
            automatically re-check the result. You can revert each file afterwards.
          </p>
        </div>

        <div className="max-h-64 overflow-y-auto px-5 py-4">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-400">
            Files that will change
          </h3>
          <ul className="mt-2 space-y-1">
            {files.length === 0 ? (
              <li className="text-sm text-slate-500">No file paths recorded for these findings.</li>
            ) : (
              files.map((file) => (
                <li key={file} className="break-all font-mono text-xs text-slate-700">
                  {file}
                </li>
              ))
            )}
          </ul>

          <h3 className="mt-4 text-xs font-semibold uppercase tracking-wide text-slate-400">
            Findings
          </h3>
          <ul className="mt-2 space-y-2">
            {findings.map((finding) => (
              <li key={finding.id} className="text-sm text-slate-700">
                <span className="font-medium text-indigo-950">{finding.title}</span>
                <span className="ml-2 text-xs text-slate-400">
                  {finding.severity}
                  {finding.filePath
                    ? ` · ${finding.filePath}${finding.lineStart != null ? `:${finding.lineStart}` : ''}`
                    : ''}
                </span>
              </li>
            ))}
          </ul>
        </div>

        {error && (
          <p role="alert" className="mx-5 mb-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}

        <div className="flex justify-end gap-2 border-t border-slate-100 bg-slate-50 px-5 py-3">
          <button type="button" className={secondaryButtonClass} onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button
            ref={confirmRef}
            type="button"
            onClick={onConfirm}
            disabled={busy || count === 0}
            className="inline-flex items-center justify-center rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {busy ? 'Applying fixes…' : `Confirm and fix (${count})`}
          </button>
        </div>
      </div>
    </div>
  );
}
