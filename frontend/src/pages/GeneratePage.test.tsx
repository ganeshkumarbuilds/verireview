import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { GeneratePage } from './GeneratePage';

const JAVA_DESC =
  'A task tracker with a React + TypeScript frontend, Spring Boot backend and PostgreSQL database.';
const PY_DESC = 'A FastAPI service with PostgreSQL and no frontend.';
const NODB_DESC = 'A simple API with no database, in-memory only.';
const DEFAULT_DESC = 'A simple task tracker for teams to plan their work.';

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

function baseGeneration(overrides: Record<string, unknown> = {}) {
  return {
    id: 'g1',
    name: 'task-tracker',
    requirement: JAVA_DESC,
    description: null,
    backend: 'JAVA_SPRING_BOOT',
    frontend: 'REACT_TYPESCRIPT',
    database: 'POSTGRESQL',
    databaseConfig: {
      host: 'localhost',
      port: 5432,
      name: 'task_tracker',
      username: 'postgres',
      sslMode: null,
      passwordConfigured: false,
    },
    aiConfig: { provider: 'OPENROUTER', model: 'openai/gpt-4o-mini', baseUrl: null, keyConfigured: false },
    status: 'QUEUED',
    error: null,
    projectId: null,
    iteration: 0,
    maxIterations: 5,
    revisionNumber: 0,
    revisionCount: 0,
    artifact: null,
    plan: null,
    workflowStats: null,
    verification: null,
    review: null,
    agentWorkflow: [],
    downloadReady: false,
    previewReady: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function renderGenerate(
  handler: (url: string, init?: RequestInit) => Response | Promise<Response>,
) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request, init?: RequestInit) =>
      handler(String(url), init)) as typeof fetch,
  );
  render(
    <MemoryRouter initialEntries={['/generate']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <GeneratePage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function fillBasic(description: string, title = 'task-tracker') {
  fireEvent.change(screen.getByLabelText('Project title'), { target: { value: title } });
  fireEvent.change(screen.getByLabelText('Project description'), {
    target: { value: description },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('GeneratePage single-form wizard', () => {
  it('validates required fields before generating', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderGenerate((url, init) => {
      calls.push({ url, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (url.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify(baseGeneration({ status: 'QUEUED' })), { status: 201 });
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(
          JSON.stringify(baseGeneration({ status: 'COMPLETED', downloadReady: false })),
          { status: 200 },
        );
      }
      return pageOf([]);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));
    expect(screen.getByText('Please enter a project title.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Project title'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));
    expect(
      screen.getByText('Please describe your idea in at least 20 characters.'),
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Project description'), {
      target: { value: JAVA_DESC },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() =>
      expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/generations'))).toBe(
        true,
      ),
    );
    await waitFor(() => expect(screen.getByText('COMPLETED')).toBeInTheDocument());
  });

  it('detects the stack from the description, defaulting sensibly', async () => {
    renderGenerate(() => pageOf([]));
    fillBasic(DEFAULT_DESC);

    await waitFor(() => expect(screen.getByLabelText('Detected stack')).toBeInTheDocument());
    expect(screen.getByText('Java Spring Boot')).toBeInTheDocument();
    expect(screen.getByText('React + TypeScript')).toBeInTheDocument();
    expect(screen.getByText('No database')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Project description'), { target: { value: PY_DESC } });
    await waitFor(() => expect(screen.getByText('Python FastAPI')).toBeInTheDocument());
    expect(screen.getByText('No frontend')).toBeInTheDocument();
    expect(screen.getByText('PostgreSQL')).toBeInTheDocument();
  });

  it('shows the database password field only when a database is detected', async () => {
    renderGenerate(() => pageOf([]));
    fillBasic(NODB_DESC);

    await waitFor(() => expect(screen.getByText('No database')).toBeInTheDocument());
    expect(screen.queryByLabelText('Database password')).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Project description'), {
      target: { value: 'A REST API with PostgreSQL database for persistence.' },
    });
    await waitFor(() => expect(screen.getByText('PostgreSQL')).toBeInTheDocument());
    expect(screen.getByLabelText('Database password')).toHaveAttribute('type', 'password');
  });

  it('requires an AI API key for non-template stacks', async () => {
    renderGenerate(() => pageOf([]));
    fillBasic(PY_DESC);
    await waitFor(() => expect(screen.getByText('Python FastAPI')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));
    expect(
      screen.getByText(
        'An AI API key is required for this stack. Skipping the key only works for Java Spring Boot projects.',
      ),
    ).toBeInTheDocument();
  });

  it('supports the template fallback without a key for Spring Boot', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderGenerate((url, init) => {
      calls.push({ url, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (url.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify(baseGeneration({ status: 'QUEUED' })), { status: 201 });
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(baseGeneration({ status: 'COMPLETED' })), { status: 200 });
      }
      return pageOf([]);
    });

    fillBasic(JAVA_DESC);
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() =>
      expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/generations'))).toBe(
        true,
      ),
    );
    const post = calls.find((call) => call.method === 'POST');
    expect(post?.body).toContain('"provider":"NONE"');
    expect(post?.body).not.toContain('apiKey');
    expect(post?.body).not.toContain('sk-live-key');
  });

  it('masks secrets in inputs and keeps them out of URLs and storage', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderGenerate((url, init) => {
      calls.push({ url, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (url.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify(baseGeneration({ status: 'QUEUED' })), { status: 201 });
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(baseGeneration({ status: 'COMPLETED' })), { status: 200 });
      }
      return pageOf([]);
    });

    fillBasic(PY_DESC);
    await waitFor(() => expect(screen.getByText('PostgreSQL')).toBeInTheDocument());

    expect(screen.getByLabelText('Database password')).toHaveAttribute('type', 'password');
    expect(screen.getByLabelText('AI API key')).toHaveAttribute('type', 'password');

    fireEvent.change(screen.getByLabelText('Database password'), { target: { value: 'db-secret-pw' } });
    fireEvent.change(screen.getByLabelText('AI API key'), { target: { value: 'sk-live-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() =>
      expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/generations'))).toBe(
        true,
      ),
    );

    const post = calls.find((call) => call.method === 'POST');
    expect(post?.body).toContain('sk-live-key');
    expect(calls.every((call) => !call.url.includes('sk-live-key'))).toBe(true);
    expect(calls.every((call) => !call.url.includes('db-secret-pw'))).toBe(true);
    expect(window.location.href).not.toContain('sk-live-key');
    expect(window.localStorage.length).toBe(0);
  });

  it('shows backend failures without exposing secrets', async () => {
    renderGenerate((url, init) => {
      if (url.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify(baseGeneration({ status: 'QUEUED' })), { status: 201 });
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(
          JSON.stringify(
            baseGeneration({ status: 'FAILED', error: 'Planning failed: AI down' }),
          ),
          { status: 200 },
        );
      }
      return pageOf([]);
    });

    fillBasic(JAVA_DESC);
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() => expect(screen.getByText('Planning failed: AI down')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Back to form' })).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('sk-live-key');
  });

  it('runs generation to a terminal state from real backend status', async () => {
    renderGenerate((url, init) => {
      if (url.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify(baseGeneration({ status: 'QUEUED' })), { status: 201 });
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(
          JSON.stringify(
            baseGeneration({
              status: 'COMPLETED',
              downloadReady: true,
              workflowStats: {
                totalFindings: 0,
                openFindings: 0,
                fixedFindings: 0,
                bugCount: 0,
                issueCount: 0,
                errorCount: 0,
              },
            }),
          ),
          { status: 200 },
        );
      }
      return pageOf([]);
    });

    fillBasic(JAVA_DESC);
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() => expect(screen.getByText('COMPLETED')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Download ZIP' })).toBeEnabled();
    expect(screen.queryByText('Download locked')).not.toBeInTheDocument();
  });
});

describe('GeneratePage draft foundation', () => {
  const DRAFT = baseGeneration({
    status: 'DRAFT',
    requirement: PY_DESC,
    database: 'POSTGRESQL',
  });

  it('saves a draft without dispatching and never sends secrets', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderGenerate((url, init) => {
      calls.push({ url, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (url.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify(DRAFT), { status: 201 });
      }
      return pageOf([]);
    });

    fillBasic(PY_DESC);
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => expect(screen.getByText('Draft saved')).toBeInTheDocument());

    const post = calls.find((call) => call.method === 'POST');
    expect(post?.body).toContain('"draft":true');
    expect(post?.body).not.toContain('apiKey');
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(window.localStorage.length).toBe(0);
  });

  it('edits the draft task and reports validation errors honestly', async () => {
    renderGenerate((url, init) => {
      const method = init?.method ?? 'GET';
      if (url.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify(DRAFT), { status: 201 });
      }
      if (url.endsWith('/generations/g1') && method === 'PATCH') {
        const body = JSON.parse(String(init?.body ?? '{}'));
        return new Response(JSON.stringify({ ...DRAFT, requirement: body.requirement }), {
          status: 200,
        });
      }
      return pageOf([]);
    });

    fillBasic(PY_DESC);
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(screen.getByText('Draft saved')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Draft task'), { target: { value: 'tiny' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(
      screen.getByText('Please describe the idea in at least 20 characters.'),
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Draft task'), {
      target: { value: 'An updated task describing a blog API with comments support.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(screen.getByText('Saved.')).toBeInTheDocument());
  });

  it('starts a draft and follows real backend status to completion', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderGenerate((url, init) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: String(init?.body ?? '') });
      if (url.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify(DRAFT), { status: 201 });
      }
      if (url.endsWith('/generations/g1/start') && method === 'POST') {
        return new Response(JSON.stringify({ ...DRAFT, status: 'READY' }), { status: 200 });
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(
          JSON.stringify({ ...DRAFT, status: 'COMPLETED', downloadReady: true }),
          { status: 200 },
        );
      }
      return pageOf([]);
    });

    fillBasic(PY_DESC);
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(screen.getByText('Draft saved')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Start AI API key'), {
      target: { value: 'sk-live-key' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() => expect(screen.getByText('COMPLETED')).toBeInTheDocument());
    const start = calls.find((call) => call.url.endsWith('/start'));
    expect(start?.method).toBe('POST');
    expect(start?.body).toContain('sk-live-key');
    expect(calls.every((call) => !call.url.includes('sk-live-key'))).toBe(true);
    expect(screen.queryByDisplayValue('sk-live-key')).not.toBeInTheDocument();
  });

  it('requests fixes for all open findings from REVIEWED without inventing counts', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    const reviewed = baseGeneration({
      status: 'REVIEWED',
      requirement: JAVA_DESC,
      review: { status: 'COMPLETED', findingCount: 2, error: null },
      workflowStats: {
        totalFindings: 2,
        openFindings: 2,
        fixedFindings: 0,
        bugCount: 1,
        issueCount: 1,
        errorCount: 0,
      },
    });
    renderGenerate((url, init) => {
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: String(init?.body ?? '') });
      if (url.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify(baseGeneration({ status: 'QUEUED' })), { status: 201 });
      }
      if (url.endsWith('/generations/g1/fix-requests') && method === 'POST') {
        return new Response(
          JSON.stringify({ reviewId: 'r1', created: ['f1'], skippedOpen: 1 }),
          { status: 202 },
        );
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(reviewed), { status: 200 });
      }
      return pageOf([]);
    });

    fillBasic(JAVA_DESC);
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() =>
      expect(screen.getByText('2 issues must be fixed before you can download.')).toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: 'Download ZIP' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Approve & fix issues' }));
    await waitFor(() => expect(screen.getByText(/1 fix request\(s\) created/)).toBeInTheDocument());
    expect(screen.getByText(/1 already open — skipped/)).toBeInTheDocument();
    const fixCall = calls.find((call) => call.url.endsWith('/fix-requests'));
    expect(fixCall?.method).toBe('POST');
  });
});
