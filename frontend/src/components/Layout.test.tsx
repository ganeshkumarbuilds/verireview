import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { AppRoutes } from '../routes';

function renderAt(path: string, authenticated = false) {
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
      screen.getByRole('heading', { name: 'Generate. Review. Fix. Verify.' }),
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
  it('renders the brand, tagline, and primary navigation', () => {
    renderAt('/dashboard');
    expect(screen.getByText('VeriReview')).toBeInTheDocument();
    expect(screen.getByText('Generate. Review. Fix. Verify.')).toBeInTheDocument();
    for (const label of ['Dashboard', 'Projects', 'Review', 'History', 'Login', 'Register']) {
      expect(screen.getByRole('link', { name: label })).toBeInTheDocument();
    }
  });

  it('toggles the sidebar from the navbar button', () => {
    // jsdom applies no stylesheets, so assert the responsive classes directly.
    renderAt('/dashboard');
    const closed = screen.getByRole('complementary', { name: 'Primary' });
    expect(closed.className).toContain('hidden');
    fireEvent.click(screen.getByRole('button', { name: 'Toggle navigation' }));
    expect(screen.getByRole('complementary', { name: 'Primary' }).className).not.toContain(
      'hidden',
    );
  });
});

describe('placeholder pages', () => {
  it.each([
    ['/', 'Generate. Review. Fix. Verify.', false],
    ['/dashboard', 'Dashboard', false],
    ['/projects', 'Projects', true],
    ['/review', 'Login', false],
    ['/history', 'History', false],
    ['/login', 'Login', false],
    ['/register', 'Register', false],
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
});
