import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ReviewRunPipeline } from './ReviewRunPipeline';
import type { ReviewResponse } from '../../../api/types';

function review(status: ReviewResponse['status'], error: string | null = null): ReviewResponse {
  return {
    id: 'r1',
    projectId: 'p1',
    status,
    startedAt: '2026-01-01T00:00:00Z',
    finishedAt: status === 'COMPLETED' || status === 'FAILED' ? '2026-01-01T00:01:00Z' : null,
    durationMs: null,
    findingCount: 0,
    error,
    filesAnalyzed: status === 'COMPLETED' ? 2 : 0,
    filesTotal: 2,
    checks: [],
    files: [],
    rating: { overall: 100, syntax: 100, logic: 100, security: 100, style: 100 },
    activity: [],
    createdAt: '2026-01-01T00:00:00Z',
  };
}

describe('ReviewRunPipeline', () => {
  it.each([
    ['QUEUED', 'WAITING'],
    ['RUNNING', 'RUNNING'],
    ['COMPLETED', 'COMPLETED'],
    ['NOT_ANALYZED', 'COMPLETED'],
  ] as const)('renders %s run state', (status, expected) => {
    render(<ReviewRunPipeline review={review(status)} onRetry={vi.fn()} />);
    expect(screen.getAllByText(expected).length).toBeGreaterThan(0);
  });

  it('renders a plain error and retries only the failed stage', () => {
    const onRetry = vi.fn();
    render(<ReviewRunPipeline review={review('FAILED', 'Provider unavailable')} onRetry={onRetry} />);
    expect(screen.getAllByText('Provider unavailable').length).toBeGreaterThan(0);
    fireEvent.click(screen.getAllByRole('button', { name: 'Retry' })[0]);
    expect(onRetry).toHaveBeenCalledWith('analyze');
  });

  it('shows activity and file progress', () => {
    const data = review('RUNNING');
    data.activity = [{ message: 'Analyzed 1 of 2 files', at: '2026-01-01T00:00:30Z' }];
    data.filesAnalyzed = 1;
    render(<ReviewRunPipeline review={data} onRetry={vi.fn()} />);
    expect(screen.getAllByText('Analyzed 1 of 2 files').length).toBeGreaterThan(0);
  });
});
