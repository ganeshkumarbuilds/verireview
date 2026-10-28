import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
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
  if (url.includes('/projects?')) return pageOf(PROJECTS);
  if (url.includes('/findings')) return pageOf(FINDINGS);
  if (url.includes('/reviews')) return pageOf(REVIEWS);
  return pageOf([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ReviewPage', () => {
  it('offers the three source tabs and an existing-project picker', async () => {
    renderReview('/review?project=p1', fullHandler);
    await waitFor(() => expect(screen.getByLabelText('Select project')).toBeInTheDocument());

    expect(screen.getByRole('tab', { name: 'Upload ZIP' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByRole('tab', { name: 'GitHub URL' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Paste code' })).toBeInTheDocument();
    expect(screen.getByRole('tabpanel', { name: 'Upload ZIP' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Back to Dashboard/i })).toHaveAttribute(
      'href',
      '/dashboard',
    );
  });

  it('shows run status and finding counts from the backend', async () => {
    renderReview('/review?project=p1', fullHandler);
    await waitFor(() => expect(screen.getByText('LineLength')).toBeInTheDocument());
    expect(screen.getByText('SQL string concat')).toBeInTheDocument();
    expect(screen.getByText('2 total')).toBeInTheDocument();
    expect(screen.getByText('1 high')).toBeInTheDocument();
    expect(screen.getByText('1 deterministic')).toBeInTheDocument();
    expect(screen.getByText('1 ai')).toBeInTheDocument();
  });

  it('does not show a clean review when all checks were skipped', async () => {
    const review = {
      ...REVIEWS[0],
      status: 'NOT_ANALYZED' as const,
      findingCount: 0,
      error: 'No checks could run: sandbox unavailable',
      filesAnalyzed: 0,
      filesTotal: 2,
      checks: [
        {
          analyzer: 'checkstyle',
          status: 'SKIPPED' as const,
          reason: 'Docker sandbox is disabled in this environment',
          durationMs: 0,
          filesAnalyzed: 0,
          findingCount: 0,
        },
      ],
    };
    renderReview('/review?project=p1', (url) => {
      if (url.includes('/projects?')) return pageOf(PROJECTS);
      if (url.includes('/findings')) return pageOf([]);
      if (url.includes('/reviews')) return pageOf([review]);
      return pageOf([]);
    });

    await waitFor(() => expect(screen.getByText('Analysis not completed.')).toBeInTheDocument());
    expect(screen.queryByText('Clean review.')).not.toBeInTheDocument();
    expect(screen.getByText('Files analyzed: 0/2')).toBeInTheDocument();
    expect(screen.getByText('SKIPPED')).toBeInTheDocument();
    expect(screen.getByText('Docker sandbox is disabled in this environment')).toBeInTheDocument();
  });

  it('shows a clean review only when a check actually ran', async () => {
    const review = {
      ...REVIEWS[0],
      findingCount: 0,
      filesAnalyzed: 2,
      filesTotal: 2,
      checks: [
        {
          analyzer: 'checkstyle',
          status: 'RAN' as const,
          reason: null,
          durationMs: 12,
          filesAnalyzed: 2,
          findingCount: 0,
        },
      ],
    };
    renderReview('/review?project=p1', (url) => {
      if (url.includes('/projects?')) return pageOf(PROJECTS);
      if (url.includes('/findings')) return pageOf([]);
      if (url.includes('/reviews')) return pageOf([review]);
      return pageOf([]);
    });

    await waitFor(() => expect(screen.getByText('Clean review.')).toBeInTheDocument());
    expect(screen.getByText('Files analyzed: 2/2')).toBeInTheDocument();
    expect(screen.getByText('2/2 files · 0 findings · 12 ms')).toBeInTheDocument();
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

  it('imports a GitHub project and moves straight to the analysis step', async () => {
    const imported = {
      id: 'p2',
      name: 'imported',
      description: null,
      sourceType: 'GITHUB',
      language: 'typescript',
      status: 'ACTIVE',
      fileCount: 7,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
    const calls: string[] = [];
    renderReview('/review', (url, init) => {
      const target = String(url);
      calls.push(`${init?.method ?? 'GET'} ${target}`);
      if (target.includes('/projects/import/github/sync')) {
        return new Response(JSON.stringify(imported), { status: 201 });
      }
      if (target.includes('/projects?')) return pageOf([]);
      if (target.includes('/projects/p2/reviews')) return pageOf([]);
      return pageOf([]);
    });

    await waitFor(() =>
      expect(screen.getByText('No projects to review yet.')).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole('tab', { name: 'GitHub URL' }));
    fireEvent.change(screen.getByLabelText('GitHub project name'), {
      target: { value: 'imported' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: 'GitHub URL' }), {
      target: { value: 'https://github.com/acme/imported' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Import from GitHub' }));

    await waitFor(() => expect(screen.getByText(/Imported “imported”/)).toBeInTheDocument());
    expect(calls.some((call) => call.startsWith('POST') && call.includes('/projects/import/github/sync'))).toBe(
      true,
    );
    // Step 2 is now available for the imported project.
    expect(screen.getByRole('heading', { name: 'Step 2 — Run analysis' })).toBeInTheDocument();
    expect(
      screen.getAllByRole('button', { name: 'Start analysis' }).every((button) => !button.hasAttribute('disabled')),
    ).toBe(true);
  });

  it('explains the empty state without fake data', async () => {
    renderReview('/review', () => pageOf([]));
    await waitFor(() =>
      expect(screen.getByText('No projects to review yet.')).toBeInTheDocument(),
    );
    const steps = screen.getByRole('navigation', { name: 'Feature steps' });
    expect(within(steps).getByText('Choose source')).toHaveAttribute('aria-current', 'step');
  });
});
