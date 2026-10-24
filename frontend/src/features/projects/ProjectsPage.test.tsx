import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import { ProjectsPage } from './ProjectsPage';

const PROJECTS = [
  {
    id: 'p1',
    name: 'demo',
    description: null,
    sourceType: 'ZIP_UPLOAD',
    language: 'java',
    status: 'ACTIVE',
    fileCount: 12,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'p2',
    name: 'github-app',
    description: null,
    sourceType: 'GITHUB',
    language: 'typescript',
    status: 'ACTIVE',
    fileCount: 3,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  },
];

const REVIEW = {
  id: 'r1',
  projectId: 'p1',
  status: 'COMPLETED',
  startedAt: '2026-01-01T00:00:00Z',
  finishedAt: '2026-01-01T00:01:00Z',
  durationMs: 60000,
  findingCount: 2,
  error: null,
  createdAt: '2026-01-03T00:00:00Z',
};

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

function renderProjects(handler: (url: string) => Response) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request) => handler(String(url))) as typeof fetch,
  );
  return render(
    <MemoryRouter initialEntries={['/projects']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <ProjectsPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ProjectsPage', () => {
  it('lists projects with source, activity, status and one Review entry point', async () => {
    renderProjects((url) => {
      if (url.includes('/projects?')) return pageOf(PROJECTS);
      if (url.includes('/projects/p1/reviews')) return pageOf([REVIEW]);
      if (url.includes('/projects/p2/reviews')) return pageOf([]);
      return pageOf([]);
    });

    await waitFor(() => expect(within(screen.getByRole('table')).getByText('demo')).toBeInTheDocument());
    const demoRow = screen.getByRole('row', { name: /demo/ });
    expect(within(demoRow).getByText('ZIP_UPLOAD')).toBeInTheDocument();
    expect(within(demoRow).getByText('COMPLETED')).toBeInTheDocument();
    expect(within(demoRow).getByRole('link', { name: 'Review demo' })).toHaveAttribute(
      'href',
      '/review?project=p1',
    );
    expect(within(demoRow).queryByRole('link', { name: 'Fix demo' })).not.toBeInTheDocument();
    expect(within(demoRow).queryByRole('link', { name: 'Verify demo' })).not.toBeInTheDocument();

    const githubRow = screen.getByRole('row', { name: /github-app/ });
    expect(within(githubRow).getByText('ACTIVE')).toBeInTheDocument();
  });

  it('has no intake or generation forms', async () => {
    renderProjects((url) => {
      if (url.includes('/projects?')) return pageOf(PROJECTS);
      return pageOf([]);
    });

    await waitFor(() =>
      expect(within(screen.getByRole('table')).getByText('demo')).toBeInTheDocument(),
    );
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Generate a new project/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Upload an existing codebase/i })).not.toBeInTheDocument();
  });

  it('points the empty state at Review and Generate', async () => {
    renderProjects((url) => {
      if (url.includes('/projects?')) return pageOf([]);
      return pageOf([]);
    });

    await waitFor(() => expect(screen.getByText('No projects yet.')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Add code in Review' })).toHaveAttribute(
      'href',
      '/review',
    );
    expect(screen.getByRole('link', { name: 'Generate a project' })).toHaveAttribute(
      'href',
      '/generate',
    );
    expect(screen.getByRole('link', { name: 'Add code' })).toHaveAttribute('href', '/review');
  });
});
