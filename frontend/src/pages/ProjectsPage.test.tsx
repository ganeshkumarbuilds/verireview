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

  it('starts a generation from the quick form and links to its progress', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderProjects((async (url: string | URL | Request, init?: RequestInit) => {
      const target = String(url);
      calls.push({ url: target, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (target.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify({ id: 'g1', name: 'todo-api' }), { status: 201 });
      }
      return new Response(pageOf([]), { status: 200 });
    }) as typeof fetch);
    await waitFor(() => expect(screen.getByText(/No projects yet/)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Quick project name'), { target: { value: 'todo-api' } });
    fireEvent.change(screen.getByLabelText('Quick backend stack'), { target: { value: 'PYTHON_FASTAPI' } });
    fireEvent.change(screen.getByLabelText('Quick frontend stack'), { target: { value: 'NONE' } });
    fireEvent.change(screen.getByLabelText('Quick database'), { target: { value: 'NONE' } });
    fireEvent.change(screen.getByLabelText('Quick project task'), {
      target: { value: 'A minimal todo REST API with create and list endpoints plus tests.' },
    });
    fireEvent.change(screen.getByLabelText('Quick AI provider'), { target: { value: 'OPENROUTER' } });
    fireEvent.change(screen.getByLabelText('Quick AI model'), { target: { value: 'test/model' } });
    fireEvent.change(screen.getByLabelText('Quick AI API key'), { target: { value: 'sk-live-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() => expect(screen.getByText(/Generation started for/)).toBeInTheDocument());
    const progress = screen.getByRole('link', { name: 'View generation progress' });
    expect(progress).toHaveAttribute('href', '/generate?genId=g1');

    const post = calls.find((call) => call.method === 'POST' && call.url.endsWith('/generations'));
    expect(post?.body).toContain('todo-api');
    expect(post?.body).toContain('sk-live-key');
    expect(calls.every((call) => !call.url.includes('sk-live-key'))).toBe(true);
    // Single-use secrets are cleared from the form after submit.
    expect(screen.queryByDisplayValue('sk-live-key')).not.toBeInTheDocument();
  });

  it('starts a NONE template generation without an API key', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderProjects((async (url: string | URL | Request, init?: RequestInit) => {
      const target = String(url);
      calls.push({ url: target, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (target.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify({ id: 'g2', name: 'plain-java' }), { status: 201 });
      }
      return new Response(pageOf([]), { status: 200 });
    }) as typeof fetch);
    await waitFor(() => expect(screen.getByText(/No projects yet/)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Quick project name'), { target: { value: 'plain-java' } });
    fireEvent.change(screen.getByLabelText('Quick backend stack'), { target: { value: 'JAVA_SPRING_BOOT' } });
    fireEvent.change(screen.getByLabelText('Quick frontend stack'), { target: { value: 'NONE' } });
    fireEvent.change(screen.getByLabelText('Quick database'), { target: { value: 'NONE' } });
    fireEvent.change(screen.getByLabelText('Quick project task'), {
      target: { value: 'A minimal Java starter with buildable checks and no AI key.' },
    });
    fireEvent.change(screen.getByLabelText('Quick AI provider'), { target: { value: 'NONE' } });
    expect(screen.getByLabelText('Quick AI API key')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() => expect(screen.getByText(/Generation started for/)).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'View generation progress' })).toHaveAttribute(
      'href',
      '/generate?genId=g2',
    );
    const post = calls.find((call) => call.method === 'POST' && call.url.endsWith('/generations'));
    expect(post?.body).toContain('"provider":"NONE"');
    expect(post?.body).not.toContain('apiKey');
  });
});
