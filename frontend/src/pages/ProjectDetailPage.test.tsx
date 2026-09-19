import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { ProjectDetailPage } from './ProjectDetailPage';

const PROJECT = {
  id: 'p1',
  name: 'demo',
  description: null,
  sourceType: 'ZIP_UPLOAD',
  language: 'java',
  status: 'ACTIVE',
  fileCount: 1,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const COMPLETED = {
  id: 'r1',
  projectId: 'p1',
  status: 'COMPLETED',
  startedAt: '2026-01-01T00:00:00Z',
  finishedAt: '2026-01-01T00:01:00Z',
  durationMs: 60000,
  findingCount: 2,
  error: null,
  createdAt: '2026-01-01T00:00:00Z',
};

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
    category: 'CODE_QUALITY',
    severity: 'HIGH',
    source: 'DETERMINISTIC',
    status: 'OPEN',
    analyzer: 'pmd',
    rule: 'SystemPrintln',
    title: 'SystemPrintln',
    description: 'Usage of System.out.',
    filePath: 'Main.java',
    lineStart: 5,
    lineEnd: 5,
    evidence: '{}',
    dedupKey: 'b',
    createdAt: '2026-01-01T00:00:00Z',
  },
];

function renderDetail(handler: (url: string, init?: RequestInit) => Response) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request, init?: RequestInit) => {
      return handler(String(url), init);
    }) as typeof fetch,
  );
  return render(
    <MemoryRouter initialEntries={['/projects/p1']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <Routes>
          <Route path="/projects/:id" element={<ProjectDetailPage />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ProjectDetailPage analysis', () => {
  it('offers to start the first analysis with an empty state', async () => {
    renderDetail((url) => {
      if (url.endsWith('/projects/p1')) {
        return new Response(JSON.stringify(PROJECT), { status: 200 });
      }
      if (url.includes('/files')) {
        return pageOf([]);
      }
      return pageOf([]);
    });
    await waitFor(() => expect(screen.getByText('demo')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Start analysis' })).toBeInTheDocument();
    expect(screen.getByText(/No analysis yet/)).toBeInTheDocument();
  });

  it('starts an analysis and shows the running status', async () => {
    const calls: string[] = [];
    let started = false;
    renderDetail((url, init) => {
      calls.push(`${init?.method ?? 'GET'} ${url}`);
      if (url.endsWith('/projects/p1')) {
        return new Response(JSON.stringify(PROJECT), { status: 200 });
      }
      if (url.includes('/files')) {
        return pageOf([]);
      }
      if (url.endsWith('/analysis')) {
        started = true;
        return new Response(
          JSON.stringify({ ...COMPLETED, id: 'r9', status: 'RUNNING', findingCount: 0 }),
          { status: 202 },
        );
      }
      return pageOf(
        started
          ? [{ ...COMPLETED, id: 'r9', status: 'RUNNING', findingCount: 0, error: null }]
          : [],
      );
    });
    await waitFor(() => expect(screen.getByText('demo')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Start analysis' }));
    await waitFor(() =>
      expect(calls.some((call) => call.startsWith('POST') && call.endsWith('/analysis'))).toBe(
        true,
      ),
    );
    await waitFor(() => expect(screen.getByText('Analysis in progress…')).toBeInTheDocument());
  });

  it('lists findings with severity and analyzer filters', async () => {
    renderDetail((url) => {
      if (url.endsWith('/projects/p1')) {
        return new Response(JSON.stringify(PROJECT), { status: 200 });
      }
      if (url.includes('/files')) {
        return pageOf([]);
      }
      if (url.includes('/findings')) {
        return pageOf(FINDINGS);
      }
      return pageOf([COMPLETED]);
    });
    await waitFor(() => expect(screen.getByText('LineLength')).toBeInTheDocument());
    expect(screen.getByText('SystemPrintln')).toBeInTheDocument();
    expect(screen.getByText('Main.java:3')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Severity'), { target: { value: 'HIGH' } });
    expect(screen.queryByText('LineLength')).not.toBeInTheDocument();
    expect(screen.getByText('SystemPrintln')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Severity'), { target: { value: 'ALL' } });
    fireEvent.change(screen.getByLabelText('Analyzer'), { target: { value: 'pmd' } });
    expect(screen.queryByText('LineLength')).not.toBeInTheDocument();
    expect(screen.getByText('SystemPrintln')).toBeInTheDocument();
  });

  it('shows a clean empty state for finding-free reviews', async () => {
    renderDetail((url) => {
      if (url.endsWith('/projects/p1')) {
        return new Response(JSON.stringify(PROJECT), { status: 200 });
      }
      if (url.includes('/files')) {
        return pageOf([]);
      }
      if (url.includes('/findings')) {
        return pageOf([]);
      }
      return pageOf([COMPLETED]);
    });
    await waitFor(() =>
      expect(screen.getByText('No findings — this review is clean.')).toBeInTheDocument(),
    );
  });
});
