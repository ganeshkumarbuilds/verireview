import { Link } from 'react-router-dom';
import { Badge, Card, secondaryButtonClass } from './ui';

/** Shared presentation helpers. All data comes from backend DTOs;
 *  nothing here invents findings, runs, or verdicts. */

export function reviewStatusTone(status: string): 'gray' | 'blue' | 'green' | 'red' {
  switch (status) {
    case 'COMPLETED':
      return 'green';
    case 'FAILED':
      return 'red';
    case 'RUNNING':
    case 'QUEUED':
      return 'blue';
    default:
      return 'gray';
  }
}

export function severityTone(severity: string): 'red' | 'amber' | 'blue' | 'gray' {
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

export function sourceTone(source: string): 'blue' | 'violet' | 'green' | 'gray' {
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

export function formatDate(value: string | null): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** Initials avatar used across dashboard / projects / history lists. */
export function ProjectAvatar({ name }: { name: string }) {
  return (
    <span
      aria-hidden="true"
      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-indigo-100 text-xs font-bold text-indigo-700"
    >
      {name.slice(0, 2).toUpperCase()}
    </span>
  );
}

/** Entry point to the real generation wizard (route `/generate`). */
export function GenerateProjectLink({ className }: { className?: string }) {
  return (
    <Link to="/generate" className={className ?? secondaryButtonClass}>
      Generate project
    </Link>
  );
}

/** Small "where am I / what project" crumb used by workspace pages. */
export function WorkspaceCrumb({ items }: { items: { label: string; to?: string }[] }) {
  return (
    <nav aria-label="Breadcrumb">
      <ol className="flex flex-wrap items-center gap-1.5 text-sm">
        {items.map((item, index) => (
          <li key={item.label} className="flex items-center gap-1.5">
            {index > 0 && (
              <span aria-hidden="true" className="text-slate-300">
                /
              </span>
            )}
            {item.to ? (
              <Link
                to={item.to}
                className="rounded px-1 font-medium text-indigo-700 hover:bg-indigo-50 hover:text-indigo-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600"
              >
                {item.label}
              </Link>
            ) : (
              <span aria-current="page" className="px-1 font-semibold text-indigo-950">
                {item.label}
              </span>
            )}
          </li>
        ))}
      </ol>
    </nav>
  );
}

/** Tab-style sub-navigation for the project workspace (anchor sections). */
export function WorkspaceTabs({
  tabs,
  active,
  onSelect,
}: {
  tabs: { id: string; label: string }[];
  active: string;
  onSelect: (id: string) => void;
}) {
  return (
    <nav
      aria-label="Project workspace sections"
      className="sticky top-16 z-10 -mx-1 overflow-x-auto bg-indigo-50/60 px-1 py-1 backdrop-blur"
    >
      <div role="tablist" className="flex min-w-max gap-1">
        {tabs.map((tab) => {
          const selected = tab.id === active;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => {
                onSelect(tab.id);
                document
                  .getElementById(`workspace-${tab.id}`)
                  ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
              }}
              className={`rounded-lg px-3 py-1.5 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 ${
                selected
                  ? 'bg-white text-indigo-800 shadow-sm ring-1 ring-inset ring-indigo-200'
                  : 'text-slate-500 hover:bg-white/70 hover:text-indigo-900'
              }`}
            >
              {tab.label}
            </button>
          );
        })}
      </div>
    </nav>
  );
}

/** Legend card explaining the honest finding-source labels. */
export function FindingSourcesCard() {
  return (
    <Card title="Finding sources" subtitle="Every finding carries one of these labels.">
      <div className="flex flex-wrap gap-2">
        <Badge tone="blue">DETERMINISTIC</Badge>
        <Badge tone="violet">AI</Badge>
        <Badge tone="green">VERIFIED</Badge>
      </div>
      <p className="mt-3 text-sm leading-relaxed text-slate-600">
        Only a sandbox build with passing tests and no new critical findings earns VERIFIED.
      </p>
    </Card>
  );
}
