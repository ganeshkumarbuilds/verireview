import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { FindingDetail } from './FindingDetail';
import { FixWorkflow } from './FixWorkflow';
import type {
  ExecutionRunResponse,
  FindingResponse,
  FixRequestResponse,
  PatchResponse,
  VerificationRunResponse,
} from '../api/types';

const FINDING: FindingResponse = {
  id: 'f1',
  reviewId: 'r1',
  category: 'SECURITY',
  severity: 'HIGH',
  source: 'DETERMINISTIC',
  status: 'OPEN',
  analyzer: 'checkstyle',
  rule: 'LineLength',
  title: 'LineLength',
  description: 'Line too long',
  filePath: 'Main.java',
  lineStart: 42,
  lineEnd: 42,
  evidence: JSON.stringify({ analyzer: 'checkstyle', rule: 'LineLength', suggestedFixHint: 'shorten line' }),
  dedupKey: 'k1',
  createdAt: '2026-01-01T00:00:00Z',
};

function renderDetail(finding: FindingResponse = FINDING) {
  return render(
    <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
      <FindingDetail finding={finding} onClose={() => {}} onViewFile={() => {}} />
    </AuthProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('FindingDetail fix request', () => {
  it('shows fix button and empty history', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify([]), { status: 200 })) as unknown as typeof fetch,
    );
    renderDetail();
    expect(await screen.findByText('No fix requests yet.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Fix this issue' })).toBeInTheDocument();
  });

  it('opens dialog with finding summary and scope note', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify([]), { status: 200 })) as unknown as typeof fetch,
    );
    renderDetail();
    await screen.findByText('No fix requests yet.');
    fireEvent.click(screen.getByRole('button', { name: 'Fix this issue' }));
    expect(screen.getByRole('dialog', { name: 'Fix request dialog' })).toBeInTheDocument();
    expect(screen.getAllByText('LineLength').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByPlaceholderText(/Add context/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit request' })).toBeInTheDocument();
  });

  it('submits fix request and shows REQUESTED status', async () => {
    const created = {
      id: 'fix1',
      findingId: 'f1',
      projectId: 'p1',
      requestedBy: 'u1',
      status: 'REQUESTED',
      scopeNote: 'please fix',
      error: null,
      createdAt: '2026-01-02T00:00:00Z',
      updatedAt: '2026-01-02T00:00:00Z',
    };
    let call = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const target = String(url);
        if (target.includes('/patch')) {
          return new Response(JSON.stringify({ message: 'Patch not found' }), { status: 404 });
        }
        call += 1;
        if (call === 1) {
          // initial history empty
          return new Response(JSON.stringify([]), { status: 200 });
        }
        if (target.includes('/fix-requests') && init?.method === 'POST') {
          return new Response(JSON.stringify(created), { status: 202 });
        }
        // reload after create
        return new Response(JSON.stringify([created]), { status: 200 });
      }) as unknown as typeof fetch,
    );
    renderDetail();
    await screen.findByText('No fix requests yet.');
    fireEvent.click(screen.getByRole('button', { name: 'Fix this issue' }));
    fireEvent.change(screen.getByLabelText('Scope note'), { target: { value: 'please fix' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    await waitFor(() => expect(screen.getByText(/Fix requested — status: REQUESTED/)).toBeInTheDocument());
    expect(await screen.findByText('REQUESTED')).toBeInTheDocument();
  });

  it('handles 409 duplicate active request', async () => {
    let historyCalls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const target = String(url);
        if (target.includes('/patch')) {
          return new Response(JSON.stringify({ message: 'Patch not found' }), { status: 404 });
        }
        if (target.includes('/fix-requests') && init?.method === 'POST') {
          return new Response(JSON.stringify({ message: 'An open fix request already exists' }), {
            status: 409,
          });
        }
        historyCalls += 1;
        if (historyCalls === 1) {
          return new Response(JSON.stringify([]), { status: 200 });
        }
        return new Response(
          JSON.stringify([
            {
              id: 'fix1',
              findingId: 'f1',
              projectId: 'p1',
              requestedBy: 'u1',
              status: 'REQUESTED',
              scopeNote: null,
              error: null,
              createdAt: '2026-01-02T00:00:00Z',
              updatedAt: '2026-01-02T00:00:00Z',
            },
          ]),
          { status: 200 },
        );
      }) as unknown as typeof fetch,
    );
    renderDetail();
    await screen.findByText('No fix requests yet.');
    fireEvent.click(screen.getByRole('button', { name: 'Fix this issue' }));
    fireEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(await screen.findByText(/An active fix request already exists/)).toBeInTheDocument();
  });

  it('prevents duplicate when active request exists', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request) => {
        if (String(url).includes('/patch')) {
          return new Response(JSON.stringify({ message: 'Patch not found' }), { status: 404 });
        }
        return new Response(
          JSON.stringify([
            {
              id: 'fix1',
              findingId: 'f1',
              projectId: 'p1',
              requestedBy: 'u1',
              status: 'REQUESTED',
              scopeNote: null,
              error: null,
              createdAt: '2026-01-02T00:00:00Z',
              updatedAt: '2026-01-02T00:00:00Z',
            },
          ]),
          { status: 200 },
        );
      }) as unknown as typeof fetch,
    );
    renderDetail();
    expect(await screen.findByText('REQUESTED')).toBeInTheDocument();
    expect(screen.getByText(/An active fix request already exists/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Fix this issue' })).not.toBeInTheDocument();
  });

  it('shows loading and error states for history', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify({ message: 'fail' }), { status: 500 })) as unknown as typeof fetch,
    );
    renderDetail();
    expect(await screen.findByText('Loading fix requests…')).toBeInTheDocument();
    expect(await screen.findByText('fail')).toBeInTheDocument();
  });
});

const FIX: FixRequestResponse = {
  id: 'fix1',
  findingId: 'f1',
  projectId: 'p1',
  requestedBy: 'u1',
  status: 'REQUESTED',
  scopeNote: null,
  error: null,
  createdAt: '2026-01-02T00:00:00Z',
  updatedAt: '2026-01-02T00:00:00Z',
};

const PATCH_PROPOSED: PatchResponse = {
  id: 'p1',
  fixRequestId: 'fix1',
  projectId: 'p1',
  diff: 'diff --git a/Main.java b/Main.java\n--- a/Main.java\n+++ b/Main.java\n@@ -1 +1 @@\n-old\n+new',
  filesChanged: 1,
  additions: 1,
  deletions: 1,
  status: 'PROPOSED',
  validationError: null,
  createdAt: '2026-01-03T00:00:00Z',
  updatedAt: '2026-01-03T00:00:00Z',
};

const PATCH_APPLIED: PatchResponse = { ...PATCH_PROPOSED, status: 'APPLIED' };

const EXEC_SUCCESS: ExecutionRunResponse = {
  id: 'e1',
  projectId: 'p1',
  patchId: 'p1',
  status: 'SUCCESS',
  exitCode: 0,
  stdout: 'BUILD SUCCESS',
  stderr: null,
  durationMs: 1200,
  buildStatus: 'SUCCESS',
  createdAt: '2026-01-04T00:00:00Z',
  updatedAt: '2026-01-04T00:00:00Z',
};

const VERIF_VERIFIED: VerificationRunResponse = {
  id: 'v1',
  patchId: 'p1',
  executionRunId: 'e1',
  buildStatus: 'SUCCESS',
  testsTotal: 3,
  testsPassed: 3,
  testsFailed: 0,
  testsSkipped: 0,
  verdict: 'VERIFIED',
  logRef: 'BUILD SUCCESS',
  durationMs: 1300,
  createdAt: '2026-01-05T00:00:00Z',
  updatedAt: '2026-01-05T00:00:00Z',
};

const notFound = () => new Response(JSON.stringify({ message: 'Not found' }), { status: 404 });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

function renderWorkflow(fix: FixRequestResponse = FIX, pollMs = 3000) {
  return render(
    <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
      <FixWorkflow fixRequest={fix} pollMs={pollMs} />
    </AuthProvider>,
  );
}

describe('FixWorkflow (apply → execute → verify)', () => {
  it('shows empty patch state with propose action for REQUESTED fix', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => notFound()) as unknown as typeof fetch,
    );
    renderWorkflow();
    expect(await screen.findByText('No patch yet for this fix request.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Propose patch' })).toBeInTheDocument();
  });

  it('runs propose → apply → execute → verify to VERIFIED', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const target = String(url);
        const method = init?.method ?? 'GET';
        if (target.includes('/fix-requests/fix1/patch')) {
          return method === 'POST' ? json(PATCH_PROPOSED, 201) : notFound();
        }
        if (target.includes('/patches/p1/apply') && method === 'POST') {
          return json(PATCH_APPLIED);
        }
        if (target.includes('/execute') && method === 'POST') {
          return json(EXEC_SUCCESS, 202);
        }
        if (target.includes('/executions/e1/verification')) {
          return method === 'POST' ? json(VERIF_VERIFIED, 201) : notFound();
        }
        return notFound();
      }) as unknown as typeof fetch,
    );
    renderWorkflow();
    await screen.findByText('No patch yet for this fix request.');

    fireEvent.click(screen.getByRole('button', { name: 'Propose patch' }));
    expect(await screen.findByText('PROPOSED')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Apply patch' }));
    expect(await screen.findByText('APPLIED')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Run in sandbox' }));
    const workflow = screen.getByLabelText('Fix apply execute verify workflow');
    expect(await within(workflow).findByText('SUCCESS')).toBeInTheDocument();
    expect(await within(workflow).findByText('exit 0')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Verify' }));
    expect(await screen.findByText('VERIFIED')).toBeInTheDocument();
    expect(screen.getByText(/tests 3\/3 passed/)).toBeInTheDocument();
  });

  it('shows REJECTED verdict with backend policy explanation', async () => {
    const rejected: VerificationRunResponse = { ...VERIF_VERIFIED, verdict: 'REJECTED' };
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request) => {
        const target = String(url);
        if (target.includes('/fix-requests/fix1/patch')) return json(PATCH_APPLIED);
        if (target.includes('/projects/p1/executions')) return json([EXEC_SUCCESS]);
        if (target.includes('/executions/e1/verification')) return json(rejected);
        return notFound();
      }) as unknown as typeof fetch,
    );
    renderWorkflow();
    expect(await screen.findByText('REJECTED')).toBeInTheDocument();
    expect(screen.getByText(/Rejected by backend policy/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Verify' })).not.toBeInTheDocument();
  });

  it('shows patch load failure with retry', async () => {
    let calls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request) => {
        const target = String(url);
        if (target.includes('/fix-requests/fix1/patch')) {
          calls += 1;
          return calls === 1
            ? new Response(JSON.stringify({ message: 'boom' }), { status: 500 })
            : notFound();
        }
        return notFound();
      }) as unknown as typeof fetch,
    );
    renderWorkflow();
    expect(await screen.findByText('boom')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No patch yet for this fix request.')).toBeInTheDocument();
  });

  it('polls an active execution until it completes', async () => {
    let polls = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const target = String(url);
        const method = init?.method ?? 'GET';
        if (target.includes('/fix-requests/fix1/patch')) return json(PATCH_APPLIED);
        if (target.includes('/projects/p1/executions')) return json([]);
        if (target.includes('/execute') && method === 'POST') {
          return json({ ...EXEC_SUCCESS, status: 'RUNNING', exitCode: null }, 202);
        }
        if (target.endsWith('/executions/e1') && method === 'GET') {
          polls += 1;
          return json(polls < 2 ? { ...EXEC_SUCCESS, status: 'RUNNING', exitCode: null } : EXEC_SUCCESS);
        }
        if (target.includes('/executions/e1/verification')) return notFound();
        return notFound();
      }) as unknown as typeof fetch,
    );
    renderWorkflow(FIX, 20);
    await screen.findByText('No executions yet for this patch.');
    fireEvent.click(screen.getByRole('button', { name: 'Run in sandbox' }));
    expect(await screen.findByText('Execution in progress…')).toBeInTheDocument();
    const workflow = screen.getByLabelText('Fix apply execute verify workflow');
    expect(await within(workflow).findByText('SUCCESS')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });
});
