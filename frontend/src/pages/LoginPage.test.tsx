import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { LoginPage } from './LoginPage';

afterEach(() => {
  vi.unstubAllGlobals();
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

  it('requests a password reset from the forgot-password panel', async () => {
    const calls: string[] = [];
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
