import type { ReactNode } from 'react';

/** Minimal card for the design system. */
export function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <h2 className="mb-2 text-sm font-semibold text-slate-800">{title}</h2>
      <div className="text-sm text-slate-500">{children}</div>
    </section>
  );
}

type BadgeTone = 'gray' | 'green' | 'red' | 'amber' | 'blue' | 'violet';

const TONES: Record<BadgeTone, string> = {
  gray: 'bg-slate-100 text-slate-600',
  green: 'bg-emerald-100 text-emerald-700',
  red: 'bg-red-100 text-red-700',
  amber: 'bg-amber-100 text-amber-700',
  blue: 'bg-sky-100 text-sky-700',
  violet: 'bg-violet-100 text-violet-700',
};

/** Status/severity/source badge. Tones mirror the backend finding enums so
 *  later phases can map DETERMINISTIC/AI/VERIFIED and severities directly. */
export function Badge({ tone = 'gray', children }: { tone?: BadgeTone; children: ReactNode }) {
  return (
    <span className={`inline-block rounded-full px-2 py-0.5 text-[11px] font-medium ${TONES[tone]}`}>
      {children}
    </span>
  );
}
