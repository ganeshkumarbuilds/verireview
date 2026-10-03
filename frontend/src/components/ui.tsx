import type { ReactNode } from 'react';

/** Shared button/input class tokens for a consistent professional look. */
export const primaryButtonClass =
  'inline-flex items-center justify-center gap-1.5 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm shadow-indigo-200 transition-colors hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60';

export const secondaryButtonClass =
  'inline-flex items-center justify-center gap-1.5 rounded-lg border border-indigo-200 bg-white px-4 py-2 text-sm font-semibold text-indigo-700 shadow-sm transition-colors hover:bg-indigo-50 hover:border-indigo-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60';

export const quietButtonClass =
  'inline-flex items-center justify-center gap-1 rounded-lg px-3 py-1.5 text-sm font-medium text-indigo-700 transition-colors hover:bg-indigo-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60';

export const inputClass =
  'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 transition-colors hover:border-indigo-300 focus:border-indigo-600 focus:outline-none focus:ring-2 focus:ring-indigo-600/30';

export const textareaClass =
  'w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 placeholder:text-slate-400 transition-colors hover:border-indigo-300 focus:border-indigo-600 focus:outline-none focus:ring-2 focus:ring-indigo-600/30 resize-y min-h-[80px]';

export const selectClass =
  'rounded-lg border border-slate-300 bg-white px-2 py-1.5 text-sm text-slate-900 transition-colors hover:border-indigo-300 focus:border-indigo-600 focus:outline-none focus:ring-2 focus:ring-indigo-600/30';

/** Accessible progress bar. */
export function Progress({
  value,
  max = 100,
  className,
}: {
  value: number;
  max?: number;
  className?: string;
}) {
  const pct = Math.min(100, Math.max(0, Math.round((value / max) * 100)));
  return (
    <div
      role="progressbar"
      aria-valuenow={pct}
      aria-valuemin={0}
      aria-valuemax={100}
      className={`h-2 rounded-full bg-slate-200 overflow-hidden ${className ?? ''}`}
    >
      <div
        className="h-full bg-indigo-600 transition-all duration-500 ease-out"
        style={{ width: `${pct}%` }}
      />
    </div>
  );
}
export function Card({
  title,
  subtitle,
  actions,
  children,
}: {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="rounded-2xl border border-indigo-100 bg-white p-5 shadow-sm shadow-indigo-100">
      <div className="mb-3 flex flex-wrap items-start gap-2">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold tracking-tight text-indigo-950">{title}</h2>
          {subtitle && <p className="mt-0.5 text-xs text-slate-500">{subtitle}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>
      <div className="text-sm text-slate-600">{children}</div>
    </section>
  );
}

/** Page header with clear visual hierarchy: title, description, actions. */
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start gap-3">
      <div className="min-w-0 flex-1">
        <h1 className="text-2xl font-bold tracking-tight text-indigo-950">{title}</h1>
        {description && <p className="mt-1 max-w-2xl text-sm leading-relaxed text-slate-600">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** Key metric for dashboard stat cards. */
export function Stat({
  label,
  value,
  hint,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
}) {
  return (
    <div>
      <p className="text-xs font-semibold uppercase tracking-wider text-indigo-400">{label}</p>
      <p className="mt-1 text-3xl font-bold tracking-tight text-indigo-950">{value}</p>
      {hint && <div className="mt-1 text-sm text-slate-600">{hint}</div>}
    </div>
  );
}

type BadgeTone = 'gray' | 'green' | 'red' | 'amber' | 'blue' | 'violet';

const TONES: Record<BadgeTone, string> = {
  gray: 'bg-slate-100 text-slate-600 ring-slate-600/20',
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-600/25',
  red: 'bg-red-50 text-red-700 ring-red-600/25',
  amber: 'bg-amber-50 text-amber-700 ring-amber-600/25',
  blue: 'bg-sky-50 text-sky-700 ring-sky-600/25',
  violet: 'bg-violet-50 text-violet-700 ring-violet-600/25',
};

/** Status/severity/source badge. Tones mirror the backend finding enums so
 *  later phases can map DETERMINISTIC/AI/VERIFIED and severities directly. */
export function Badge({ tone = 'gray', children }: { tone?: BadgeTone; children: ReactNode }) {
  return (
    <span
      className={`inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset ${TONES[tone]}`}
    >
      {children}
    </span>
  );
}

/** Accessible error banner with optional retry (wired to existing reloads). */
export function ErrorAlert({
  message,
  onRetry,
  retryLabel = 'Retry',
}: {
  message: string;
  onRetry?: () => void;
  retryLabel?: string;
}) {
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      <p className="min-w-0 flex-1">{message}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="shrink-0 rounded-lg border border-red-300 bg-white px-3 py-1.5 text-sm font-semibold text-red-700 transition-colors hover:bg-red-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-600 focus-visible:ring-offset-2"
        >
          {retryLabel}
        </button>
      )}
    </div>
  );
}

/** Loading indicator with spinner; label text stays screen-reader friendly. */
export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <p role="status" className="flex items-center gap-2.5 py-4 text-sm text-slate-600">
      <span
        aria-hidden="true"
        className="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-indigo-200 border-t-indigo-600"
      />
      {label}
    </p>
  );
}

/** Skeleton rows for list loading states (visual only, no content shift). */
export function SkeletonList({ rows = 3 }: { rows?: number }) {
  return (
    <div aria-hidden="true" className="space-y-2 py-2">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="h-9 animate-pulse rounded-lg bg-indigo-50" />
      ))}
    </div>
  );
}

/** Friendly empty state with optional action (existing links/buttons). */
export function EmptyState({
  title,
  body,
  action,
}: {
  title: string;
  body?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="rounded-xl border border-dashed border-indigo-200 bg-indigo-50/50 px-4 py-8 text-center">
      <p className="text-sm font-semibold text-indigo-950">{title}</p>
      {body && <div className="mx-auto mt-1 max-w-md text-sm leading-relaxed text-slate-600">{body}</div>}
      {action && <div className="mt-4 flex items-center justify-center gap-2">{action}</div>}
    </div>
  );
}

/**
 * Animation utilities for smooth transitions and stage animations.
 * These work with Tailwind's existing transition utilities.
 */
export const animationStyles = {
  /** Smooth pulse for active states */
  pulse: 'animate-pulse',
  /** Gentle spin for loading indicators */
  spin: 'animate-spin',
  /** Fade in from 0 to 1 opacity */
  fadeIn: 'animate-in fade-in duration-300',
  /** Fade out from 1 to 0 opacity */
  fadeOut: 'animate-out fade-out duration-200',
  /** Slide in from left */
  slideInLeft: 'animate-in slide-in-from-left duration-300',
  /** Slide in from right */
  slideInRight: 'animate-in slide-in-from-right duration-300',
  /** Slide in from top */
  slideInTop: 'animate-in slide-in-from-top duration-300',
  /** Slide in from bottom */
  slideInBottom: 'animate-in slide-in-from-bottom duration-300',
  /** Scale up from 0.95 to 1 */
  scaleIn: 'animate-in zoom-in-95 duration-200',
  /** Scale down from 1 to 0.95 */
  scaleOut: 'animate-out zoom-out-95 duration-150',
} as const;

/**
 * Stage transition class names for pipeline visualization.
 * Usage: combine base classes with state-specific classes.
 */
export const pipelineStageClasses = {
  base: 'relative flex gap-3 pb-4 last:pb-0 transition-all duration-500 ease-out',
  connector: 'absolute left-[13px] top-7 h-[calc(100%-1.5rem)] w-px transition-colors duration-500',
  icon: {
    base: 'flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold transition-all duration-300 ease-out',
    waiting: 'bg-white text-slate-400 ring-1 ring-inset ring-slate-200',
    active: 'bg-indigo-600 text-white ring-2 ring-indigo-300 shadow-lg shadow-indigo-600/30 animate-pulse',
    completed: 'bg-emerald-600 text-white ring-2 ring-emerald-300',
    failed: 'bg-red-600 text-white ring-2 ring-red-300 animate-bounce',
  },
  label: {
    base: 'min-w-0 flex-1 pt-0.5 transition-opacity duration-300',
    active: 'opacity-100',
    completed: 'opacity-100',
    waiting: 'opacity-70',
  },
} as const;

/**
 * Returns computed className for a pipeline stage icon based on state.
 */
export function getPipelineStageIconClass(state: 'waiting' | 'active' | 'completed' | 'failed'): string {
  const base = pipelineStageClasses.icon.base;
  switch (state) {
    case 'active':
      return `${base} ${pipelineStageClasses.icon.active}`;
    case 'completed':
      return `${base} ${pipelineStageClasses.icon.completed}`;
    case 'failed':
      return `${base} ${pipelineStageClasses.icon.failed}`;
    default:
      return `${base} ${pipelineStageClasses.icon.waiting}`;
  }
}

/**
 * Returns computed className for a pipeline stage label based on state.
 */
export function getPipelineStageLabelClass(state: 'waiting' | 'active' | 'completed' | 'failed'): string {
  const base = pipelineStageClasses.label.base;
  switch (state) {
    case 'active':
      return `${base} ${pipelineStageClasses.label.active}`;
    case 'completed':
      return `${base} ${pipelineStageClasses.label.completed}`;
    case 'failed':
      return `${base} opacity-100`;
    default:
      return `${base} ${pipelineStageClasses.label.waiting}`;
  }
}

/**
 * Returns computed className for a pipeline stage connector line.
 */
export function getPipelineConnectorClass(state: 'waiting' | 'active' | 'completed' | 'failed'): string {
  const base = pipelineStageClasses.connector;
  switch (state) {
    case 'active':
    case 'completed':
      return `${base} bg-emerald-200`;
    case 'failed':
      return `${base} bg-red-200`;
    default:
      return `${base} bg-indigo-100`;
  }
}

/**
 * Agent types in the VeriReview pipeline.
 */
export type AgentType = 
  | 'planner' 
  | 'coding' 
  | 'build' 
  | 'verified' 
  | 'review' 
  | 'generation'
  | 'analysis';

/**
 * Agent state for animation purposes.
 */
export type AgentState = 'waiting' | 'active' | 'completed' | 'failed' | 'skipped';

/**
 * Agent configuration with display properties.
 */
export interface AgentConfig {
  type: AgentType;
  label: string;
  icon: ReactNode;
  description: string;
  order: number;
}

/**
 * Default agent pipeline configuration.
 */
export const AGENT_PIPELINE: AgentConfig[] = [
  { 
    type: 'planner', 
    label: 'Planner Agent', 
    icon: '📋',
    description: 'Analyzes requirements and creates execution plan',
    order: 1 
  },
  { 
    type: 'coding', 
    label: 'Coding Agent', 
    icon: '💻',
    description: 'Generates code changes and fixes',
    order: 2 
  },
  { 
    type: 'build', 
    label: 'Build & Test', 
    icon: '🔨',
    description: 'Compiles project and runs test suite',
    order: 3 
  },
  { 
    type: 'verified', 
    label: 'Verified Agent', 
    icon: '✅',
    description: 'Evaluates build/test evidence for verification',
    order: 4 
  },
  { 
    type: 'review', 
    label: 'Review Agent', 
    icon: '🔍',
    description: 'Performs AI-powered code review',
    order: 5 
  },
];

/**
 * Returns the color theme for an agent type.
 */
export function getAgentColor(agentType: AgentType): { 
  primary: string; 
  light: string; 
  dark: string; 
  glow: string;
} {
  switch (agentType) {
    case 'planner':
      return { primary: 'indigo', light: 'indigo-50', dark: 'indigo-700', glow: 'indigo-600/30' };
    case 'coding':
      return { primary: 'violet', light: 'violet-50', dark: 'violet-700', glow: 'violet-600/30' };
    case 'build':
      return { primary: 'amber', light: 'amber-50', dark: 'amber-700', glow: 'amber-600/30' };
    case 'verified':
      return { primary: 'emerald', light: 'emerald-50', dark: 'emerald-700', glow: 'emerald-600/30' };
    case 'review':
      return { primary: 'sky', light: 'sky-50', dark: 'sky-700', glow: 'sky-600/30' };
    case 'generation':
      return { primary: 'purple', light: 'purple-50', dark: 'purple-700', glow: 'purple-600/30' };
    case 'analysis':
      return { primary: 'blue', light: 'blue-50', dark: 'blue-700', glow: 'blue-600/30' };
    default:
      return { primary: 'slate', light: 'slate-50', dark: 'slate-700', glow: 'slate-600/30' };
  }
}

/**
 * Returns computed className for an agent stage based on state.
 */
export function getAgentStageClass(
  agentType: AgentType, 
  state: AgentState,
  includeBase = true
): string {
  const colors = getAgentColor(agentType);
  const base = includeBase 
    ? 'relative flex items-start gap-3 rounded-xl p-4 transition-all duration-500 ease-out border'
    : 'transition-all duration-500 ease-out';
  
  switch (state) {
    case 'active':
      return `${base} border-${colors.primary}-300 bg-${colors.light} ring-2 ring-${colors.primary}-200 shadow-lg shadow-${colors.glow} animate-pulse`;
    case 'completed':
      return `${base} border-${colors.primary}-200 bg-${colors.light} ring-1 ring-${colors.primary}-200`;
    case 'failed':
      return `${base} border-red-200 bg-red-50 ring-1 ring-red-200 animate-bounce`;
    case 'skipped':
      return `${base} border-slate-200 bg-slate-50 opacity-60`;
    default: // waiting
      return `${base} border-slate-200 bg-white`;
  }
}

/**
 * Returns the icon class for an agent stage based on state.
 */
export function getAgentIconClass(agentType: AgentType, state: AgentState): string {
  const colors = getAgentColor(agentType);
  const base = 'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl text-lg font-bold transition-all duration-300 ease-out';
  
  switch (state) {
    case 'active':
      return `${base} bg-${colors.primary}-600 text-white ring-3 ring-${colors.primary}-200 shadow-xl shadow-${colors.glow} animate-pulse`;
    case 'completed':
      return `${base} bg-${colors.primary}-600 text-white ring-2 ring-${colors.primary}-300`;
    case 'failed':
      return `${base} bg-red-600 text-white ring-2 ring-red-300 animate-bounce`;
    case 'skipped':
      return `${base} bg-slate-300 text-slate-500`;
    default: // waiting
      return `${base} bg-white text-slate-300 ring-1 ring-inset ring-slate-200`;
  }
}

/**
 * Returns the label class for an agent stage based on state.
 */
export function getAgentLabelClass(state: AgentState): string {
  const base = 'min-w-0 flex-1 pt-1 transition-all duration-300';
  switch (state) {
    case 'active':
      return `${base} opacity-100`;
    case 'completed':
      return `${base} opacity-100`;
    case 'failed':
      return `${base} opacity-100 text-red-700`;
    case 'skipped':
      return `${base} opacity-50 text-slate-400 line-through`;
    default:
      return `${base} opacity-60 text-slate-500`;
  }
}

/**
 * Returns the connector line class between agent stages.
 */
export function getAgentConnectorClass(state: AgentState, agentColor: string): string {
  const base = 'absolute left-5 top-10 h-[calc(100%-2.5rem)] w-0.5 transition-colors duration-500';
  switch (state) {
    case 'active':
    case 'completed':
      return `${base} bg-${agentColor}-300`;
    case 'failed':
      return `${base} bg-red-300`;
    default:
      return `${base} bg-slate-200`;
  }
}

/**
 * Animated agent status indicator - shows pulsing dot with tooltip.
 */
export function AgentStatusIndicator({ 
  state, 
  agentType, 
  className = '',
  showLabel = true 
}: { 
  state: AgentState; 
  agentType: AgentType; 
  className?: string; 
  showLabel?: boolean;
}) {
  const colors = getAgentColor(agentType);
  const agent = AGENT_PIPELINE.find(a => a.type === agentType);
  
  const dotClasses = {
    waiting: 'h-2.5 w-2.5 rounded-full bg-slate-300',
    active: `h-2.5 w-2.5 rounded-full bg-${colors.primary}-500 animate-pulse shadow-[0_0_8px_${colors.primary}-400]`,
    completed: `h-2.5 w-2.5 rounded-full bg-${colors.primary}-500`,
    failed: 'h-2.5 w-2.5 rounded-full bg-red-500 animate-bounce',
    skipped: 'h-2.5 w-2.5 rounded-full bg-slate-300 opacity-50',
  };
  
  return (
    <div className={`flex items-center gap-2 ${className}`} title={agent?.label}>
      <span className={dotClasses[state]} />
      {showLabel && (
        <span className={`text-xs font-medium ${state === 'active' ? `text-${colors.dark}` : 'text-slate-500'}`}>
          {agent?.label}
        </span>
      )}
    </div>
  );
}

/**
 * Animated progress ring for agent completion percentage.
 */
export function AgentProgressRing({ 
  progress, 
  size = 48, 
  strokeWidth = 4,
  agentType,
  className = ''
}: { 
  progress: number; 
  size?: number; 
  strokeWidth?: number;
  agentType?: AgentType;
  className?: string;
}) {
  const colors = agentType ? getAgentColor(agentType) : { primary: 'indigo' };
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference * (1 - Math.min(1, Math.max(0, progress / 100)));
  
  return (
    <div className={`relative inline-flex ${className}`} style={{ width: size, height: size }}>
      <svg width={size} height={size} className="transform -rotate-90">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={`#e0e7ef`}
          strokeWidth={strokeWidth}
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={`#${colors.primary === 'indigo' ? '6366f1' : colors.primary === 'violet' ? '8b5cf6' : colors.primary === 'amber' ? 'f59e0b' : colors.primary === 'emerald' ? '10b981' : colors.primary === 'sky' ? '0ea5e9' : '6366f1'}`}
          strokeWidth={strokeWidth}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          className="transition-all duration-700 ease-out"
          style={{ strokeDasharray: circumference, strokeDashoffset: offset }}
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="text-xs font-bold text-slate-700">{Math.round(progress)}%</span>
      </div>
    </div>
  );
}

/**
 * Agent timeline step - for showing sequential agent execution with animations.
 */
export function AgentTimelineStep({ 
  agent, 
  state, 
  duration, 
  error,
  isLast = false,
  className = ''
}: { 
  agent: AgentConfig; 
  state: AgentState; 
  duration?: number; 
  error?: string;
  isLast?: boolean;
  className?: string;
}) {
  const colors = getAgentColor(agent.type);
  const stageClass = getAgentStageClass(agent.type, state);
  const iconClass = getAgentIconClass(agent.type, state);
  const labelClass = getAgentLabelClass(state);
  const connectorClass = getAgentConnectorClass(state, colors.primary);
  
  return (
    <div className={`relative ${className}`}>
      <div className={`${stageClass} ${isLast ? 'pb-0' : ''}`}>
        <div className="flex items-start gap-3">
          <div className="relative flex-shrink-0">
            <span className={iconClass}>{agent.icon}</span>
            {!isLast && (
              <div className={connectorClass} style={{ height: `calc(100% + 1.5rem)` }} />
            )}
          </div>
          <div className={`min-w-0 flex-1 ${labelClass}`}>
            <div className="flex items-center gap-2">
              <p className="text-sm font-bold text-slate-800">{agent.label}</p>
              {state === 'active' && (
                <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded-full bg-${colors.primary}-100 text-${colors.dark} animate-pulse`}>
                  Working
                </span>
              )}
              {state === 'completed' && (
                <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded-full bg-${colors.primary}-100 text-${colors.dark}`}>
                  Completed
                </span>
              )}
              {state === 'failed' && (
                <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded-full bg-red-100 text-red-700">
                  Failed
                </span>
              )}
              {state === 'skipped' && (
                <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded-full bg-slate-100 text-slate-500">
                  Skipped
                </span>
              )}
            </div>
            <p className="mt-1 text-xs leading-relaxed text-slate-500">{agent.description}</p>
            {(duration || error) && (
              <div className="mt-2 flex flex-wrap items-center gap-3 text-xs">
                {duration && (
                  <span className={`flex items-center gap-1 text-${colors.dark}`}>
                    <span className="h-1.5 w-1.5 rounded-full bg-current" />
                    {Math.round(duration / 1000)}s
                  </span>
                )}
                {error && (
                  <span className="flex items-center gap-1 text-red-600">
                    <span className="h-1.5 w-1.5 rounded-full bg-current" />
                    {error}
                  </span>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * Full agent pipeline visualization with smooth animations.
 */
export function AgentPipeline({ 
  agents, 
  states, 
  className = '',
}: { 
  agents: AgentConfig[]; 
  states: Record<AgentType, AgentState>;
  className?: string;
}) {
  return (
    <div className={`space-y-0 ${className}`}>
      {agents.map((agent, index) => (
        <AgentTimelineStep
          key={agent.type}
          agent={agent}
          state={states[agent.type] || 'waiting'}
          isLast={index === agents.length - 1}
        />
      ))}
    </div>
  );
}
