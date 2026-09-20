import { Badge } from './ui';

export type PipelineStageState = 'done' | 'active' | 'planned';

export interface PipelineStage {
  key: string;
  label: string;
  state: PipelineStageState;
  /** Optional honest qualifier, e.g. "awaiting a future phase". */
  hint?: string;
}

/** Canonical generation pipeline order shared by the draft workspace and the
 *  generated-project workspace panel. */
export const GENERATION_PIPELINE_STAGES = [
  'Requirement',
  'Planning',
  'Coding',
  'Review',
  'Fix',
  'Test',
  'Verify',
  'Complete',
] as const;

function stageTone(state: PipelineStageState): 'gray' | 'blue' | 'green' {
  switch (state) {
    case 'done':
      return 'green';
    case 'active':
      return 'blue';
    default:
      return 'gray';
  }
}

function stageLabel(state: PipelineStageState): string {
  switch (state) {
    case 'done':
      return 'Done';
    case 'active':
      return 'Active';
    default:
      return 'Planned';
  }
}

/**
 * Honest workflow preview: which stages are done/active versus merely
 * planned. Never shows progress, logs, or agent activity — parents decide
 * each stage's state from real backend data.
 */
export function GenerationPipeline({ stages }: { stages: PipelineStage[] }) {
  return (
    <ol aria-label="Generation pipeline" className="space-y-0">
      {stages.map((stage, index) => (
        <li key={stage.key} className="relative flex gap-3 pb-4 last:pb-0">
          {index < stages.length - 1 && (
            <span aria-hidden="true" className="absolute left-[13px] top-7 h-[calc(100%-1.5rem)] w-px bg-indigo-100" />
          )}
          <span
            aria-hidden="true"
            className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold ${
              stage.state === 'done'
                ? 'bg-emerald-600 text-white'
                : stage.state === 'active'
                  ? 'bg-indigo-600 text-white'
                  : 'bg-white text-slate-400 ring-1 ring-inset ring-slate-200'
            }`}
          >
            {stage.state === 'done' ? '✓' : index + 1}
          </span>
          <span className="min-w-0 flex-1 pt-0.5">
            <span className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-semibold text-indigo-950">{stage.label}</span>
              <Badge tone={stageTone(stage.state)}>{stageLabel(stage.state)}</Badge>
            </span>
            {stage.hint && <span className="mt-0.5 block text-xs text-slate-500">{stage.hint}</span>}
          </span>
        </li>
      ))}
    </ol>
  );
}
