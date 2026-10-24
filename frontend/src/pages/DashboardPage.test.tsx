import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { DashboardPage } from './DashboardPage';

/** Minimal dashboard summary body matching the simplified endpoint shape. */
function summaryBody(overrides: Record<string, unknown> = {}) {
  return {
    totalFilesVerified: 0,
    totalIssuesFound: 0,
    totalIssuesFixed: 0,
    projects: [],
    ...overrides,
  };
}

function installFetch(
  handler: (url: string, init?: RequestInit) => Response | Promise<Response>,
) {
  const mock = vi.fn(async (url: string | URL | Request, init?: RequestInit) =>
    handler(String(url), init),
  );
  vi.stubGlobal('fetch', mock as unknown as typeof fetch);
  return mock;
}

function renderDashboard() {
  render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <AuthProvider initial={{ token: 'tok', refreshToken: null, user: null }}>
        <DashboardPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('DashboardPage', () => {
  it('shows the simplified layout with zeros and an empty projects state', async () => {
    installFetch(() => new Response(JSON.stringify(summaryBody()), { status: 200 }));
    renderDashboard();

    await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
    expect(
      screen.getByText(/Generate a new project or review existing code/),
    ).toBeInTheDocument();

    // Header primary action points at the review/upload page.
    expect(screen.getByRole('link', { name: /Review existing code/i })).toHaveAttribute(
      'href',
      '/review',
    );

    // Exactly two product features, each with a working destination.
    for (const title of ['Generate', 'Review']) {
      expect(screen.getByRole('heading', { name: title })).toBeInTheDocument();
    }
    expect(screen.queryByRole('heading', { name: 'Fix' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Verify' })).not.toBeInTheDocument();

    // Overall tiles render 0 cleanly for missing data. The summary request
    // resolves asynchronously, so wait for the loaded state instead of racing
    // it -- the empty-projects assertions below flaked under full-suite load.
    await waitFor(() => expect(screen.getByText('Files verified')).toBeInTheDocument());
    expect(screen.getByText('Issues found')).toBeInTheDocument();
    expect(screen.getByText('Issues fixed')).toBeInTheDocument();
    expect(screen.getAllByText('0')).toHaveLength(3);

    // Empty projects state with a create/upload action.
    await waitFor(() => expect(screen.getByText('No projects yet')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: /Add code to review/i })).toHaveAttribute(
      'href',
      '/review',
    );
  });

  it('renders totals and the project table when data is present', async () => {
    installFetch(() =>
      new Response(
        JSON.stringify(
          summaryBody({
            totalFilesVerified: 42,
            totalIssuesFound: 10,
            totalIssuesFixed: 7,
            projects: [
              {
                id: 'p1',
                name: 'demo',
                filesVerified: 42,
                issuesFound: 10,
                issuesFixed: 7,
                lastActivityAt: new Date().toISOString(),
              },
              {
                id: 'p2',
                name: 'empty',
                filesVerified: 0,
                issuesFound: 0,
                issuesFixed: 0,
                lastActivityAt: null,
              },
            ],
          }),
        ),
        { status: 200 },
      ),
    );
    renderDashboard();

    const overall = screen.getByRole('region', { name: 'Overall' });
    await waitFor(() => expect(within(overall).getByText('42')).toBeInTheDocument());
    expect(within(overall).getByText('10')).toBeInTheDocument();
    expect(within(overall).getByText('7')).toBeInTheDocument();

    const demoRow = screen.getByRole('row', { name: /demo/ });
    expect(within(demoRow).getByText('10')).toBeInTheDocument();
    expect(within(demoRow).getByText('7')).toBeInTheDocument();

    const emptyRow = screen.getByRole('row', { name: /empty/ });
    expect(within(emptyRow).getByText('—')).toBeInTheDocument();

    // Row Review button opens Review with the project preselected.
    expect(within(demoRow).getByRole('link', { name: /Review demo/i })).toHaveAttribute(
      'href',
      '/review?project=p1',
    );
  });

  it('navigates each feature card to an existing route', async () => {
    installFetch(() => new Response(JSON.stringify(summaryBody()), { status: 200 }));
    renderDashboard();

    await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());

    expect(screen.getByRole('link', { name: /Start generation/i })).toHaveAttribute(
      'href',
      '/generate',
    );
    expect(screen.getByRole('link', { name: /Open review/i })).toHaveAttribute('href', '/review');
    expect(screen.queryByRole('link', { name: /Fix findings/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Run verification/i })).not.toBeInTheDocument();
  });

  it('surfaces errors with a working Retry button', async () => {
    let failing = true;
    installFetch(() =>
      failing
        ? new Response(JSON.stringify({ message: 'Boom' }), { status: 500 })
        : new Response(
            JSON.stringify(summaryBody({ totalFilesVerified: 5 })),
            { status: 200 },
          ),
    );
    renderDashboard();

    await waitFor(() => expect(screen.getByText('Boom')).toBeInTheDocument());
    const retry = screen.getByRole('button', { name: /Retry/i });

    failing = false;
    fireEvent.click(retry);

    await waitFor(() => expect(screen.getByText('5')).toBeInTheDocument());
    expect(screen.queryByText('Boom')).not.toBeInTheDocument();
  });

  it('refreshes the summary every 30 seconds while the tab is visible', async () => {
    vi.useFakeTimers();
    const mock = installFetch(
      () => new Response(JSON.stringify(summaryBody({ totalFilesVerified: 1 })), { status: 200 }),
    );
    renderDashboard();

    await vi.waitFor(() => expect(mock).toHaveBeenCalledTimes(1));

    await vi.advanceTimersByTimeAsync(30_000);
    await vi.waitFor(() => expect(mock).toHaveBeenCalledTimes(2));
  });

  it('does not open an SSE connection', async () => {
    const eventSourceCtor = vi.fn();
    vi.stubGlobal('EventSource', eventSourceCtor);
    installFetch(() => new Response(JSON.stringify(summaryBody()), { status: 200 }));
    renderDashboard();

    await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
    expect(eventSourceCtor).not.toHaveBeenCalled();
  });
});
