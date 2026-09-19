import type { ReactNode } from 'react';

/** Pill-shaped auth field with a leading icon (shared login/register look). */
export function PillField({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <label className="flex items-center gap-3 rounded-full border border-slate-300 bg-white px-5 py-3 focus-within:border-indigo-500 focus-within:ring-1 focus-within:ring-indigo-500">
      {icon}
      {children}
    </label>
  );
}

/** Inline field icons (no extra dependencies). */
export function EnvelopeIcon() {
  return (
    <svg
      aria-hidden="true"
      className="h-5 w-5 shrink-0 text-slate-500"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
    >
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m3.5 7 8.5 6 8.5-6" />
    </svg>
  );
}

export function LockIcon() {
  return (
    <svg
      aria-hidden="true"
      className="h-5 w-5 shrink-0 text-slate-500"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
    >
      <rect x="5" y="10" width="14" height="10" rx="2" fill="currentColor" stroke="none" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </svg>
  );
}

export function UserIcon() {
  return (
    <svg
      aria-hidden="true"
      className="h-5 w-5 shrink-0 text-slate-500"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
    >
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5 20c1.5-3.5 4-5 7-5s5.5 1.5 7 5" />
    </svg>
  );
}
