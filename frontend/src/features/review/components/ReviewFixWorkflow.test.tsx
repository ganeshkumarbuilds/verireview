import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../../auth/AuthContext';
import type { FindingResponse, ReviewResponse } from '../../../api/types';
import { ReviewFixWorkflow } from './ReviewFixWorkflow';

const SAMPLE_REVIEW: ReviewResponse = {
  id: 'r1',
  projectId: 'p1',
  status: 'COMPLETED',
  startedAt: '2026-01-01T00:00:00Z',
  finishedAt: '2026-01-01T00:01:00Z',
  durationMs: 60000,
  findingCount: 1,
  fixRound: 2,
  error: null,
  filesAnalyzed: 5,
  filesTotal: 5,
  checks: [],
  files: [],
  rating: { overall: 80, syntax: 80, logic: 80, security: 80, style: 80 },
  activity: [],
  createdAt: '2026-01-01T00:00:00Z',
};

const SAMPLE_FINDINGS: FindingResponse[] = [
  {
    id: 'f1',
    reviewId: 'r1',
    category: 'SECURITY',
    severity: 'HIGH',
    source: 'DETERMINISTIC',
    status: 'OPEN',
    analyzer: 'spotbugs',
    rule: 'SQL_INJECTION',
    title: 'SQL injection risk',
    description: 'Concatenation detected in query.',
    filePath: 'src/File.java',
    lineStart: 10,
    lineEnd: 12,
    evidence: '{}',
    evidenceSnippet: 'User input reaches the SQL query.',
    explanation: 'The query is assembled from untrusted input.',
    suggestedFix: 'Use a parameterized query.',
    confidence: 0.95,
    toolConfirmed: true,
    dedupKey: 'k1',
    createdAt: '2026-01-01T00:00:00Z',
  },
];

function installFetch(handler: (url: string) => Response) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request) => handler(String(url))) as typeof fetch,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ReviewFixWorkflow', () => {
  it('reads authoritative fix round from the backend review', async () => {
    installFetch((url) => {
      if (url.includes('/fix-runs')) {
        return new Response(JSON.stringify([]), { status: 200 });
      }
      return new Response('[]', { status: 200 });
    });

    render(
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <ReviewFixWorkflow
          projectId="p1"
          review={SAMPLE_REVIEW}
          findings={SAMPLE_FINDINGS}
          onRunComplete={() => {}}
        />
      </AuthProvider>,
    );

    await waitFor(() =>
      expect(
        screen.getByText('Current round: 2 of 5 (server-authoritative)'),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText('Fix selected (0)')).toBeInTheDocument();
  });

  it('renders fix limit reached when server round is 5', async () => {
    installFetch(() => new Response('[]', { status: 200 }));

    render(
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <ReviewFixWorkflow
          projectId="p1"
          review={{ ...SAMPLE_REVIEW, fixRound: 5 }}
          findings={SAMPLE_FINDINGS}
          onRunComplete={() => {}}
        />
      </AuthProvider>,
    );

    await waitFor(() =>
      expect(screen.getByText('Fix limit reached')).toBeInTheDocument(),
    );
    expect(
      screen.getByText(/Five fix rounds are allowed for one review/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Fix selected/)).not.toBeInTheDocument();
  });
});
