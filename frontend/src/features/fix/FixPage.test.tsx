import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import { FixPage } from './FixPage';

const PROJECTS = [
  { id: 'p1', name: 'demo', sourceType: 'ZIP_UPLOAD', language: 'java', fileCount: 2 },
];

const REVIEWS = [
  {
    id: 'r1',
    projectId: 'p1',
    status: 'COMPLETED',
    startedAt: '2026-01-01T00:00:00Z',
    finishedAt: '2026-01-01T00:01:00Z',
    durationMs: 60000,
    findingCount: 2,
    error: null,
    createdAt: '2026-01-01T00:00:00Z',
  },
];

const FINDINGS = [
  {
    id: 'f1',
    reviewId: 'r1',
    category: 'SECURITY',
    severity: 'HIGH',
    source: 'AI',
    status: 'OPEN',
    analyzer: 'review-agent',
    rule: null,
    title: 'SQL string concat',
    description: 'User input reaches the query.',
    filePath: 'src/Dao.java',
    lineStart: 41,
    lineEnd: 41,
    evidence: '{}',
    dedupKey: 'a',
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'f2',
    reviewId: 'r1',
    category: 'BUG',
    severity: 'MEDIUM',
    source: 'DETERMINISTIC',
    status: 'OPEN',
    analyzer: 'spotbugs',
    rule: 'NP_NULL',
    title: 'Possible null dereference',
    description: 'Value may be null.',
    filePath: 'src/Service.java',
    lineStart: 7,
    lineEnd: 7,
    evidence: '{}',
    dedupKey: 'b',
    createdAt: '2026-01-01T00:00:00Z',
  },
];

const PIPELINE = {
  projectId: 'p1',
  stages: [
    { key: 'import', label: 'Import', state: 'COMPLETED', startedAt: null, finishedAt: null, durationMs: null, detail: '2 file(s) imported', error: null, checkRunId: null },
    { key: 'analyze', label: 'Analyze', state: 'COMPLETED', startedAt: null, finishedAt: null, durationMs: null, detail: 'Agents: REVIEW', error: null, checkRunId: null },
    { key: 'review', label: 'Review', state: 'COMPLETED', startedAt: null, finishedAt: null, durationMs: null, detail: '2 finding(s)', error: null, checkRunId: null },
    { key: 'fix', label: 'Fix', state: 'RUNNING', startedAt: null, finishedAt: null, durationMs: null, detail: 'Fix applied', error: null, checkRunId: 'c1' },
    { key: 'verify', label: 'Verify', state: 'SKIPPED', startedAt: null, finishedAt: null, durationMs: null, detail: 'Not verified: sandbox unavailable', error: null, checkRunId: 'c2' },
  ],
};

const FIX_RUN = {
  checkRunId: 'c1',
  projectId: 'p1',
  status: 'SUCCEEDED',
  error: null,
  steps: [],
  summary: { fixed: 1, stillOpen: 1, newlyIntroduced: 0 },
  changedFiles: [
    { path: 'src/Dao.java', patchId: 'patch-1', status: 'APPLIED', diff: '+ preparedStatement' },
  ],
  verificationVerdict: 'VERIFIED',
  sandboxSkipped: false,
  startedAt: '2026-01-01T00:02:00Z',
  finishedAt: '2026-01-01T00:03:00Z',
};

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

function renderFix(handler: (url: string, init?: RequestInit) => Response) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request, init?: RequestInit) =>
      handler(String(url), init)) as typeof fetch,
  );
  return render(
    <MemoryRouter initialEntries={['/fix?project=p1']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <FixPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('FixPage', () => {
  it('asks for confirmation before changing anything, then shows the re-check result', async () => {
    const calls: string[] = [];
    renderFix((url, init) => {
      const target = url;
      const method = init?.method ?? 'GET';
      if (method === 'POST') calls.push(`${method} ${target}`);
      if (target.includes('/pipeline')) return json(PIPELINE);
      if (target.includes('/fix-requests')) {
        return json({ projectId: 'p1', fixRequestIds: ['fr1', 'fr2'], created: 2, skipped: 0 }, 201);
      }
      if (target.includes('/fix-runs')) return json(FIX_RUN, 200);
      if (target.includes('/projects?')) return pageOf(PROJECTS);
      if (target.includes('/findings')) return pageOf(FINDINGS);
      if (target.includes('/reviews')) return pageOf(REVIEWS);
      return pageOf([]);
    });

    // Findings load and the pipeline reflects real backend state.
    await waitFor(() => expect(screen.getByText('SQL string concat')).toBeInTheDocument());
    expect(screen.getByTestId('stage-state-fix')).toHaveTextContent('Running');
    expect(screen.getByTestId('stage-state-verify')).toHaveTextContent('Skipped');

    // Select everything, then open the confirmation — nothing is sent yet.
    fireEvent.click(screen.getByLabelText('Select all findings'));
    expect(screen.getByRole('button', { name: 'Fix selected (2)' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Fix selected (2)' }));

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Fix 2 selected findings?');
    expect(within(dialog).getByText('src/Dao.java')).toBeInTheDocument();
    expect(calls).toEqual([]);

    // Confirm: now the backend is asked to fix and re-check.
    fireEvent.click(screen.getByRole('button', { name: 'Confirm and fix (2)' }));

    await waitFor(() => expect(screen.getByText('After re-check')).toBeInTheDocument());
    expect(calls.some((call) => call.includes('POST') && call.includes('/fix-requests'))).toBe(true);
    expect(calls.some((call) => call.includes('POST') && call.includes('/fix-runs'))).toBe(true);

    // Before/after summary and per-file diff with a revert action.
    expect(screen.getByText('Fixed')).toBeInTheDocument();
    expect(screen.getByText('Still open')).toBeInTheDocument();
    expect(screen.getByText('New')).toBeInTheDocument();
    expect(screen.getByText('src/Dao.java')).toBeInTheDocument();
    expect(screen.getByText(/preparedStatement/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Revert' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Accept changes' })).toBeInTheDocument();
  });

  it('cancelling the dialog changes nothing', async () => {
    const calls: string[] = [];
    renderFix((url, init) => {
      const target = url;
      if ((init?.method ?? 'GET') === 'POST') calls.push(target);
      if (target.includes('/pipeline')) return json(PIPELINE);
      if (target.includes('/projects?')) return pageOf(PROJECTS);
      if (target.includes('/findings')) return pageOf(FINDINGS);
      if (target.includes('/reviews')) return pageOf(REVIEWS);
      return pageOf([]);
    });

    await waitFor(() => expect(screen.getByText('SQL string concat')).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('Select all findings'));
    fireEvent.click(screen.getByRole('button', { name: 'Fix selected (2)' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls).toEqual([]);
    expect(screen.queryByText('After re-check')).not.toBeInTheDocument();
  });

  it('says there is nothing to fix when every finding is closed', async () => {
    renderFix((url) => {
      if (url.includes('/projects?')) return pageOf(PROJECTS);
      if (url.includes('/findings')) {
        return pageOf(FINDINGS.map((finding) => ({ ...finding, status: 'FIXED' })));
      }
      if (url.includes('/reviews')) return pageOf(REVIEWS);
      return pageOf([]);
    });

    await waitFor(() => expect(screen.getByText('No open findings.')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Open Review' })).toHaveAttribute(
      'href',
      '/review?project=p1',
    );
  });
});
