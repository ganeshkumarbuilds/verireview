import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { FindingDetail } from './FindingDetail';
import type { FindingResponse } from '../api/types';

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
      vi.fn(async () =>
        new Response(
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
        ),
      ) as unknown as typeof fetch,
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
