import type { ReactNode } from 'react';

/** Standalone auth shell: centered card on a soft gray backdrop. */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-slate-100 text-slate-900">
      <main className="flex flex-1 items-center justify-center px-4 py-10">
        <div className="w-full max-w-md">{children}</div>
      </main>
    </div>
  );
}
