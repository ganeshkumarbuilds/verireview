import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { ProjectsPage } from './ProjectsPage';

const pageOf = (content: unknown[]) =>
  JSON.stringify({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });

function renderProjects(fetchImpl: typeof fetch) {
  vi.stubGlobal('fetch', fetchImpl);
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
  it('lists the owner projects', async () => {
    renderProjects(async () => new Response(pageOf([{ id: 'p1', name: 'demo', sourceType: 'ZIP_UPLOAD', fileCount: 2 }]), { status: 200 }));
    await waitFor(() => expect(screen.getByText('demo')).toBeInTheDocument());
    expect(screen.getByText(/Your projects \(1\)/)).toBeInTheDocument();
  });

  it('creates a shell from the form', async () => {
    const calls: string[] = [];
    renderProjects(async (url: string | URL | Request, init?: RequestInit) => {
      calls.push(String(url));
      if (init?.method === 'POST') {
        return new Response(JSON.stringify({ id: 'p9', name: 'fresh' }), { status: 201 });
      }
      return new Response(pageOf([]), { status: 200 });
    });
    await waitFor(() => expect(screen.getByText(/No projects yet/)).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('Project name'), { target: { value: 'fresh' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create project' }));
    await waitFor(() =>
      expect(calls.some((url) => url.endsWith('/projects') && !url.includes('import'))).toBe(true),
    );
  });
});
