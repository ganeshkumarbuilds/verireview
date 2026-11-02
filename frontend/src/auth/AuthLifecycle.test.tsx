import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './AuthContext';

function jwt(exp: number): string {
  const payload = btoa(JSON.stringify({ exp }));
  return `header.${payload}.signature`;
}

function Probe() {
  const { status, user, notice, retry } = useAuth();
  return (
    <>
      <span data-testid="status">{status}</span>
      <span data-testid="name">{user?.displayName ?? ''}</span>
      <span>{notice}</span>
      <button type="button" onClick={retry}>Retry</button>
    </>
  );
}

function renderProvider() {
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

describe('AuthProvider session lifecycle', () => {
  it('discards an expired token before making a validation request', async () => {
    window.localStorage.setItem('verireview.accessToken', jwt(1));
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderProvider();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(window.localStorage.getItem('verireview.accessToken')).toBeNull();
    expect(screen.getByText('Your session expired. Please log in again.')).toBeInTheDocument();
  });

  it('validates a token and exposes the backend identity', async () => {
    window.localStorage.setItem('verireview.accessToken', jwt(Math.floor(Date.now() / 1000) + 900));
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({
        id: 'u1',
        email: 'person@example.com',
        displayName: 'Real Person',
        roles: ['USER'],
        enabled: true,
        createdAt: '2026-01-01T00:00:00Z',
      }),
      { status: 200 },
    )));
    renderProvider();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated'));
    expect(screen.getByTestId('name')).toHaveTextContent('Real Person');
  });

  it('keeps a valid token when the backend is unreachable', async () => {
    const token = jwt(Math.floor(Date.now() / 1000) + 900);
    window.localStorage.setItem('verireview.accessToken', token);
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('network down');
    }));
    renderProvider();

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('unreachable'));
    expect(window.localStorage.getItem('verireview.accessToken')).toBe(token);
  });

  it('updates this tab when another tab removes the session', async () => {
    const token = jwt(Math.floor(Date.now() / 1000) + 900);
    window.localStorage.setItem('verireview.accessToken', token);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ id: 'u1', email: 'person@example.com', displayName: 'Person' }),
      { status: 200 },
    )));
    renderProvider();
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated'));

    window.localStorage.removeItem('verireview.accessToken');
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'verireview.accessToken',
      oldValue: token,
      newValue: null,
    }));

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'));
  });
});
