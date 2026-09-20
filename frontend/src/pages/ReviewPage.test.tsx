import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { ReviewPage } from './ReviewPage';

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
    category: 'STYLE',
    severity: 'MEDIUM',
    source: 'DETERMINISTIC',
    status: 'OPEN',
    analyzer: 'checkstyle',
    rule: 'LineLength',
    title: 'LineLength',
    description: 'Line is longer than 80 characters.',
    filePath: 'Main.java',
    lineStart: 3,
    lineEnd: 3,
    evidence: '{}',
    dedupKey: 'a',
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'f2',
    reviewId: 'r1',
    category: 'SECURITY',
    severity: 'HIGH',
    source: 'AI',
    status: 'OPEN',
    analyzer: 'review-agent',
    rule: null,
    title: 'SQL string concat',
    description: 'User input reaches the query.',
    filePath: 'Dao.java',
    lineStart: 41,
    lineEnd: 41,
    evidence: '{}',
    dedupKey: 'b',
    createdAt: '2026-01-01T00:00:00Z',
  },
];

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

function renderReview(path: string, handler: (url: string, init?: RequestInit) => Response) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request, init?: RequestInit) => handler(String(url), init)) as typeof fetch,
  );
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <ReviewPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function fullHandler(url: string): Response {
  if (url.includes('/projects?')) {
    return pageOf(PROJECTS);
  }
  if (url.includes('/reviews?') || url.includes('/projects/p1/reviews')) {
    return pageOf(REVIEWS);
  }
  if (url.includes('/findings')) {
    return pageOf(FINDINGS);
  }
  return pageOf([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ReviewPage', () => {
  it('shows project context, run status, and finding counts', async () => {
    renderReview('/review?project=p1', fullHandler);
    await waitFor(() => expect(screen.getByText('LineLength')).toBeInTheDocument());
    expect(screen.getByText('SQL string concat')).toBeInTheDocument();
    expect(screen.getByText('2 total')).toBeInTheDocument();
    expect(screen.getByText('1 high')).toBeInTheDocument();
    expect(screen.getByText('1 deterministic')).toBeInTheDocument();
    expect(screen.getByText('1 ai')).toBeInTheDocument();
    expect(screen.getByLabelText('Select project')).toBeInTheDocument();
  });

  it('links each finding to its detail page without inventing data', async () => {
    renderReview('/review?project=p1', fullHandler);
    await waitFor(() => expect(screen.getByText('LineLength')).toBeInTheDocument());
    const link = screen.getByRole('link', { name: /Investigate finding LineLength/ });
    expect(link).toHaveAttribute('href', '/projects/p1/reviews/r1/findings/f1');
  });

  it('filters by severity and searches across title, file, and rule', async () => {
    renderReview('/review?project=p1', fullHandler);
    await waitFor(() => expect(screen.getByText('SQL string concat')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Severity'), { target: { value: 'HIGH' } });
    expect(screen.queryByText('LineLength')).not.toBeInTheDocument();
    expect(screen.getByText('SQL string concat')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Severity'), { target: { value: 'ALL' } });
    fireEvent.change(screen.getByLabelText('Search findings'), { target: { value: 'dao.java' } });
    expect(screen.queryByText('LineLength')).not.toBeInTheDocument();
    expect(screen.getByText('SQL string concat')).toBeInTheDocument();
  });

  it('explains the empty workspace without fake data', async () => {
    renderReview('/review', () => pageOf([]));
    await waitFor(() => expect(screen.getByText('No projects to review.')).toBeInTheDocument());
  });
});
