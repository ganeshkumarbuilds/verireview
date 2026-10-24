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
    languageRuntime: 'Java 21',
    backendCustom: null,
    frontendCustom: null,
    database: 'NONE',
    auth: 'NONE',
    integrations: [],
    databaseConfigureLater: true,
    answers: [],
    databaseConfig: null,
    aiConfig: {
      provider: 'NONE',
      model: 'template',
      baseUrl: null,
      keyConfigured: false,
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
    rating: null,
    findings: [],
    execution: null,
    agentWorkflow: [],
    verificationLevel: 'NOT_RUN',
    downloadReady: false,
    previewReady: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

function questionSet() {
  return [
    {
      id: 'database.type',
      prompt: 'Which database should the project use?',
      help: 'Pick the database engine.',
      type: 'CHOICE',
      required: true,
      allowSkip: true,
      secret: false,
      options: [
        { value: 'POSTGRESQL', label: 'PostgreSQL' },
        { value: 'MYSQL', label: 'MySQL' },
        { value: 'NONE', label: 'None — no database' },
      ],
    },
    {
      id: 'database.connection',
      prompt: 'What are the database connection details?',
      help: 'Leave empty to configure later.',
      type: 'TEXT',
      required: false,
      allowSkip: true,
      secret: false,
      options: [],
    },
    {
      id: 'auth.method',
      prompt: 'How should users authenticate?',
      help: 'Authentication options.',
      type: 'CHOICE',
      required: true,
      allowSkip: true,
      secret: false,
      options: [
        { value: 'JWT', label: 'JWT' },
        { value: 'NONE', label: 'No authentication' },
      ],
    },
    {
      id: 'ai.features',
      prompt: 'Does the product itself include AI features?',
      help: 'About the app, not the generator.',
      type: 'YES_NO',
      required: true,
      allowSkip: false,
      secret: false,
      options: [],
    },
    {
      id: 'generation.engine',
      prompt: 'Which generation engine should write the code?',
      help: 'Template is deterministic.',
      type: 'CHOICE',
      required: true,
      allowSkip: false,
      secret: false,
      options: [
        { value: 'OPENROUTER', label: 'OpenRouter' },
        { value: 'TEMPLATE', label: 'Template — no AI key' },
      ],
    },
    {
      id: 'generation.model',
      prompt: 'Which generation model?',
      help: 'Required unless template.',
      type: 'TEXT',
      required: false,
      allowSkip: false,
      secret: false,
      options: [],
    },
    {
      id: 'generation.apiKey',
      prompt: 'Generation engine API key',
      help: 'Sent once.',
      type: 'TEXT',
      required: false,
      allowSkip: false,
      secret: true,
      options: [],
    },
    {
      id: 'deployment.target',
      prompt: 'Where will this deploy?',
      help: 'Adds deployment files.',
      type: 'CHOICE',
      required: true,
      allowSkip: true,
      secret: false,
      options: [
        { value: 'LOCAL', label: 'Local machine' },
        { value: 'DOCKER', label: 'Docker' },
      ],
    },
    {
      id: 'integrations',
      prompt: 'Which third-party integrations should be scaffolded?',
      help: 'Comma separated.',
      type: 'TEXT',
      required: false,
      allowSkip: true,
      secret: false,
      options: [],
    },
  ];
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
    notes: 'Built only from your explicit answers; nothing was assumed.',
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

function answerBasics(name = 'task-tracker') {
  fireEvent.change(screen.getByLabelText(/Project name/), { target: { value: name } });
  fireEvent.change(screen.getByLabelText(/Task description/), { target: { value: IDEA } });
  fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
}

function chooseStack() {
  fireEvent.change(screen.getByLabelText('Frontend'), { target: { value: 'REACT_TYPESCRIPT' } });
  fireEvent.change(screen.getByLabelText('Backend'), { target: { value: 'JAVA_SPRING_BOOT' } });
  fireEvent.change(screen.getByLabelText(/Language \/ runtime/), {
    target: { value: 'Java 21' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Answer questions' }));
}

async function answerQuestions() {
  await screen.findByLabelText('Which database should the project use?');
  fireEvent.change(screen.getByLabelText('Which database should the project use?'), {
    target: { value: 'NONE' },
  });
  fireEvent.change(screen.getByLabelText('How should users authenticate?'), {
    target: { value: 'NONE' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'No' }));
  fireEvent.change(screen.getByLabelText('Which generation engine should write the code?'), {
    target: { value: 'TEMPLATE' },
  });
  fireEvent.change(screen.getByLabelText('Where will this deploy?'), {
    target: { value: 'LOCAL' },
  });
}

function handlerWithQuestions(extra?: (url: string, init?: RequestInit) => Response | undefined) {
  return (url: string, init?: RequestInit) => {
    const custom = extra?.(url, init);
    if (custom) {
      return custom;
    }
    if (url.endsWith('/generate/questions')) {
      return new Response(JSON.stringify(questionSet()), { status: 200 });
    }
    if (url.endsWith('/generate/plan')) {
      return new Response(JSON.stringify(planPreview()), { status: 200 });
    }
    return pageOf([]);
  };
}

function resultGeneration(overrides: Record<string, unknown> = {}) {
  return baseGeneration({
    status: 'AWAITING_FIX_APPROVAL',
    aiConfig: {
      provider: 'OPENROUTER',
      model: 'test-model',
      baseUrl: null,
      keyConfigured: false,
    },
    findings: [
      {
        id: 'finding-1',
        file: 'src/App.tsx',
        line: 10,
        category: 'STYLE',
        severity: 'HIGH',
        title: 'App needs attention',
        explanation: 'The component has an issue.',
        status: 'OPEN',
      },
      {
        id: 'finding-2',
        file: 'src/api.ts',
        line: 20,
        category: 'SECURITY',
        severity: 'MEDIUM',
        title: 'API needs attention',
        explanation: 'The request needs validation.',
        status: 'OPEN',
      },
    ],
    ...overrides,
  });
}

function renderResultGeneration(
  generation: Record<string, unknown>,
  extra?: (url: string, init?: RequestInit) => Response | undefined,
) {
  return renderGenerate(
    (url, init) => {
      const custom = extra?.(url, init);
      if (custom) return custom;
      if (url.endsWith('/generations/g1')) {
        return new Response(JSON.stringify(generation), { status: 200 });
      }
      if (url.endsWith('/generations/g1/restructure/proposals')) {
        return new Response(JSON.stringify([]), { status: 200 });
      }
      return pageOf([]);
    },
    '/generate?genId=g1',
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('GeneratePage — four-step explicit wizard', () => {
  it('step 1 requires a project name and a meaningful task description', () => {
    renderGenerate(() => pageOf([]));

    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    expect(screen.getByText('Project name is required.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/Project name/), { target: { value: 'demo' } });
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    expect(
      screen.getByText('Please describe the task in at least 20 characters.'),
    ).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/Task description/), { target: { value: IDEA } });
    fireEvent.click(screen.getByRole('button', { name: 'Choose stack' }));
    expect(screen.getByRole('heading', { name: 'Stack' })).toBeInTheDocument();
  });

  it('step 2 starts empty, shows suggestions without applying them, and supports Other', () => {
    renderGenerate(() => pageOf([]));
    answerBasics();

    expect(screen.getByLabelText('Frontend')).toHaveValue('');
    expect(screen.getByLabelText('Backend')).toHaveValue('');
    expect(screen.getByLabelText(/Language \/ runtime/)).toHaveValue('');

    fireEvent.click(screen.getByRole('button', { name: 'Suggest options' }));
    expect(screen.getByText(/Suggestions only/)).toBeInTheDocument();
    expect(screen.getByLabelText('Frontend')).toHaveValue('');
    expect(screen.getByLabelText('Backend')).toHaveValue('');

    fireEvent.change(screen.getByLabelText('Backend'), { target: { value: 'OTHER' } });
    expect(screen.getByLabelText(/Custom backend/)).toBeInTheDocument();
  });

  it('step 3 renders backend questions and blocks unanswered required ones', async () => {
    renderGenerate(handlerWithQuestions());
    answerBasics();
    chooseStack();

    await screen.findByLabelText('Which database should the project use?');
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    expect(screen.getByText(/Answer these questions first/)).toBeInTheDocument();

    await answerQuestions();
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    expect(await screen.findByLabelText('Planned file path 1')).toHaveValue('src/main/java/app/Application.java');
  });

  it('requires connection details or an explicit Ask me later for a chosen database', async () => {
    renderGenerate(handlerWithQuestions());
    answerBasics();
    chooseStack();

    await screen.findByLabelText('Which database should the project use?');
    fireEvent.change(screen.getByLabelText('Which database should the project use?'), {
      target: { value: 'POSTGRESQL' },
    });
    fireEvent.change(screen.getByLabelText('How should users authenticate?'), {
      target: { value: 'NONE' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'No' }));
    fireEvent.change(screen.getByLabelText('Which generation engine should write the code?'), {
      target: { value: 'TEMPLATE' },
    });
    fireEvent.change(screen.getByLabelText('Where will this deploy?'), {
      target: { value: 'LOCAL' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    expect(
      screen.getByText(/Enter database host, port, name and username/),
    ).toBeInTheDocument();

    // The second "Ask me later" belongs to the connection question.
    fireEvent.click(screen.getAllByRole('button', { name: 'Ask me later' })[1]);
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    expect(await screen.findByLabelText('Planned file path 1')).toHaveValue('src/main/java/app/Application.java');
  });

  it('step 4 plans from answers and creates with no silent defaults', async () => {
    const calls = renderGenerate(
      handlerWithQuestions((url, init) => {
        if (url.endsWith('/generations') && init?.method === 'POST') {
          return new Response(JSON.stringify(baseGeneration()), { status: 201 });
        }
        return undefined;
      }),
    );
    answerBasics();
    chooseStack();
    await answerQuestions();
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));

    expect(await screen.findByLabelText('Planned file path 1')).toHaveValue('src/main/java/app/Application.java');
    expect(screen.getByText(/Built only from your explicit answers/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Planned file path 1'), {
      target: { value: 'src/main/java/app/TaskTracker.java' },
    });
    fireEvent.change(screen.getByLabelText('Purpose for planned file 1'), {
      target: { value: 'User-approved task tracker entrypoint' },
    });

    const planCall = calls.find((call) => call.url.endsWith('/generate/plan'));
    expect(planCall).toBeDefined();
    const planBody = JSON.parse(planCall!.body);
    expect(planBody.answers).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ questionId: 'database.type', value: 'NONE' }),
        expect.objectContaining({ questionId: 'auth.method', value: 'NONE' }),
        expect.objectContaining({ questionId: 'generation.engine', value: 'TEMPLATE' }),
      ]),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Approve and generate' }));
    await waitFor(() => expect(screen.getByText('Generation pipeline')).toBeInTheDocument());

    const created = calls.find(
      (call) => call.method === 'POST' && call.url.endsWith('/generations'),
    );
    expect(created).toBeDefined();
    const body = JSON.parse(created!.body);
    expect(body.name).toBe('task-tracker');
    expect(body.languageRuntime).toBe('Java 21');
    expect(body.database).toBe('NONE');
    expect(body.databaseConfigureLater).toBe(false);
    expect(body.databaseConfig).toBeUndefined();
    expect(body.auth).toBe('NONE');
    expect(body.approvedPlan.files[0]).toEqual({
      path: 'src/main/java/app/TaskTracker.java',
      purpose: 'User-approved task tracker entrypoint',
    });
    expect(body.aiConfig).toEqual({ provider: 'NONE', model: 'template' });
    expect(body.answers).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ questionId: 'database.type', value: 'NONE' }),
      ]),
    );
  });

  it('step 4 sends a secret answer once and never uses a default key', async () => {
    const calls = renderGenerate(
      handlerWithQuestions((url, init) => {
        if (url.endsWith('/generations') && init?.method === 'POST') {
          return new Response(
            JSON.stringify(baseGeneration({ aiConfig: { provider: 'OPENROUTER', model: 'm', baseUrl: null, keyConfigured: true } })),
            { status: 201 },
          );
        }
        return undefined;
      }),
    );
    answerBasics();
    chooseStack();
    await screen.findByLabelText('Which database should the project use?');
    fireEvent.change(screen.getByLabelText('Which database should the project use?'), {
      target: { value: 'NONE' },
    });
    fireEvent.change(screen.getByLabelText('How should users authenticate?'), {
      target: { value: 'NONE' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'No' }));
    fireEvent.change(screen.getByLabelText('Which generation engine should write the code?'), {
      target: { value: 'OPENROUTER' },
    });
    fireEvent.change(screen.getByLabelText('Which generation model?'), {
      target: { value: 'openai/gpt-test' },
    });
    fireEvent.change(screen.getByLabelText('Generation engine API key'), {
      target: { value: 'sk-test-key' },
    });
    fireEvent.change(screen.getByLabelText('Where will this deploy?'), {
      target: { value: 'LOCAL' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    await screen.findByLabelText('Planned file path 1');
    fireEvent.click(screen.getByRole('button', { name: 'Approve and generate' }));

    await waitFor(() =>
      expect(calls.some((call) => call.method === 'POST' && call.url.endsWith('/generations'))).toBe(
        true,
      ),
    );
    const created = calls.find(
      (call) => call.method === 'POST' && call.url.endsWith('/generations'),
    );
    const body = JSON.parse(created!.body);
    expect(body.aiConfig.apiKey).toBe('sk-test-key');
    expect(body.aiConfig.provider).toBe('OPENROUTER');
    expect(body.aiConfig.model).toBe('openai/gpt-test');
  });

  it('shows the real pipeline and hands a reviewed generation to Review', async () => {
    const calls = renderGenerate(
      handlerWithQuestions((url, init) => {
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
        return undefined;
      }),
      '/generate?genId=g1',
    );

    await screen.findByText('Generation pipeline');
    const send = screen.getByRole('button', { name: 'Send to Review' });
    expect(send).toBeEnabled();
    fireEvent.click(send);

    await waitFor(() =>
      expect(
        calls.some((call) => call.method === 'POST' && call.url.endsWith('/send-to-review')),
      ).toBe(true),
    );
  });

  it('shows agent states, timing, rating, and findings without enabling an unverified ZIP', async () => {
    const generation = baseGeneration({
      status: 'REVIEWED',
      rating: { overall: 72, syntax: 100, logic: 82, security: 60, style: 94 },
      findings: [
        {
          file: 'src/main/App.java',
          line: 12,
          category: 'security',
          severity: 'HIGH',
          title: 'Unsafe access',
          explanation: 'Validate the caller before returning this resource.',
        },
      ],
      agentWorkflow: [
        {
          agentType: 'PLANNING',
          status: 'COMPLETED',
          durationMs: 10,
          error: null,
          createdAt: '2026-01-01T00:00:01Z',
          finishedAt: '2026-01-01T00:00:02Z',
          activityLog: 'plan:abc',
        },
        {
          agentType: 'CODING',
          status: 'RUNNING',
          durationMs: null,
          error: null,
          createdAt: '2026-01-01T00:00:03Z',
          finishedAt: null,
          activityLog: null,
        },
        {
          agentType: 'VERIFIED',
          status: 'SKIPPED',
          durationMs: 0,
          error: 'Not verified: sandbox unavailable',
          createdAt: '2026-01-01T00:00:04Z',
          finishedAt: '2026-01-01T00:00:04Z',
          activityLog: 'verification:abc',
        },
        {
          agentType: 'REVIEW',
          status: 'FAILED',
          durationMs: 5,
          error: 'Review service unavailable.',
          createdAt: '2026-01-01T00:00:05Z',
          finishedAt: '2026-01-01T00:00:06Z',
          activityLog: null,
        },
        {
          agentType: 'RATING',
          status: 'COMPLETED',
          durationMs: 2,
          error: null,
          createdAt: '2026-01-01T00:00:07Z',
          finishedAt: '2026-01-01T00:00:08Z',
          activityLog: 'overall:72',
        },
      ],
      execution: {
        command: 'mvn test',
        exitCode: 0,
        buildStatus: 'SKIPPED',
        testStatus: 'SKIPPED',
        failureReason: 'Not verified: sandbox unavailable',
        durationMs: 0,
        notVerified: true,
        createdAt: '2026-01-01T00:00:04Z',
      },
      downloadReady: false,
    });
    renderGenerate((url) =>
      url.endsWith('/generations/g1')
        ? new Response(JSON.stringify(generation), { status: 200 })
        : pageOf([]),
      '/generate?genId=g1',
    );

    await screen.findByText('Generation pipeline');
    expect(screen.getByText('Overall score: 72/100.')).toBeInTheDocument();
    expect(screen.getByText('72/100')).toBeInTheDocument();
    expect(screen.getByText('Unsafe access')).toBeInTheDocument();
    expect(screen.getByText('src/main/App.java')).toBeInTheDocument();
    expect(screen.getByText('Review service unavailable.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Download ZIP' })).toBeDisabled();
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
    const calls = renderGenerate(
      handlerWithQuestions((url, init) => {
        if (url.endsWith('/generations') && init?.method === 'POST') {
          return new Response(
            JSON.stringify(
              baseGeneration({
                status: 'REVIEWED',
                rating: { overall: 100, syntax: 100, logic: 100, security: 100, style: 100 },
                agentWorkflow: [
                  {
                    agentType: 'RATING',
                    status: 'COMPLETED',
                    durationMs: 1,
                    error: null,
                    createdAt: '2026-01-01T00:00:04Z',
                    finishedAt: '2026-01-01T00:00:05Z',
                    activityLog: 'overall:100',
                  },
                ],
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
          return new Response(JSON.stringify(baseGeneration({ status: 'REVIEWED' })), {
            status: 200,
          });
        }
        return undefined;
      }),
    );

    answerBasics();
    chooseStack();
    await answerQuestions();
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    await screen.findByLabelText('Planned file path 1');
    fireEvent.click(screen.getByRole('button', { name: 'Approve and generate' }));

    expect(await screen.findByText('Generated project')).toBeInTheDocument();
    fireEvent.click(await screen.findByText('README.md'));
    expect(await screen.findByText('# demo')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Propose new structure' }));
    expect(await screen.findByText('MVC proposal')).toBeInTheDocument();
    expect(screen.getByText('PROPOSED')).toBeInTheDocument();
    expect(calls.some((call) => call.url.includes('/proposals/p1/apply'))).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Approve & apply' }));
    await waitFor(() => expect(screen.getByText('APPLIED')).toBeInTheDocument());
    expect(calls.some((call) => call.url.includes('/proposals/p1/apply'))).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Revert to original' }));
    await waitFor(() => expect(screen.getByText('REVERTED')).toBeInTheDocument());
    expect(
      calls.some((call) => call.url.endsWith('/generations/g1/restructure/revert')),
    ).toBe(true);
  });

  it('renders Verifying as Not verified when the sandbox is disabled', async () => {
    renderGenerate(
      handlerWithQuestions((url, init) => {
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
                verificationLevel: 'SANDBOX_UNAVAILABLE',
                agentWorkflow: [
                  { agentType: 'PLANNING', status: 'COMPLETED', durationMs: 1, error: null, createdAt: '2026-01-01T00:00:01Z' },
                  { agentType: 'CODING', status: 'COMPLETED', durationMs: 1, error: null, createdAt: '2026-01-01T00:00:02Z' },
                  { agentType: 'VERIFIED', status: 'SKIPPED', durationMs: 0, error: 'Not verified', createdAt: '2026-01-01T00:00:03Z' },
                  { agentType: 'RATING', status: 'COMPLETED', durationMs: 1, error: null, createdAt: '2026-01-01T00:00:04Z' },
                ],
                rating: { overall: 100, syntax: 100, logic: 100, security: 100, style: 100 },
              }),
            ),
            { status: 201 },
          );
        }
        if (url.endsWith('/generations/g1')) {
          return new Response(JSON.stringify(baseGeneration({ status: 'REVIEWED' })), {
            status: 200,
          });
        }
        return undefined;
      }),
    );

    answerBasics();
    chooseStack();
    await answerQuestions();
    fireEvent.click(screen.getByRole('button', { name: 'Review plan' }));
    await screen.findByLabelText('Planned file path 1');
    fireEvent.click(screen.getByRole('button', { name: 'Approve and generate' }));

    expect(
      (await screen.findAllByText(/build and tests not run: sandbox unavailable/i)).length,
    ).toBeGreaterThan(0);
    const buildStage = screen.getByText('3. Verifying').closest('li')!;
    expect(within(buildStage).getByText('Not verified')).toBeInTheDocument();
    expect(within(buildStage).queryByText('Done')).not.toBeInTheDocument();
  });

  it('selects findings and updates the Fix selected count', async () => {
    renderResultGeneration(resultGeneration());

    await screen.findByText('Findings');
    const checkboxes = screen.getAllByRole('checkbox');
    expect(checkboxes).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Fix selected (2)' })).toBeInTheDocument();

    fireEvent.click(checkboxes[0]);
    expect(screen.getByRole('button', { name: 'Fix selected (1)' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Select all' }));
    expect(screen.getAllByRole('checkbox').every((checkbox) => (checkbox as HTMLInputElement).checked)).toBe(true);
    expect(screen.getByRole('button', { name: 'Deselect all' })).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('checkbox')[0]);
    expect(screen.getByRole('button', { name: 'Fix selected (1)' })).toBeInTheDocument();
  });

  it('previews files without approving until Confirm, Cancel, or Escape', async () => {
    const calls = renderResultGeneration(
      resultGeneration(),
      (url, init) => {
        if (url.endsWith('/fix-approvals/preview')) {
          return new Response(JSON.stringify({
            files: ['src/App.tsx', 'src/shared/types.ts'],
            findingCount: 1,
          }), { status: 200 });
        }
        if (url.endsWith('/fix-approvals') && init?.method === 'POST') {
          return new Response(JSON.stringify(resultGeneration({ status: 'RUNNING' })), { status: 200 });
        }
        return undefined;
      },
    );

    await screen.findByText('Findings');
    fireEvent.click(screen.getAllByRole('checkbox')[1]);
    fireEvent.click(screen.getByRole('button', { name: 'Fix selected (1)' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(within(screen.getByRole('dialog')).getByText('src/App.tsx')).toBeInTheDocument();
    expect(screen.getByText('src/shared/types.ts')).toBeInTheDocument();
    expect(calls.some((call) => call.url.endsWith('/fix-approvals') && call.method === 'POST')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls.some((call) => call.url.endsWith('/fix-approvals') && call.method === 'POST')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Fix selected (1)' }));
    await screen.findByRole('dialog');
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls.some((call) => call.url.endsWith('/fix-approvals') && call.method === 'POST')).toBe(false);
  });

  it('confirms exactly the selected finding ids', async () => {
    const calls = renderResultGeneration(
      resultGeneration(),
      (url, init) => {
        if (url.endsWith('/fix-approvals/preview')) {
          return new Response(JSON.stringify({ files: ['src/api.ts'], findingCount: 1 }), { status: 200 });
        }
        if (url.endsWith('/fix-approvals') && init?.method === 'POST') {
          return new Response(JSON.stringify(resultGeneration({ status: 'RUNNING' })), { status: 200 });
        }
        return undefined;
      },
    );

    await screen.findByText('Findings');
    fireEvent.click(screen.getAllByRole('checkbox')[1]);
    fireEvent.click(screen.getByRole('button', { name: 'Fix selected (1)' }));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByLabelText(/AI provider API key/), { target: { value: 'test-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(
      calls.some((call) => call.url.endsWith('/fix-approvals') && call.method === 'POST'),
    ).toBe(true));
    const approvalCall = calls.find(
      (call) => call.url.endsWith('/fix-approvals') && call.method === 'POST',
    );
    expect(JSON.parse(approvalCall!.body)).toMatchObject({
      findingIds: ['finding-1'],
      apiKey: 'test-key',
    });
  });

  it('shows a failed stage error and retries only that stage', async () => {
    const calls = renderResultGeneration(
      resultGeneration({
        status: 'FAILED',
        error: 'Review could not finish. Try this stage again.',
        agentWorkflow: [{
          id: 'review-execution',
          agentType: 'REVIEW',
          status: 'FAILED',
          durationMs: 100,
          error: 'Review could not finish. Try this stage again.',
          createdAt: '2026-01-01T00:00:01Z',
          finishedAt: '2026-01-01T00:00:02Z',
          activityLog: null,
          roundNumber: 1,
        }],
      }),
      (url, init) => {
        if (url.endsWith('/agent-executions/review-execution/retry') && init?.method === 'POST') {
          return new Response(JSON.stringify(resultGeneration({ status: 'RUNNING' })), { status: 200 });
        }
        return undefined;
      },
    );

    await screen.findAllByText('Review could not finish. Try this stage again.');
    fireEvent.click(screen.getByRole('button', { name: 'Retry Reviewing' }));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByLabelText('AI provider API key'), { target: { value: 'test-key' } });
    fireEvent.click(screen.getByRole('button', { name: 'Retry stage' }));

    await waitFor(() => expect(
      calls.some((call) => call.url.includes('/agent-executions/') && call.url.endsWith('/retry')),
    ).toBe(true));
    expect(calls.filter((call) => call.url.includes('/agent-executions/') && call.method === 'POST')).toHaveLength(1);
    const retryCall = calls.find((call) => call.url.includes('/agent-executions/') && call.method === 'POST');
    expect(retryCall?.url).toContain('/review-execution/retry');
  });

  it('confirms before calling revert-round', async () => {
    const calls = renderResultGeneration(
      resultGeneration({
        status: 'REVIEWED',
        fixRounds: [{
          approvalId: 'approval-1',
          roundNumber: 1,
          findingIds: ['finding-1'],
          beforeFindingCount: 2,
          afterFindingCount: 1,
          status: 'COMPLETED',
          approvedAt: '2026-01-01T00:00:01Z',
          completedAt: '2026-01-01T00:00:02Z',
        }],
        fileVersions: [{
          id: 'version-1',
          roundNumber: 1,
          path: 'src/App.tsx',
          beforeContent: 'before',
          afterContent: 'after',
          reverted: false,
          createdAt: '2026-01-01T00:00:02Z',
        }],
      }),
      (url, init) => {
        if (url.endsWith('/fix-rounds/1/revert') && init?.method === 'POST') {
          return new Response(JSON.stringify(resultGeneration({ status: 'REVIEWED' })), { status: 200 });
        }
        return undefined;
      },
    );

    await screen.findByRole('heading', { name: 'Round 1' });
    fireEvent.click(screen.getByRole('button', { name: 'Revert round 1' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(calls.some((call) => call.url.endsWith('/fix-rounds/1/revert'))).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Confirm revert' }));
    await waitFor(() => expect(
      calls.some((call) => call.url.endsWith('/fix-rounds/1/revert') && call.method === 'POST'),
    ).toBe(true));
  });

  it('shows round number and before/after finding counts', async () => {
    renderResultGeneration(resultGeneration({
      status: 'REVIEWED',
      fixRounds: [{
        approvalId: 'approval-1',
        roundNumber: 2,
        findingIds: ['finding-1'],
        beforeFindingCount: 4,
        afterFindingCount: 2,
        status: 'COMPLETED',
        approvedAt: '2026-01-01T00:00:01Z',
        completedAt: '2026-01-01T00:00:02Z',
      }],
      fileVersions: [],
    }));

    expect(await screen.findByText('Round 2')).toBeInTheDocument();
    expect(screen.getByText(/Findings: 4 before → 2 after/)).toBeInTheDocument();
  });

  it('shows the fix limit and hides Fix selected at the maximum round', async () => {
    renderResultGeneration(resultGeneration({
      iteration: 5,
      maxIterations: 5,
    }));

    expect(await screen.findByText('Fix limit reached.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Fix selected/ })).not.toBeInTheDocument();
  });

  it.each([
    ['STATIC', 'Verified (static)', true],
    ['BUILD_AND_TESTS', 'Verified (build and tests)', true],
    ['SANDBOX_UNAVAILABLE', 'Build and tests not run: sandbox unavailable', false],
  ] as const)('gates ZIP and displays verification for %s', async (verificationLevel, text, ready) => {
    renderResultGeneration(resultGeneration({
      status: 'COMPLETED',
      findings: [],
      verificationLevel,
      downloadReady: ready,
    }));

    await screen.findByText('Generation pipeline');
    const verification = screen.getByText(/Verification:/i);
    expect(verification.textContent).toContain(text);
    expect(screen.getByRole('button', { name: 'Download ZIP' })).toHaveProperty('disabled', !ready);
  });
});
