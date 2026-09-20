import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { HistoryPage } from './HistoryPage';

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

function renderHistory(handler: (url: string) => Response) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request) => handler(String(url))) as typeof fetch,
  );
  return render(
    <MemoryRouter initialEntries={['/history']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <HistoryPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('HistoryPage', () => {
  it('builds a timeline from real review runs', async () => {
    renderHistory((url) => {
      if (url.includes('/projects?')) {
        return pageOf([{ id: 'p1', name: 'demo', fileCount: 2 }]);
      }
      if (url.includes('/reviews')) {
        return pageOf([
          {
            id: 'r1',
            projectId: 'p1',
            status: 'COMPLETED',
            findingCount: 2,
            error: null,
            createdAt: '2026-01-02T00:00:00Z',
          },
          {
            id: 'r0',
            projectId: 'p1',
            status: 'FAILED',
            findingCount: 0,
            error: 'Sandbox image missing.',
            createdAt: '2026-01-01T00:00:00Z',
          },
        ]);
      }
      return pageOf([]);
    });
    await waitFor(() =>
      expect(screen.getByText('Analysis completed with 2 findings.')).toBeInTheDocument(),
    );
    expect(screen.getByText('Sandbox image missing.')).toBeInTheDocument();
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('filters the timeline by status', async () => {
    renderHistory((url) => {
      if (url.includes('/projects?')) {
        return pageOf([{ id: 'p1', name: 'demo', fileCount: 2 }]);
      }
      if (url.includes('/reviews')) {
        return pageOf([
          {
            id: 'r1',
            projectId: 'p1',
            status: 'COMPLETED',
            findingCount: 2,
            error: null,
            createdAt: '2026-01-02T00:00:00Z',
          },
        ]);
      }
      return pageOf([]);
    });
    await waitFor(() =>
      expect(screen.getByText('Analysis completed with 2 findings.')).toBeInTheDocument(),
    );
    fireEvent.change(screen.getByLabelText('Filter history by status'), {
      target: { value: 'FAILED' },
    });
    expect(screen.getByText('No matching activity.')).toBeInTheDocument();
  });

  it('shows an honest empty state instead of fabricated records', async () => {
    renderHistory(() => pageOf([]));
    await waitFor(() =>
      expect(screen.getByText('History will appear here.')).toBeInTheDocument(),
    );
    expect(
      screen.getByText(/Fix, patch, execution, and verification records are tracked per finding/),
    ).toBeInTheDocument();
  });
});
