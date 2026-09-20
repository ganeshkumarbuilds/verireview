import type { ReactNode } from 'react';

/** Standalone auth shell: centered card on a soft indigo backdrop. */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-gradient-to-br from-indigo-50 via-white to-indigo-100 text-slate-900">
      <header className="flex items-center justify-center gap-2.5 pt-10">
        <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-indigo-600 font-mono text-sm font-bold text-white shadow-sm shadow-indigo-200">
          VR
        </span>
        <span className="text-base font-bold tracking-wide text-indigo-950">VeriReview</span>
      </header>
      <main className="flex flex-1 items-center justify-center px-4 py-10">
        <div className="w-full max-w-md">{children}</div>
      </main>
      <footer className="pb-8 text-center text-xs font-medium text-indigo-400">
        Generate. Review. Fix. Verify.
      </footer>
    </div>
  );
}
