import { Badge } from './ui';
import { 
  AgentPipeline, 
  AgentConfig, 
  AgentState,
  AGENT_PIPELINE,
  getAgentColor,
  AgentType 
} from './ui';

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
 * Maps generation pipeline stages to agent types for animation.
 */
function mapStageToAgent(stageKey: string): AgentType {
  const map: Record<string, AgentType> = {
    'Requirement': 'planner',
    'Planning': 'planner',
    'Coding': 'coding',
    'Build': 'build',
    'Test': 'build',
    'Verify': 'verified',
    'Review': 'review',
    'Fix': 'coding',
    'Complete': 'verified',
  };
  return map[stageKey] || 'planner';
}

interface PipelineAgent extends AgentConfig {
  state: AgentState;
  hint?: string;
  /** Unique key for React reconciliation - uses the original stage key. */
  stageKey: string;
}

/**
 * Converts PipelineStage to AgentConfig with proper state mapping.
 */
function stagesToAgents(stages: PipelineStage[]): PipelineAgent[] {
  return stages.map(stage => {
    const agentType = mapStageToAgent(stage.key);
    const baseAgent = AGENT_PIPELINE.find(a => a.type === agentType);
    return {
      ...baseAgent!,
      label: stage.label,
      state: stage.state === 'done' ? 'completed' : stage.state === 'active' ? 'active' : 'waiting',
      hint: stage.hint,
      stageKey: stage.key,
    };
  });
}

/**
 * Animated Generation Pipeline - shows smooth agent animations based on real backend state.
 */
export function GenerationPipeline({ 
  stages, 
  currentStageIndex = 0,
  showDetails = true,
  className = ''
}: { 
  stages: PipelineStage[]; 
  currentStageIndex?: number;
  showDetails?: boolean;
  className?: string;
}) {
  const agents = stagesToAgents(stages);
  const states: Record<AgentType, AgentState> = {} as Record<AgentType, AgentState>;
  
  agents.forEach((agent, index) => {
    const stage = stages.find(s => s.key === mapStageToAgent(agent.type));
    if (stage) {
      states[agent.type] = stage.state === 'done' ? 'completed' : stage.state === 'active' ? 'active' : 'waiting';
    } else {
      states[agent.type] = index < currentStageIndex ? 'completed' : index === currentStageIndex ? 'active' : 'waiting';
    }
  });

  return (
    <div className={`space-y-3 ${className}`}>
      <AgentPipeline 
        agents={agents} 
        states={states}
        className="space-y-3"
      />
      {showDetails && (
        <div className="mt-4 space-y-2">
          {stages.map((stage, index) => {
            const isActive = index === currentStageIndex;
            const isDone = stage.state === 'done';
            const agentType = mapStageToAgent(stage.key);
            const colors = getAgentColor(agentType);
            
            return (
              <div 
                key={stage.key}
                className={`flex items-center gap-3 px-3 py-2 rounded-xl transition-all duration-500 ${
                  isActive 
                    ? `bg-${colors.light} ring-2 ring-${colors.primary}-200 shadow-${colors.glow} animate-pulse`
                    : isDone 
                      ? `bg-${colors.light} ring-1 ring-${colors.primary}-200`
                      : 'bg-white'
                }`}
              >
                <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-sm font-bold transition-all duration-300 ${
                  isActive 
                    ? `bg-${colors.primary}-600 text-white ring-2 ring-${colors.primary}-300 shadow-lg shadow-${colors.glow} animate-pulse`
                    : isDone 
                      ? `bg-${colors.primary}-600 text-white ring-2 ring-${colors.primary}-300`
                      : 'bg-white text-slate-400 ring-1 ring-inset ring-slate-200'
                }`}>
                  {isDone ? '✓' : index + 1}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold text-slate-800">{stage.label}</span>
                    <Badge tone={stageTone(stage.state)}>{stageLabel(stage.state)}</Badge>
                  </div>
                  {stage.hint && <span className="mt-0.5 block text-xs text-slate-500">{stage.hint}</span>}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}