import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './AuthContext';
import { draftKey } from '../features/generate/draftStorage';

function LogoutProbe() {
  const { logout } = useAuth();
  return <button type="button" onClick={() => void logout()}>Log out</button>;
}

describe('AuthContext wizard draft lifecycle', () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('clears wizard drafts on logout', () => {
    window.localStorage.setItem(draftKey('user-a'), '{}');
    render(
      <AuthProvider initial={{
        token: 'tok',
        refreshToken: null,
        user: {
          id: 'user-a',
          email: 'a@example.com',
          displayName: null,
          roles: [],
          enabled: true,
          createdAt: '2026-01-01T00:00:00Z',
        },
      }}>
        <LogoutProbe />
      </AuthProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Log out' }));
    expect(window.localStorage.getItem(draftKey('user-a'))).toBeNull();
  });
});
