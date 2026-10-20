import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import { VerifyPage } from './VerifyPage';

const PROJECTS = [
  { id: 'p1', name: 'demo', sourceType: 'ZIP_UPLOAD', language: 'java', fileCount: 2 },
  { id: 'p2', name: 'empty', sourceType: 'GITHUB', language: 'typescript', fileCount: 1 },
];

const EXECUTION = {
  id: 'e1',
  projectId: 'p1',
  patchId: 'patch-1',
  status: 'SUCCESS',
  exitCode: 0,
  stdout: 'BUILD SUCCESS',
  stderr: null,
  durationMs: 4200,
  buildStatus: 'SUCCESS',
  createdAt: '2026-01-01T00:02:00Z',
  updatedAt: '2026-01-01T00:02:04Z',
};

const VERIFICATION = {
  id: 'v1',
  patchId: 'patch-1',
  executionRunId: 'e1',
  buildStatus: 'SUCCESS',
  testsTotal: 12,
  testsPassed: 12,
  testsFailed: 0,
  testsSkipped: 0,
  verdict: 'VERIFIED',
  logRef: 'Tests run: 12, Failures: 0',
  durationMs: 5000,
  createdAt: '2026-01-01T00:03:00Z',
  updatedAt: '2026-01-01T00:03:05Z',
  sandboxSkipped: false,
};

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

function renderVerify(handler: (url: string, init?: RequestInit) => Response) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request, init?: RequestInit) =>
      handler(String(url), init)) as typeof fetch,
  );
  return render(
    <MemoryRouter initialEntries={['/verify?project=p1']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <VerifyPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('VerifyPage', () => {
  it('runs verification on the latest sandbox execution and shows the persisted verdict', async () => {
    let verified = false;
    const calls: string[] = [];
    renderVerify((url, init) => {
      const target = url;
      const method = init?.method ?? 'GET';
      if (method === 'POST') calls.push(target);
      if (target.includes('/projects?')) return pageOf(PROJECTS);
      if (target.endsWith('/projects/p1/executions')) {
        return new Response(JSON.stringify([EXECUTION]), { status: 200 });
      }
      if (target.endsWith('/executions/e1/verification')) {
        if (method === 'POST') {
          verified = true;
          return new Response(JSON.stringify(VERIFICATION), { status: 201 });
        }
        return verified
          ? new Response(JSON.stringify(VERIFICATION), { status: 200 })
          : new Response(JSON.stringify({ message: 'Not found' }), { status: 404 });
      }
      return pageOf([]);
    });

    await waitFor(() => expect(screen.getByText('build: SUCCESS')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText('Not verified yet.')).toBeInTheDocument());

    fireEvent.click(screen.getAllByRole('button', { name: 'Run verification' })[0]);

    await waitFor(() => expect(screen.getByText('VERIFIED')).toBeInTheDocument());
    expect(screen.getByText(/tests 12\/12 passed/)).toBeInTheDocument();
    expect(screen.getByText('Tests run: 12, Failures: 0')).toBeInTheDocument();
    expect(calls).toContain('http://localhost:8080/api/v1/executions/e1/verification');
    expect(screen.getByRole('link', { name: /Back to Dashboard/i })).toHaveAttribute(
      'href',
      '/dashboard',
    );
  });

  it('points to Fix when the project has no sandbox execution', async () => {
    renderVerify((url) => {
      if (url.includes('/projects?')) return pageOf(PROJECTS);
      if (url.endsWith('/projects/p1/executions')) {
        return new Response(JSON.stringify([]), { status: 200 });
      }
      return pageOf([]);
    });

    await waitFor(() => expect(screen.getByText('No sandbox runs yet.')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Go to Fix' })).toHaveAttribute(
      'href',
      '/fix?project=p1',
    );
    expect(screen.getByRole('button', { name: 'Run verification' })).toBeDisabled();
  });

  it('can re-run build and test for the latest applied patch', async () => {
    const calls: string[] = [];
    renderVerify((url, init) => {
      const target = url;
      const method = init?.method ?? 'GET';
      if (method === 'POST') calls.push(target);
      if (target.includes('/projects?')) return pageOf(PROJECTS);
      if (target.endsWith('/projects/p1/executions')) {
        return new Response(JSON.stringify([EXECUTION]), { status: 200 });
      }
      if (target.includes('/projects/p1/execute')) {
        return new Response(JSON.stringify({ ...EXECUTION, id: 'e2', status: 'RUNNING' }), {
          status: 202,
        });
      }
      if (target.endsWith('/executions/e1/verification')) {
        return new Response(JSON.stringify({ message: 'Not found' }), { status: 404 });
      }
      return pageOf([]);
    });

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Re-run build & test' })).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Re-run build & test' }));

    await waitFor(() =>
      expect(calls.some((call) => call.includes('/projects/p1/execute?patchId=patch-1'))).toBe(true),
    );
  });
});
