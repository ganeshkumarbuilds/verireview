const fs = require('fs');
const f = 'C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx';
const c = fs.readFileSync(f, 'utf8');

// Fix the "shows backend failures without exposing secrets" test
const oldTest = `it('shows backend failures without exposing secrets', async () => {
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
});`;

const newTest = `it('shows backend failures without exposing secrets', async () => {
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

    await fillRequirementWithStack();
    await waitFor(() => expect(screen.getByLabelText('Detected stack')).toBeInTheDocument());
    fillDatabase();
    fillAi();
    fireEvent.click(screen.getByRole('button', { name: 'Generate project' }));

    await waitFor(() => expect(screen.getByText('Planning failed: AI down')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Back to review' })).toBeInTheDocument();
  });
});`;

const c = fs.readFileSync('C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx', 'utf8');
const n = c.replace(oldTest, newTest);
fs.writeFileSync('C:\\Projects\\verireview\\frontend\\src\\pages\\GeneratePage.test.tsx', n);
console.log('Fixed');