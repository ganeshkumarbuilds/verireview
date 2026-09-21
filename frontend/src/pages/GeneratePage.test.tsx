import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { GeneratePage } from './GeneratePage';

function pageOf(content: unknown[]) {
  return new Response(
    JSON.stringify({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 }),
    { status: 200 },
  );
}

function renderGenerate(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request, init?: RequestInit) => handler(String(url), init)) as typeof fetch,
  );
  render(
    <MemoryRouter initialEntries={['/generate']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <GeneratePage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function fillRequirement() {
  fireEvent.change(screen.getByLabelText('Project name'), { target: { value: 'todo-api' } });
  fireEvent.change(screen.getByLabelText('Requirement'), {
    target: { value: 'A minimal todo REST API with create and list endpoints plus tests.' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
}

function chooseStack(database: 'POSTGRESQL' | 'NONE') {
  fireEvent.click(screen.getByLabelText(/Java Spring Boot/));
  fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="frontend"]' }));
  fireEvent.click(
    screen.getByLabelText(database === 'POSTGRESQL' ? 'PostgreSQL' : 'None', {
      selector: 'input[name="database"]',
    }),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
}

function fillDatabase() {
  fireEvent.change(screen.getByLabelText('Database host'), { target: { value: 'localhost' } });
  fireEvent.change(screen.getByLabelText('Database port'), { target: { value: '5432' } });
  fireEvent.change(screen.getByLabelText('Database name'), { target: { value: 'todos' } });
  fireEvent.change(screen.getByLabelText('Database username'), { target: { value: 'app' } });
  fireEvent.change(screen.getByLabelText('Database password'), { target: { value: 'db-secret-pw' } });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
}

function fillAi() {
  fireEvent.click(screen.getByLabelText(/OpenRouter/));
  fireEvent.change(screen.getByLabelText('AI API key'), { target: { value: 'sk-live-key' } });
  fireEvent.change(screen.getByLabelText('AI model'), { target: { value: 'test/model' } });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('GeneratePage wizard', () => {
  it('validates the requirement step before continuing', async () => {
    renderGenerate(() => pageOf([]));
    expect(screen.getByRole('heading', { name: 'Step 1 — Requirement' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Please enter a project name.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Project name'), { target: { value: 'x' } });
    fireEvent.change(screen.getByLabelText('Requirement'), { target: { value: 'too short' } });
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(
      screen.getByText('Please describe the requirement in at least 20 characters.'),
    ).toBeInTheDocument();
  });

  it('requires explicit stack choices with no preselection', async () => {
    renderGenerate(() => pageOf([]));
    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());

    const checked = document.querySelectorAll('input[type="radio"]:checked');
    expect(checked.length).toBe(0);

    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Please choose a backend stack.')).toBeInTheDocument();
  });

  it('shows database configuration only when a database is selected', async () => {
    renderGenerate(() => pageOf([]));
    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    chooseStack('NONE');
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    expect(screen.queryByLabelText('Database password')).not.toBeInTheDocument();
  });

  it('masks secrets in review and keeps them out of URLs and storage', async () => {
    renderGenerate(() => pageOf([]));
    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    chooseStack('POSTGRESQL');
    await waitFor(() => expect(screen.getByLabelText('Database host')).toBeInTheDocument());
    expect(screen.getByLabelText('Database password')).toHaveAttribute('type', 'password');
    fillDatabase();
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    expect(screen.getByLabelText('AI API key')).toHaveAttribute('type', 'password');
    fillAi();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());

    // Masked in review, raw values never rendered.
    expect(screen.queryByText('db-secret-pw')).not.toBeInTheDocument();
    expect(screen.queryByText('sk-live-key')).not.toBeInTheDocument();
    expect(window.location.href).not.toContain('db-secret-pw');
    expect(window.location.href).not.toContain('sk-live-key');
    expect(window.localStorage.length).toBe(0);
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const value = window.localStorage.getItem(window.localStorage.key(i) ?? '');
      expect(value).not.toContain('db-secret-pw');
      expect(value).not.toContain('sk-live-key');
    }
  });

  it('disables Generate until the configuration is valid', async () => {
    renderGenerate(() => pageOf([]));
    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    chooseStack('NONE');
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    // AI step not yet filled: continuing is blocked.
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Please choose an AI provider.')).toBeInTheDocument();
  });

  it('runs generation to completion and links the project', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderGenerate((url, init) => {
      const target = String(url);
      calls.push({ url: target, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (target.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(
          JSON.stringify({
            id: 'g1',
            name: 'todo-api',
            requirement: 'req',
            description: null,
            backend: 'PYTHON_FASTAPI',
            frontend: 'NONE',
            database: 'NONE',
            databaseConfig: null,
            aiConfig: { provider: 'OPENROUTER', model: 'test/model', baseUrl: null, keyConfigured: true },
            status: 'QUEUED',
            error: null,
            projectId: null,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
          { status: 201 },
        );
      }
      if (target.endsWith('/generations/g1')) {
        return new Response(
          JSON.stringify({
            id: 'g1',
            name: 'todo-api',
            requirement: 'req',
            description: null,
            backend: 'PYTHON_FASTAPI',
            frontend: 'NONE',
            database: 'NONE',
            databaseConfig: null,
            aiConfig: { provider: 'OPENROUTER', model: 'test/model', baseUrl: null, keyConfigured: true },
            status: 'COMPLETED',
            error: null,
            projectId: 'p9',
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
          { status: 200 },
        );
      }
      return pageOf([]);
    });

    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText(/Python FastAPI/));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="frontend"]' }));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="database"]' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    fillAi();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());

    const generate = screen.getByRole('button', { name: 'Start Generation' });
    expect(generate).toBeEnabled();
    fireEvent.click(generate);

    await waitFor(() => expect(screen.getAllByText('COMPLETED').length).toBeGreaterThan(0));

    const post = calls.find((call) => call.method === 'POST');
    expect(post?.body).toContain('sk-live-key');
    // Secrets travel in the request body only — never in the URL.
    expect(calls.every((call) => !call.url.includes('sk-live-key'))).toBe(true);
  });

  it('requests fixes for all open findings from REVIEWED without inventing counts', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    const base = {
      id: 'g1',
      name: 'todo-api',
      requirement: 'req',
      description: null,
      backend: 'PYTHON_FASTAPI',
      frontend: 'NONE',
      database: 'NONE',
      databaseConfig: null,
      aiConfig: { provider: 'OPENROUTER', model: 'test/model', baseUrl: null, keyConfigured: true },
      error: null,
      projectId: null,
      iteration: 1,
      maxIterations: 5,
      revisionNumber: 1,
      revisionCount: 1,
      artifact: null,
      plan: null,
      verification: {
        verdict: 'VERIFIED',
        buildStatus: 'SUCCESS',
        testsTotal: 1,
        testsPassed: 1,
        testsFailed: 0,
        testsSkipped: 0,
        durationMs: 12,
        logRef: null,
      },
      review: { status: 'COMPLETED', findingCount: 2, error: null },
      agentWorkflow: [],
      downloadReady: false,
      previewReady: false,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
    const reviewed = {
      ...base,
      status: 'REVIEWED',
      workflowStats: {
        totalFindings: 2,
        openFindings: 2,
        fixedFindings: 0,
        bugCount: 1,
        issueCount: 1,
        errorCount: 0,
      },
    };
    renderGenerate((url, init) => {
      const target = String(url);
      const method = init?.method ?? 'GET';
      calls.push({ url: target, method, body: String(init?.body ?? '') });
      if (target.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify({ ...base, status: 'QUEUED', workflowStats: null }), { status: 201 });
      }
      if (target.endsWith('/generations/g1/fix-requests') && method === 'POST') {
        return new Response(
          JSON.stringify({ reviewId: 'r1', created: ['f1'], skippedOpen: 1 }),
          { status: 202 },
        );
      }
      if (target.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(reviewed), { status: 200 });
      }
      return pageOf([]);
    });

    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText(/Python FastAPI/));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="frontend"]' }));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="database"]' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    fillAi();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Start Generation' }));

    await waitFor(() => expect(screen.getByText('Issues found 2 · Issues fixed 0', { exact: false })).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Download ZIP' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Fix issues & optimize' }));
    await waitFor(() => expect(screen.getByText(/1 fix request\(s\) created/)).toBeInTheDocument());
    expect(screen.getByText(/1 already open — skipped/)).toBeInTheDocument();
    const fixCall = calls.find((call) => call.url.endsWith('/fix-requests'));
    expect(fixCall?.method).toBe('POST');
  });

  it('supports the NONE provider without an API key', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    const queued = {
      id: 'g1',
      name: 'todo-api',
      requirement: 'req',
      description: null,
      backend: 'JAVA_SPRING_BOOT',
      frontend: 'NONE',
      database: 'NONE',
      databaseConfig: null,
      aiConfig: { provider: 'NONE', model: 'template', baseUrl: null, keyConfigured: false },
      status: 'QUEUED',
      error: null,
      projectId: null,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
    renderGenerate((url, init) => {
      const target = String(url);
      const method = init?.method ?? 'GET';
      calls.push({ url: target, method, body: String(init?.body ?? '') });
      if (target.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify(queued), { status: 201 });
      }
      if (target.endsWith('/generations/g1')) {
        return new Response(
          JSON.stringify({ ...queued, status: 'FAILED', error: 'done' }),
          { status: 200 },
        );
      }
      return pageOf([]);
    });

    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText(/Java Spring Boot/));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="frontend"]' }));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="database"]' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText(/None — template starter/));
    expect(screen.getByLabelText('AI API key')).toBeDisabled();
    expect(screen.getByLabelText('AI model')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());
    expect(screen.getByText('None — template starter', { exact: false })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Start Generation' }));
    await waitFor(() =>
      expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/generations'))).toBe(true),
    );
    const post = calls.find((call) => call.method === 'POST' && call.url.endsWith('/generations'));
    expect(post?.body).toContain('"provider":"NONE"');
    expect(post?.body).not.toContain('apiKey');
    expect(post?.body).not.toContain('sk-live-key');
  });

  it('shows backend failures without exposing secrets', async () => {
    renderGenerate((url, init) => {
      const target = String(url);
      if (target.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(
          JSON.stringify({
            id: 'g1',
            name: 'todo-api',
            requirement: 'req',
            description: null,
            backend: 'PYTHON_FASTAPI',
            frontend: 'NONE',
            database: 'NONE',
            databaseConfig: null,
            aiConfig: { provider: 'OPENROUTER', model: 'test/model', baseUrl: null, keyConfigured: true },
            status: 'QUEUED',
            error: null,
            projectId: null,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
          { status: 201 },
        );
      }
      if (target.endsWith('/generations/g1')) {
        return new Response(
          JSON.stringify({
            id: 'g1',
            name: 'todo-api',
            requirement: 'req',
            description: null,
            backend: 'PYTHON_FASTAPI',
            frontend: 'NONE',
            database: 'NONE',
            databaseConfig: null,
            aiConfig: { provider: 'OPENROUTER', model: 'test/model', baseUrl: null, keyConfigured: true },
            status: 'FAILED',
            error: 'Planning failed: AI down',
            projectId: null,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
          { status: 200 },
        );
      }
      return pageOf([]);
    });

    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText(/Python FastAPI/));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="frontend"]' }));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="database"]' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    fillAi();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Start Generation' }));

    await waitFor(() => expect(screen.getByText('Planning failed: AI down')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Back to review' })).toBeInTheDocument();
  });
});

describe('GeneratePage draft foundation', () => {
  const DRAFT = {
    id: 'g1',
    name: 'todo-api',
    requirement: 'A minimal todo REST API with create and list endpoints plus tests.',
    description: null,
    backend: 'PYTHON_FASTAPI',
    frontend: 'NONE',
    database: 'NONE',
    databaseConfig: null,
    aiConfig: { provider: 'OPENROUTER', model: 'test/model', baseUrl: null, keyConfigured: false },
    status: 'DRAFT',
    error: null,
    projectId: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  async function reachReview() {
    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText(/Python FastAPI/));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="frontend"]' }));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="database"]' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    fillAi();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());
  }

  it('saves a draft without dispatching and shows the pipeline preview', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    renderGenerate((url, init) => {
      const target = String(url);
      calls.push({ url: target, method: init?.method ?? 'GET', body: String(init?.body ?? '') });
      if (target.endsWith('/generations') && (init?.method ?? 'GET') === 'POST') {
        return new Response(JSON.stringify(DRAFT), { status: 201 });
      }
      return pageOf([]);
    });
    await reachReview();

    fireEvent.click(screen.getByRole('button', { name: 'Save Draft' }));
    await waitFor(() => expect(screen.getByText('Draft saved')).toBeInTheDocument());

    const post = calls.find((call) => call.method === 'POST');
    expect(post?.body).toContain('"draft":true');
    // Drafts persist configuration only — secrets omitted from the request.
    expect(post?.body).not.toContain('sk-live-key');
    expect(screen.getByText('ready for the next phase', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('Task / requirements')).toBeInTheDocument();
    expect(screen.getByText('Planner Agent')).toBeInTheDocument();
    expect(screen.getByText('Coding Agent')).toBeInTheDocument();
    expect(screen.queryByText('sk-live-key')).not.toBeInTheDocument();
    expect(window.localStorage.length).toBe(0);
  });

  it('edits the draft task and reports errors honestly', async () => {
    renderGenerate((url, init) => {
      const target = String(url);
      const method = init?.method ?? 'GET';
      if (target.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify(DRAFT), { status: 201 });
      }
      if (target.endsWith('/generations/g1') && method === 'PATCH') {
        const body = JSON.parse(String(init?.body ?? '{}'));
        if ((body.requirement ?? '').trim().length < 20) {
          return new Response(JSON.stringify({ message: 'Invalid requirement' }), { status: 400 });
        }
        return new Response(
          JSON.stringify({ ...DRAFT, requirement: body.requirement }),
          { status: 200 },
        );
      }
      return pageOf([]);
    });
    await reachReview();
    fireEvent.click(screen.getByRole('button', { name: 'Save Draft' }));
    await waitFor(() => expect(screen.getByText('Draft saved')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Draft task'), {
      target: { value: 'Updated task: a blog API with comments support.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save task' }));
    await waitFor(() => expect(screen.getByText('Task saved.')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Draft task'), { target: { value: 'tiny' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save task' }));
    await waitFor(() =>
      expect(
        screen.getByText('Please describe the requirement in at least 20 characters.'),
      ).toBeInTheDocument(),
    );
  });

  it('starts a draft through READY without exposing secrets', async () => {
    const calls: { url: string; method: string; body: string }[] = [];
    const ready = { ...DRAFT, status: 'READY', aiConfig: { ...DRAFT.aiConfig, keyConfigured: true } };
    const done = { ...ready, status: 'COMPLETED', projectId: 'p9' };
    renderGenerate((url, init) => {
      const target = String(url);
      const method = init?.method ?? 'GET';
      calls.push({ url: target, method, body: String(init?.body ?? '') });
      if (target.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify(DRAFT), { status: 201 });
      }
      if (target.endsWith('/generations/g1/start') && method === 'POST') {
        return new Response(JSON.stringify(ready), { status: 200 });
      }
      if (target.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(done), { status: 200 });
      }
      return pageOf([]);
    });
    await reachReview();
    fireEvent.click(screen.getByRole('button', { name: 'Save Draft' }));
    await waitFor(() => expect(screen.getByText('Draft saved')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Start AI API key'), { target: { value: 'sk-live-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Start Generation' }));
    await waitFor(() => expect(screen.getAllByText('COMPLETED').length).toBeGreaterThan(0));

    const start = calls.find((call) => call.url.endsWith('/start'));
    expect(start?.body).toContain('sk-live-key');
    expect(calls.every((call) => !call.url.includes('sk-live-key'))).toBe(true);
    // Key input is cleared after use; nothing secret stays rendered.
    expect(screen.queryByDisplayValue('sk-live-key')).not.toBeInTheDocument();
    expect(screen.queryByText('sk-live-key')).not.toBeInTheDocument();
  });

  it('requires secrets to start and surfaces start failures', async () => {
    renderGenerate((url, init) => {
      const target = String(url);
      const method = init?.method ?? 'GET';
      if (target.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify(DRAFT), { status: 201 });
      }
      if (target.endsWith('/generations/g1/start')) {
        return new Response(JSON.stringify({ message: 'AI API key is required to start' }), {
          status: 400,
        });
      }
      return pageOf([]);
    });
    await reachReview();
    fireEvent.click(screen.getByRole('button', { name: 'Save Draft' }));
    await waitFor(() => expect(screen.getByText('Draft saved')).toBeInTheDocument());

    // Client-side guard first.
    fireEvent.click(screen.getByRole('button', { name: 'Start Generation' }));
    expect(screen.getByText('Please enter your API key.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Start AI API key'), { target: { value: 'sk-live-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Start Generation' }));
    await waitFor(() =>
      expect(screen.getByText('AI API key is required to start')).toBeInTheDocument(),
    );
  });
});

describe('GeneratePage refined wizard', () => {
  async function reachReviewWithStack() {
    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText(/Python FastAPI/));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="frontend"]' }));
    fireEvent.click(screen.getByLabelText('PostgreSQL', { selector: 'input[name="database"]' }));
    fillDatabase();
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    fillAi();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());
  }

  

  it('keeps the wizard at four fixed steps with database config inside the stack step', async () => {
    renderGenerate(() => pageOf([]));
    fillRequirement();
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 2 — Technology stack' })).toBeInTheDocument());
    // Database fields live on the stack step itself — there is no separate step.
    expect(screen.queryByLabelText('Database host')).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/Python FastAPI/));
    fireEvent.click(screen.getByLabelText('None', { selector: 'input[name="frontend"]' }));
    fireEvent.click(screen.getByLabelText('PostgreSQL', { selector: 'input[name="database"]' }));
    expect(screen.getByLabelText('Database host')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByText('Please enter the database host.')).toBeInTheDocument();
  });


  it('navigates Review with Back and Edit, preserving input', async () => {
    renderGenerate(() => pageOf([]));
    await reachReviewWithStack();

    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: /AI configuration/ })).toBeInTheDocument());
    expect(screen.getByLabelText('AI model')).toHaveValue('test/model');

    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Step 1 — Requirement' })).toBeInTheDocument());
    expect(screen.getByLabelText('Project name')).toHaveValue('todo-api');
    expect(screen.getByLabelText('Requirement')).toHaveValue(
      'A minimal todo REST API with create and list endpoints plus tests.',
    );
  });

  it('shows a Generation ready state when the backend has not started executing', async () => {
    let phase: 'ready' | 'failed' = 'ready';
    const readyBody = {
      id: 'g1',
      name: 'todo-api',
      requirement: 'A minimal todo REST API with create and list endpoints plus tests.',
      description: null,
      backend: 'PYTHON_FASTAPI',
      frontend: 'NONE',
      database: 'NONE',
      databaseConfig: null,
      aiConfig: { provider: 'OPENROUTER', model: 'test/model', baseUrl: null, keyConfigured: true },
      error: null,
      projectId: null,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
    renderGenerate((url, init) => {
      const target = String(url);
      const method = init?.method ?? 'GET';
      if (target.endsWith('/generations') && method === 'POST') {
        return new Response(JSON.stringify({ ...readyBody, status: 'QUEUED' }), { status: 201 });
      }
      if (target.endsWith('/generations/g1')) {
        return new Response(
          JSON.stringify(
            phase === 'ready'
              ? { ...readyBody, status: 'READY' }
              : { ...readyBody, status: 'FAILED', error: 'Execution unavailable' },
          ),
          { status: 200 },
        );
      }
      return pageOf([]);
    });
    await reachReviewWithStack();
    fireEvent.click(screen.getByRole('button', { name: 'Start Generation' }));

    await waitFor(() => expect(screen.getByText('Generation ready.')).toBeInTheDocument());
    expect(
      screen.getByText('no progress is shown until the backend reports it', { exact: false }),
    ).toBeInTheDocument();
    // No planning agent is ever called from this wizard.
    expect(screen.queryByText(/planning agent/i)).not.toBeInTheDocument();

    // Drive to a terminal state so no poller outlives the test.
    phase = 'failed';
    await waitFor(
      () => expect(screen.getByText('Execution unavailable')).toBeInTheDocument(),
      { timeout: 10000 },
    );
  });
});
