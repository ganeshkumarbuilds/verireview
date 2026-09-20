import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { LoginPage } from './LoginPage';

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

describe('LoginPage', () => {
  it('signs in against the backend', async () => {
    const calls: { url: string; body: string }[] = [];
    vi.stubGlobal(
      'fetch',
      (async (url: string | URL | Request, init?: RequestInit) => {
        const target = String(url);
        calls.push({ url: target, body: String(init?.body ?? '') });
        if (target.endsWith('/auth/login')) {
          return new Response(
            JSON.stringify({ accessToken: 'a', refreshToken: 'r', tokenType: 'Bearer', expiresIn: 900 }),
            { status: 200 },
          );
        }
        return new Response(
          JSON.stringify({ id: 'u1', email: 'dev@example.com', roles: ['USER'] }),
          { status: 200 },
        );
      }) as typeof fetch,
    );
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'dev@example.com' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'correct-horse-99!' } });
    fireEvent.click(screen.getByRole('button', { name: 'Login' }));
    await waitFor(() =>
      expect(calls.some((call) => call.url.endsWith('/auth/login'))).toBe(true),
    );
    expect(calls[0]?.body).toContain('dev@example.com');
  });

  it('requests a password reset from the forgot-password panel', async () => {    const calls: string[] = [];
    vi.stubGlobal(
      'fetch',
      (async (url: string | URL | Request, init?: RequestInit) => {
        calls.push(String(url));
        expect(String(init?.body ?? '')).toContain('dev@example.com');
        return new Response(null, { status: 202 });
      }) as typeof fetch,
    );
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Forgot password?' }));
    fireEvent.change(screen.getByLabelText('Reset email'), {
      target: { value: 'dev@example.com' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send reset link' }));
    await waitFor(() =>
      expect(
        calls.some((url) => url.endsWith('/auth/password-reset/request')),
      ).toBe(true),
    );
    expect(screen.getByText(/reset instructions/)).toBeInTheDocument();
  });
});

describe('LoginPage auth error UX', () => {
  function renderLogin(fetchImpl: (url: string | URL | Request, init?: RequestInit) => Promise<Response>) {
    const calls: string[] = [];
    vi.stubGlobal(
      'fetch',
      (async (url: string | URL | Request, init?: RequestInit) => {
        calls.push(String(url));
        return fetchImpl(url, init);
      }) as typeof fetch,
    );
    render(
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    return { calls };
  }

  function submit() {
    fireEvent.click(screen.getByRole('button', { name: 'Login' }));
  }

  function fill(email: string, password: string) {
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
  }

  const okProfile = () =>
    new Response(JSON.stringify({ id: 'u1', email: 'dev@example.com', roles: ['USER'] }), {
      status: 200,
    });

  it('validates empty email without calling the backend', async () => {
    const { calls } = renderLogin(async () => okProfile());
    fill('', 'secret-1');
    submit();
    await waitFor(() =>
      expect(screen.getByText('Please enter your email address.')).toBeInTheDocument(),
    );
    expect(calls.some((url) => url.endsWith('/auth/login'))).toBe(false);
  });

  it('validates malformed email without calling the backend', async () => {
    const { calls } = renderLogin(async () => okProfile());
    fill('not-an-email', 'secret-1');
    submit();
    await waitFor(() =>
      expect(screen.getByText('Please enter a valid email address.')).toBeInTheDocument(),
    );
    expect(calls.some((url) => url.endsWith('/auth/login'))).toBe(false);
  });

  it('validates empty password without calling the backend', async () => {
    const { calls } = renderLogin(async () => okProfile());
    fill('dev@example.com', '');
    submit();
    await waitFor(() =>
      expect(screen.getByText('Please enter your password.')).toBeInTheDocument(),
    );
    expect(calls.some((url) => url.endsWith('/auth/login'))).toBe(false);
  });

  it.each([401, 400])('maps HTTP %i to invalid credentials without raw status text', async (status) => {
    renderLogin(async (url: string | URL | Request) => {
      if (String(url).endsWith('/auth/login')) {
        return new Response(JSON.stringify({ message: 'Bad credentials' }), { status });
      }
      return okProfile();
    });
    fill('dev@example.com', 'wrong-password');
    submit();
    await waitFor(() =>
      expect(screen.getByText('Invalid email or password.')).toBeInTheDocument(),
    );
    expect(screen.queryByText(/Request failed with status/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Bad credentials/)).not.toBeInTheDocument();
  });

  it('uses the same message for unregistered email and wrong password', async () => {
    async function attempt(): Promise<string | null> {
      vi.unstubAllGlobals();
      renderLogin(async (url: string | URL | Request) => {
        if (String(url).endsWith('/auth/login')) {
          return new Response(JSON.stringify({ message: 'no such user' }), { status: 401 });
        }
        return okProfile();
      });
      fill('dev@example.com', 'wrong-password');
      submit();
      await waitFor(() =>
        expect(screen.getByText('Invalid email or password.')).toBeInTheDocument(),
      );
      return screen.getByRole('alert').textContent;
    }
    const first = await attempt();
    document.body.innerHTML = '';
    const second = await attempt();
    expect(first).toBe('Invalid email or password.');
    expect(second).toBe(first);
  });

  it('maps 403 to the disabled-account message', async () => {
    renderLogin(async (url: string | URL | Request) => {
      if (String(url).endsWith('/auth/login')) {
        return new Response(JSON.stringify({ message: 'disabled' }), { status: 403 });
      }
      return okProfile();
    });
    fill('dev@example.com', 'secret-1');
    submit();
    await waitFor(() =>
      expect(
        screen.getByText('Your account is disabled. Please contact support.'),
      ).toBeInTheDocument(),
    );
  });

  it('maps 429 to the rate-limit message', async () => {
    renderLogin(async (url: string | URL | Request) => {
      if (String(url).endsWith('/auth/login')) {
        return new Response(JSON.stringify({ message: 'slow down' }), { status: 429 });
      }
      return okProfile();
    });
    fill('dev@example.com', 'secret-1');
    submit();
    await waitFor(() =>
      expect(
        screen.getByText('Too many login attempts. Please try again later.'),
      ).toBeInTheDocument(),
    );
  });

  it('maps unreachable backend to the connectivity message', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      renderLogin(async () => {
        throw new TypeError('fetch failed');
      });
      fill('dev@example.com', 'secret-1');
      submit();
      await waitFor(() =>
        expect(
          screen.getByText('Unable to connect to VeriReview. Please try again.'),
        ).toBeInTheDocument(),
      );
      expect(consoleSpy).not.toHaveBeenCalled();
    } finally {
      consoleSpy.mockRestore();
    }
  });

  it('maps other server errors to the generic message', async () => {
    renderLogin(async (url: string | URL | Request) => {
      if (String(url).endsWith('/auth/login')) {
        return new Response(JSON.stringify({ message: 'boom' }), { status: 500 });
      }
      return okProfile();
    });
    fill('dev@example.com', 'secret-1');
    submit();
    await waitFor(() =>
      expect(screen.getByText('Something went wrong. Please try again.')).toBeInTheDocument(),
    );
    expect(screen.queryByText(/boom/)).not.toBeInTheDocument();
  });

  it('never logs passwords or tokens on failure', async () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      renderLogin(async (url: string | URL | Request) => {
        if (String(url).endsWith('/auth/login')) {
          return new Response(JSON.stringify({ message: 'nope' }), { status: 401 });
        }
        return okProfile();
      });
      fill('dev@example.com', 'super-secret-pw');
      submit();
      await waitFor(() =>
        expect(screen.getByText('Invalid email or password.')).toBeInTheDocument(),
      );
      const logged = [...logSpy.mock.calls, ...errSpy.mock.calls].flat().join(' ');
      expect(logged).not.toContain('super-secret-pw');
    } finally {
      logSpy.mockRestore();
      errSpy.mockRestore();
    }
  });
});
