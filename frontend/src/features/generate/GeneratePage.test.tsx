import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../auth/AuthContext';
import { GeneratePage } from './GeneratePage';

const IDEA = 'A task tracker where teams create projects and assign tasks.';

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
    requirement: IDEA,
    description: null,
    backend: 'JAVA_SPRING_BOOT',
    frontend: 'REACT_TYPESCRIPT',
    database: 'POSTGRESQL',
    auth: 'NONE',
    integrations: [],
    databaseConfig: {
      host: 'localhost',
      port: 5432,
      name: 'app',
      username: 'app',
      sslMode: 'prefer',
      passwordConfigured: false,
    },
    aiConfig: {
      provider: 'OPENROUTER',
      model: 'openai/gpt-4o-mini',
      baseUrl: null,
      keyConfigured: true,
    },
    status: 'QUEUED',
    error: null,
    projectId: null,
    iteration: 1,
    maxIterations: 5,
    revisionNumber: 1,
    revisionCount: 1,
    artifact: null,
    plan: null,
    workflowStats: null,
    verification: null,
    review: null,
    execution: null,
    agentWorkflow: [],
    downloadReady: false,
    previewReady: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function planPreview() {
  return {
    architecture: 'Backend: JAVA SPRING BOOT. Frontend: REACT TYPESCRIPT.',
    dependencies: ['spring-boot-starter-web', 'react'],
    directories: ['src/main/java/app', 'frontend/src'],
    apis: 'HTTP endpoints follow the requirement.',
    steps: ['Scaffold', 'Implement'],
    files: [
      { path: 'src/main/java/app/Application.java', purpose: 'Spring Boot entrypoint' },
      { path: 'frontend/src/App.tsx', purpose: 'Root component' },
    ],
    notes: 'Scaffold preview only. The Planner Agent produces the final file plan after you generate.',
  };
}

function renderGenerate(
  handler: (url: string, init?: RequestInit) => Response | Promise<Response>,
  entry = '/generate',
) {
  const calls: { url: string; method: string; body: string }[] = [];
  vi.stubGlobal(
    'fetch',
    (async (url: string | URL | Request, init?: RequestInit) => {
      const requestUrl = String(url);
      calls.push({
        url: requestUrl,
        method: init?.method ?? 'GET',
        body: String(init?.body ?? ''),
      });
      return handler(requestUrl, init);
    }) as typeof fetch,
  );
  render(
    <MemoryRouter initialEntries={[entry]}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <GeneratePage />
      </AuthProvider>
    </MemoryRouter>,
  );
  return calls;
}

function enterIdea() {
  fireEvent.change(screen.getByLabelText('Project idea'), { target: { value: IDEA } });
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('GeneratePage — five-step wizard', () => {
  it('step 1 requires a meaningful idea before continuing', () => {
    renderGenerate(() => pageOf([]));

    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    expect(
      screen.getByText('Please describe your idea in at least 20 characters.'),
    ).toBeInTheDocument();

    enterIdea();
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    expect(screen.getByText('Choose your stack')).toBeInTheDocument();
  });

  it('step 2 exposes explicit stack, database and auth choices', () => {
    renderGenerate(() => pageOf([]));
    enterIdea();
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));

    expect(screen.getByLabelText('Frontend')).toHaveValue('REACT_TYPESCRIPT');
    expect(screen.getByLabelText('Backend')).toHaveValue('JAVA_SPRING_BOOT');
    expect(screen.getByLabelText('Database')).toHaveValue('POSTGRESQL');
    expect(screen.getByLabelText('Authentication')).toHaveValue('NONE');

    fireEvent.change(screen.getByLabelText('Frontend'), { target: { value: 'NEXTJS' } });
    fireEvent.change(screen.getByLabelText('Backend'), { target: { value: 'NODEJS' } });
    fireEvent.change(screen.getByLabelText('Database'), { target: { value: 'SQLITE' } });
    fireEvent.change(screen.getByLabelText('Authentication'), { target: { value: 'JWT' } });
    fireEvent.click(screen.getByRole('button', { name: 'Choose integrations' }));

    expect(screen.getByRole('heading', { name: 'Integrations' })).toBeInTheDocument();
  });

  it('step 3 multi-selects integrations with a note for each', () => {
    renderGenerate(() => pageOf([]));
    enterIdea();
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    fireEvent.click(screen.getByRole('button', { name: 'Choose integrations' }));

    expect(screen.getByLabelText('Stripe payments')).not.toBeChecked();
    expect(screen.getByText('Dockerfile + GitHub Actions build/test.')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('Stripe payments'));
    fireEvent.click(screen.getByLabelText('Email'));
    expect(screen.getByLabelText('Stripe payments')).toBeChecked();
    expect(screen.getByLabelText('Email')).toBeChecked();
  });

  it('step 4 previews the folder/file plan and can re-plan', async () => {
    const calls = renderGenerate((url) => {
      if (url.endsWith('/generations/plan-preview')) {
        return new Response(JSON.stringify(planPreview()), { status: 200 });
      }
      return pageOf([]);
    });
    enterIdea();
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    fireEvent.click(screen.getByRole('button', { name: 'Choose integrations' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));

    expect(await screen.findByText('src/main/java/app/Application.java')).toBeInTheDocument();
    expect(screen.getByText('frontend/src/App.tsx')).toBeInTheDocument();
    expect(screen.getByText(/Scaffold preview only/)).toBeInTheDocument();

    const before = calls.filter((call) => call.url.endsWith('/generations/plan-preview')).length;
    fireEvent.click(screen.getByRole('button', { name: 'Re-plan' }));
    await waitFor(() =>
      expect(
        calls.filter((call) => call.url.endsWith('/generations/plan-preview')).length,
      ).toBeGreaterThan(before),
    );
  });

  it('step 5 sends stack/auth/integrations and shows the real pipeline', async () => {
    const calls = renderGenerate((url, init) => {
      if (url.endsWith('/generations/plan-preview')) {
        return new Response(JSON.stringify(planPreview()), { status: 200 });
      }
      if (url.endsWith('/generations') && init?.method === 'POST') {
        return new Response(
          JSON.stringify(
            baseGeneration({
              status: 'REVIEWED',
              auth: 'JWT',
              integrations: ['EMAIL'],
              agentWorkflow: [
                { agentType: 'PLANNING', status: 'COMPLETED', durationMs: 4, error: null, createdAt: '2026-01-01T00:00:01Z' },
                { agentType: 'CODING', status: 'COMPLETED', durationMs: 5, error: null, createdAt: '2026-01-01T00:00:02Z' },
                { agentType: 'VERIFIED', status: 'COMPLETED', durationMs: 2, error: null, createdAt: '2026-01-01T00:00:03Z' },
                { agentType: 'REVIEW', status: 'COMPLETED', durationMs: 2, error: null, createdAt: '2026-01-01T00:00:04Z' },
              ],
              execution: {
                command: 'mvn -B test',
                exitCode: 0,
                buildStatus: 'SUCCESS',
                testStatus: 'SUCCESS',
                failureReason: null,
                durationMs: 1200,
                notVerified: false,
                createdAt: '2026-01-01T00:00:03Z',
              },
              verification: {
                verdict: 'VERIFIED',
                buildStatus: 'SUCCESS',
                testsTotal: 3,
                testsPassed: 3,
                testsFailed: 0,
                testsSkipped: 0,
                durationMs: 900,
                logRef: 'build:1',
              },
            }),
          ),
          { status: 201 },
        );
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(baseGeneration({ status: 'REVIEWED' })), { status: 200 });
      }
      return pageOf([]);
    });

    enterIdea();
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    fireEvent.change(screen.getByLabelText('Authentication'), { target: { value: 'JWT' } });
    fireEvent.click(screen.getByRole('button', { name: 'Choose integrations' }));
    fireEvent.click(screen.getByLabelText('Email'));
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));

    await screen.findByText('src/main/java/app/Application.java');
    fireEvent.change(screen.getByLabelText('AI API key'), { target: { value: 'sk-test-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() => expect(screen.getByText('Generation pipeline')).toBeInTheDocument());
    const created = calls.find(
      (call) => call.method === 'POST' && call.url.endsWith('/generations'),
    );
    expect(created).toBeDefined();
    const body = JSON.parse(created!.body);
    expect(body.auth).toBe('JWT');
    expect(body.integrations).toEqual(['EMAIL']);
    expect(body.backend).toBe('JAVA_SPRING_BOOT');
    expect(body.frontend).toBe('REACT_TYPESCRIPT');
    expect(body.database).toBe('POSTGRESQL');
    expect(body.aiConfig.apiKey).toBe('sk-test-key');

    expect(screen.getByText('REVIEWED')).toBeInTheDocument();
    expect(screen.getByText(/Build & Test/)).toBeInTheDocument();
    expect(screen.getAllByText('Done').length).toBeGreaterThanOrEqual(4);
  });

  it('renders Build & Test as Not verified when the sandbox is disabled', async () => {
    renderGenerate((url, init) => {
      if (url.endsWith('/generations/plan-preview')) {
        return new Response(JSON.stringify(planPreview()), { status: 200 });
      }
      if (url.endsWith('/generations') && init?.method === 'POST') {
        return new Response(
          JSON.stringify(
            baseGeneration({
              status: 'REVIEWED',
              execution: {
                command: 'mvn -B test',
                exitCode: 0,
                buildStatus: 'SKIPPED',
                testStatus: 'SKIPPED',
                failureReason: 'Not verified: Docker sandbox is disabled',
                durationMs: 0,
                notVerified: true,
                createdAt: '2026-01-01T00:00:03Z',
              },
              verification: {
                verdict: 'REJECTED',
                buildStatus: 'SKIPPED',
                testsTotal: 0,
                testsPassed: 0,
                testsFailed: 0,
                testsSkipped: 0,
                durationMs: 0,
                logRef: 'sandbox-unavailable',
              },
              agentWorkflow: [
                { agentType: 'PLANNING', status: 'COMPLETED', durationMs: 1, error: null, createdAt: '2026-01-01T00:00:01Z' },
                { agentType: 'CODING', status: 'COMPLETED', durationMs: 1, error: null, createdAt: '2026-01-01T00:00:02Z' },
                { agentType: 'VERIFIED', status: 'SKIPPED', durationMs: 0, error: 'Not verified', createdAt: '2026-01-01T00:00:03Z' },
              ],
            }),
          ),
          { status: 201 },
        );
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(baseGeneration({ status: 'REVIEWED' })), { status: 200 });
      }
      return pageOf([]);
    });

    enterIdea();
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    fireEvent.click(screen.getByRole('button', { name: 'Choose integrations' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    await screen.findByText('src/main/java/app/Application.java');
    fireEvent.change(screen.getByLabelText('AI API key'), { target: { value: 'sk-test-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() =>
      expect(
        screen.getAllByText(/the Docker sandbox is disabled/i).length,
      ).toBeGreaterThan(0),
    );
    const buildStage = screen.getByText('3. Build & Test').closest('li')!;
    expect(within(buildStage).getByText('Not verified')).toBeInTheDocument();
    expect(within(buildStage).queryByText('Done')).not.toBeInTheDocument();
  });

  it('hands a reviewed generation to Review through the backend', async () => {
    const calls = renderGenerate((url, init) => {
      if (url.endsWith('/generations/g1/send-to-review') && init?.method === 'POST') {
        return new Response(
          JSON.stringify(baseGeneration({ status: 'REVIEWED', projectId: 'proj-1' })),
          { status: 200 },
        );
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(baseGeneration({ status: 'REVIEWED' })), {
          status: 200,
        });
      }
      return pageOf([]);
    }, '/generate?genId=g1');

    await screen.findByText('Generation pipeline');
    const send = screen.getByRole('button', { name: 'Send to Review' });
    expect(send).toBeEnabled();
    fireEvent.click(send);

    await waitFor(() =>
      expect(
        calls.some(
          (call) => call.method === 'POST' && call.url.endsWith('/send-to-review'),
        ),
      ).toBe(true),
    );
  });

  it('browses generated files and gates restructure on approval, with revert', async () => {
    const proposal = {
      id: 'p1',
      generationId: 'g1',
      iteration: 1,
      preset: 'MVC',
      description: null,
      status: 'PROPOSED',
      moves: [
        { from: 'src/main/java/app/UserController.java', to: 'controllers/UserController.java' },
      ],
      directories: ['controllers'],
      notes: 'MVC: controllers, services, repositories, models.',
      canRevert: false,
      createdAt: '2026-01-01T00:00:05Z',
    };
    let current = proposal;
    const calls = renderGenerate((url, init) => {
      if (url.endsWith('/generations/plan-preview')) {
        return new Response(JSON.stringify(planPreview()), { status: 200 });
      }
      if (url.endsWith('/generations') && init?.method === 'POST') {
        return new Response(
          JSON.stringify(
            baseGeneration({
              status: 'REVIEWED',
              artifact: {
                id: 'a1',
                iteration: 1,
                fileCount: 2,
                totalChars: 40,
                sha256: 'a'.repeat(64),
                createdAt: '2026-01-01T00:00:03Z',
              },
            }),
          ),
          { status: 201 },
        );
      }
      if (url.endsWith('/generations/g1/files')) {
        return new Response(
          JSON.stringify([
            { path: 'src/main/java/app/UserController.java', sizeBytes: 24 },
            { path: 'README.md', sizeBytes: 8 },
          ]),
          { status: 200 },
        );
      }
      if (url.includes('/generations/g1/file?path=')) {
        return new Response(
          JSON.stringify({
            path: 'README.md',
            content: '# demo',
            truncated: false,
            binary: false,
          }),
          { status: 200 },
        );
      }
      if (url.endsWith('/generations/g1/restructure/proposals') && init?.method === 'POST') {
        return new Response(JSON.stringify(proposal), { status: 201 });
      }
      if (url.endsWith('/generations/g1/restructure/proposals')) {
        return new Response(JSON.stringify(current.status === 'PROPOSED' ? [] : [current]), {
          status: 200,
        });
      }
      if (url.endsWith('/restructure/proposals/p1/apply')) {
        current = { ...proposal, status: 'APPLIED', canRevert: true };
        return new Response(JSON.stringify(current), { status: 200 });
      }
      if (url.endsWith('/generations/g1/restructure/revert')) {
        current = { ...proposal, status: 'REVERTED', canRevert: false };
        return new Response(JSON.stringify(current), { status: 200 });
      }
      if (url.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(baseGeneration({ status: 'REVIEWED' })), { status: 200 });
      }
      return pageOf([]);
    });

    enterIdea();
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    fireEvent.click(screen.getByRole('button', { name: 'Choose integrations' }));
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    await screen.findByText('src/main/java/app/Application.java');
    fireEvent.change(screen.getByLabelText('AI API key'), { target: { value: 'sk-test-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    expect(await screen.findByText('Generated project')).toBeInTheDocument();
    fireEvent.click(await screen.findByText('README.md'));
    expect(await screen.findByText('# demo')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Propose new structure' }));
    expect(await screen.findByText('MVC proposal')).toBeInTheDocument();
    expect(screen.getByText('PROPOSED')).toBeInTheDocument();
    expect(
      calls.some((call) => call.url.includes('/proposals/p1/apply')),
    ).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Approve & apply' }));
    await waitFor(() => expect(screen.getByText('APPLIED')).toBeInTheDocument());
    expect(calls.some((call) => call.url.includes('/proposals/p1/apply'))).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Revert to original' }));
    await waitFor(() => expect(screen.getByText('REVERTED')).toBeInTheDocument());
    expect(
      calls.some((call) => call.url.endsWith('/generations/g1/restructure/revert')),
    ).toBe(true);
  });
});
