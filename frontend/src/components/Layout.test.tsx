import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { AppRoutes } from '../routes';

/** Stub the API so authenticated pages (dashboard, projects) resolve instantly. */
function installFetch() {
  const mock = vi.fn(
    async () =>
      new Response(
        JSON.stringify({
          totalFilesVerified: 0,
          totalIssuesFound: 0,
          totalIssuesFixed: 0,
          projects: [],
          content: [],
          totalElements: 0,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
  );
  vi.stubGlobal('fetch', mock as unknown as typeof fetch);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderAt(path: string, authenticated = false) {
  if (authenticated) {
    installFetch();
  }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider
        initial={
          authenticated ? { token: 'test-token', refreshToken: null, user: null } : undefined
        }
      >
        <AppRoutes />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('landing page', () => {
  it('opens the homepage with login and get-started actions', () => {
    renderAt('/');
    expect(
      screen.getByRole('heading', { name: 'Generate. Review.' }),
    ).toBeInTheDocument();
    const getStarted = screen.getAllByRole('link', { name: 'Get started' });
    expect(getStarted.length).toBeGreaterThan(0);
    for (const link of getStarted) {
      expect(link).toHaveAttribute('href', '/register');
    }
    const login = screen.getAllByRole('link', { name: 'Log in' });
    expect(login.length).toBeGreaterThan(0);
    for (const link of login) {
      expect(link).toHaveAttribute('href', '/login');
    }
  });
});

describe('shell layout', () => {
  it('renders the brand, tagline, and workspace navigation for a signed-in user', () => {
    renderAt('/dashboard', true);
    expect(screen.getByText('VeriReview')).toBeInTheDocument();
    expect(screen.getByText('Generate. Review.')).toBeInTheDocument();
    for (const label of ['Dashboard', 'Projects', 'History']) {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument();
    }
    expect(screen.queryByRole('link', { name: 'Review' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Logout' })).toBeInTheDocument();
  });

  it('shows only the auth actions to a signed-out visitor', () => {
    renderAt('/no-such-route');
    expect(screen.getByRole('link', { name: 'Login' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Register' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument();
  });

  it('toggles the mobile navigation menu from the navbar button', () => {
    renderAt('/dashboard', true);
    expect(screen.queryByRole('navigation', { name: 'Mobile' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Toggle navigation' }));
    expect(screen.getByRole('navigation', { name: 'Mobile' })).toBeInTheDocument();
  });

  it('logs out back to the home page', async () => {
    renderAt('/dashboard', true);
    fireEvent.click(screen.getByRole('button', { name: 'Logout' }));
    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Generate. Review.' }),
      ).toBeInTheDocument(),
    );
  });
});

describe('route guards', () => {
  it('redirects an anonymous visitor from a protected route to the login form', () => {
    renderAt('/dashboard');
    expect(screen.getByRole('form', { name: 'Login form' })).toBeInTheDocument();
  });

  it.each(['/login', '/register'])(
    'redirects a signed-in visitor away from %s to the dashboard',
    async (path) => {
      renderAt(path, true);
      await waitFor(() =>
        expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument(),
      );
      expect(screen.queryByRole('form', { name: 'Login form' })).not.toBeInTheDocument();
    },
  );
});

describe('placeholder pages', () => {
  it.each([
    ['/', 'Generate. Review.', false],
    ['/dashboard', 'Login', false],
    ['/dashboard', 'Dashboard', true],
    ['/projects', 'Projects', true],
    ['/review', 'Login', false],
    ['/generate', 'Login', false],
    ['/fix', 'Login', false],
    ['/verify', 'Login', false],
    ['/review', 'Review', true],
    ['/generate', 'Generate', true],
    ['/fix', 'Review', true],
    ['/verify', 'Review', true],
    ['/history', 'Login', false],
    ['/login', 'Login', false],
    ['/register', 'Register', false],
    ['/register', 'Dashboard', true],
    ['/no-such-route', 'Page not found', false],
  ])('renders %s', (path, heading, authenticated) => {
    renderAt(path, authenticated as boolean);
    expect(screen.getByRole('heading', { name: heading as string })).toBeInTheDocument();
  });

  it('shows the login form placeholder', () => {
    renderAt('/login');
    expect(screen.getByRole('form', { name: 'Login form' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Login' })).toBeInTheDocument();
  });

  it('redirects old project-workspace bookmarks to Review for that project', async () => {
    renderAt('/projects/p1', true);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Review' })).toBeInTheDocument());
    expect(screen.getByRole('link', { name: /Back to Dashboard/i })).toHaveAttribute(
      'href',
      '/dashboard',
    );
  });

  it('highlights Projects on nested project routes', async () => {
    renderAt('/projects/p1/reviews/r1/findings/f1', true);
    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'Projects' })).toHaveAttribute(
        'aria-current',
        'page',
      ),
    );
  });
});
