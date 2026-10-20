import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AgentPipelineView, formatDuration, formatStageTime } from './AgentPipelineView';
import type { PipelineStageResponse } from '../api/types';

function stage(overrides: Partial<PipelineStageResponse>): PipelineStageResponse {
  return {
    key: 'analyze',
    label: 'Analyze',
    state: 'WAITING',
    startedAt: null,
    finishedAt: null,
    durationMs: null,
    detail: null,
    error: null,
    checkRunId: null,
    ...overrides,
  };
}

describe('AgentPipelineView', () => {
  it('renders every stage in the order it was given', () => {
    render(
      <AgentPipelineView
        stages={[
          stage({ key: 'import', label: 'Import', state: 'COMPLETED' }),
          stage({ key: 'analyze', label: 'Analyze', state: 'RUNNING' }),
          stage({ key: 'review', label: 'Review', state: 'WAITING' }),
          stage({ key: 'fix', label: 'Fix', state: 'WAITING' }),
          stage({ key: 'verify', label: 'Verify', state: 'WAITING' }),
        ]}
      />,
    );
    const list = screen.getByRole('list', { name: 'Agent pipeline' });
    const labels = within(list)
      .getAllByRole('listitem')
      .map((item) => within(item).getAllByText(/Import|Analyze|Review|Fix|Verify/)[0].textContent);
    expect(labels).toEqual(['Import', 'Analyze', 'Review', 'Fix', 'Verify']);
  });

  it('shows a distinct state for waiting, running, completed, and failed stages', () => {
    render(
      <AgentPipelineView
        onRetry={vi.fn()}
        stages={[
          stage({ key: 'import', label: 'Import', state: 'COMPLETED' }),
          stage({ key: 'analyze', label: 'Analyze', state: 'RUNNING' }),
          stage({ key: 'review', label: 'Review', state: 'WAITING' }),
          stage({ key: 'fix', label: 'Fix', state: 'FAILED', error: 'The coding agent is unavailable.' }),
        ]}
      />,
    );

    expect(screen.getByTestId('stage-state-import')).toHaveTextContent('Completed');
    expect(screen.getByTestId('stage-state-analyze')).toHaveTextContent('Running');
    expect(screen.getByTestId('stage-state-review')).toHaveTextContent('Waiting');
    expect(screen.getByTestId('stage-state-fix')).toHaveTextContent('Failed');

    // The failed stage shows the exact backend error and a retry action.
    expect(screen.getByRole('alert')).toHaveTextContent('The coding agent is unavailable.');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('never shows a green tick for a skipped (sandbox unavailable) stage', () => {
    render(
      <AgentPipelineView
        stages={[
          stage({
            key: 'verify',
            label: 'Verify',
            state: 'SKIPPED',
            detail: 'Not verified: sandbox unavailable',
          }),
        ]}
      />,
    );
    expect(screen.getByTestId('stage-state-verify')).toHaveTextContent('Skipped');
    expect(screen.getByText('Not verified: sandbox unavailable')).toBeInTheDocument();
    expect(screen.getByTestId('stage-icon-verify')).toHaveTextContent('–');
  });

  it('shows start/end times and duration for a completed stage', () => {
    render(
      <AgentPipelineView
        stages={[
          stage({
            key: 'review',
            label: 'Review',
            state: 'COMPLETED',
            startedAt: '2026-01-01T10:00:00Z',
            finishedAt: '2026-01-01T10:00:02Z',
            durationMs: 2000,
            detail: '3 finding(s) — 1 critical',
          }),
        ]}
      />,
    );
    expect(screen.getByText(/2.0s/)).toBeInTheDocument();
    expect(screen.getByText('3 finding(s) — 1 critical')).toBeInTheDocument();
  });

  it('invokes onRetry with the failed stage', () => {
    const onRetry = vi.fn();
    const failed = stage({ key: 'fix', label: 'Fix', state: 'FAILED', error: 'Boom' });
    render(<AgentPipelineView stages={[failed]} onRetry={onRetry} />);
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledWith(failed);
  });

  it('handles empty or missing data without crashing', () => {
    render(<AgentPipelineView stages={[]} />);
    expect(screen.getByText('No pipeline data yet.')).toBeInTheDocument();
  });
});

describe('pipeline formatting helpers', () => {
  it('formats missing values safely', () => {
    expect(formatStageTime(null)).toBe('—');
    expect(formatStageTime('not-a-date')).toBe('—');
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(250)).toBe('250ms');
    expect(formatDuration(1500)).toBe('1.5s');
  });
});
