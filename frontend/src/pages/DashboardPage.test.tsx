import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { DashboardPage } from './DashboardPage';

function pageOf(content: unknown[], total: number) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 5, totalElements: total, totalPages: 1 }),
    { status: 200 },
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DashboardPage', () => {
  it('shows real project counts and latest review states', async () => {
    vi.stubGlobal(
      'fetch',
      (async (url: string | URL | Request) => {
        const target = String(url);
        if (target.endsWith('/projects?size=5')) {
          return pageOf(
            [
              { id: 'p1', name: 'demo', fileCount: 3 },
              { id: 'p2', name: 'empty', fileCount: 0 },
            ],
            2,
          );
        }
        if (target.includes('/projects/p1/reviews')) {
          return pageOf([{ id: 'r1', status: 'COMPLETED', findingCount: 4 }], 1);
        }
        return pageOf([], 0);
      }) as typeof fetch,
    );
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
          <DashboardPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText('demo')).toBeInTheDocument());
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    expect(screen.getByText('NEVER ANALYZED')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('explains the empty workspace', async () => {
    vi.stubGlobal(
      'fetch',
      (async () => pageOf([], 0)) as typeof fetch,
    );
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
          <DashboardPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByText(/No projects yet/)).toBeInTheDocument());
  });
});
