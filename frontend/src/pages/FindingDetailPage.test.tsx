import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { FindingDetailPage } from './FindingDetailPage';

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

const REVIEW = {
  id: 'r1',
  projectId: 'p1',
  status: 'COMPLETED',
  startedAt: '2026-01-01T00:00:00Z',
  finishedAt: '2026-01-01T00:01:00Z',
  durationMs: 60000,
  findingCount: 1,
  error: null,
  createdAt: '2026-01-01T00:00:00Z',
};

const FINDING = {
  id: 'f1',
  reviewId: 'r1',
  category: 'SECURITY',
  severity: 'HIGH',
  source: 'DETERMINISTIC',
  status: 'OPEN',
  analyzer: 'checkstyle',
  rule: 'LineLength',
  title: 'LineLength',
  description: 'Line too long',
  filePath: 'Main.java',
  lineStart: 42,
  lineEnd: 42,
  evidence: JSON.stringify({ analyzer: 'checkstyle', suggestedFixHint: 'shorten line' }),
  dedupKey: 'k1',
  createdAt: '2026-01-01T00:00:00Z',
};

function renderFinding(handler: (url: string) => Response) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request) => handler(String(url))) as typeof fetch,
  );
  return render(
    <MemoryRouter initialEntries={['/projects/p1/reviews/r1/findings/f1']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <Routes>
          <Route
            path="/projects/:projectId/reviews/:reviewId/findings/:findingId"
            element={<FindingDetailPage />}
          />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 200, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('FindingDetailPage', () => {
  it('shows the investigation context and the existing fix workflow', async () => {
    renderFinding((url) => {
      if (url.endsWith('/projects/p1')) {
        return new Response(JSON.stringify(PROJECT), { status: 200 });
      }
      if (url.endsWith('/reviews/r1')) {
        return new Response(JSON.stringify(REVIEW), { status: 200 });
      }
      if (url.includes('/fix-requests')) {
        return new Response(JSON.stringify([]), { status: 200 });
      }
      if (url.includes('/findings')) {
        return pageOf([FINDING]);
      }
      return new Response(JSON.stringify({ message: 'Not found' }), { status: 404 });
    });
    await waitFor(() =>
      expect(screen.getByText('Investigation context')).toBeInTheDocument(),
    );
    expect(screen.getByRole('heading', { name: 'LineLength', level: 1 })).toBeInTheDocument();
    expect(screen.getByText('shorten line')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Fix this issue' })).toBeInTheDocument();
    expect(screen.getByText('COMPLETED · 1 findings')).toBeInTheDocument();
  });

  it('reports a missing finding honestly', async () => {
    renderFinding((url) => {
      if (url.endsWith('/projects/p1')) {
        return new Response(JSON.stringify(PROJECT), { status: 200 });
      }
      if (url.endsWith('/reviews/r1')) {
        return new Response(JSON.stringify(REVIEW), { status: 200 });
      }
      return pageOf([]);
    });
    await waitFor(() =>
      expect(
        screen.getByText('This finding is not part of the selected review.'),
      ).toBeInTheDocument(),
    );
  });
});
