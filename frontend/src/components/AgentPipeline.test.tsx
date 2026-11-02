import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AgentPipeline, type AgentStep } from './AgentPipeline';

const steps: AgentStep[] = [
  { key: 'planning', label: 'Planning', state: 'waiting', startedAt: null, finishedAt: null, currentActivity: null, error: null },
  { key: 'coding', label: 'Coding', state: 'running', startedAt: '2026-01-01T00:00:00Z', finishedAt: null, currentActivity: 'Writing src/App.tsx', error: null, filesWritten: 2, filesPlanned: 4 },
  { key: 'reviewing', label: 'Reviewing', state: 'completed', startedAt: '2026-01-01T00:00:00Z', finishedAt: '2026-01-01T00:00:02Z', currentActivity: 'Checks complete', error: null },
  { key: 'verifying', label: 'Verifying', state: 'failed', startedAt: '2026-01-01T00:00:00Z', finishedAt: '2026-01-01T00:00:03Z', currentActivity: null, error: 'Build failed' },
  { key: 'rating', label: 'Rating', state: 'skipped', startedAt: null, finishedAt: null, currentActivity: null, error: null },
];

describe('AgentPipeline', () => {
  it('renders every real stage state and coding progress', () => {
    render(<AgentPipeline steps={steps} now={Date.parse('2026-01-01T00:00:01Z')} onRetry={vi.fn()} />);
    expect(screen.getByText('Waiting')).toBeInTheDocument();
    expect(screen.getByText('Running')).toBeInTheDocument();
    expect(screen.getByText('Completed')).toBeInTheDocument();
    expect(screen.getByText('Failed')).toBeInTheDocument();
    expect(screen.getByText('Skipped')).toBeInTheDocument();
    expect(screen.getByText('2 of 4 files')).toBeInTheDocument();
    expect(screen.getByText('Writing src/App.tsx')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry Verifying' })).toBeInTheDocument();
  });

  it('uses an indeterminate bar when coding counts are unavailable', () => {
    render(<AgentPipeline steps={[{ ...steps[1], filesWritten: null, filesPlanned: null }]} now={Date.now()} />);
    expect(screen.getByText('Progress updating')).toBeInTheDocument();
    expect(screen.queryByText(/of \d+ files/)).not.toBeInTheDocument();
  });
});
