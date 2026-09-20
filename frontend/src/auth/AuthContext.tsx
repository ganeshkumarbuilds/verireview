import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { ApiClient } from '../api/client';
import { login as apiLogin, logout as apiLogout, me as apiMe, register as apiRegister } from '../api/auth';
import type { UserResponse } from '../api/types';

const client = new ApiClient({});

/** localStorage keys for the persisted session (ADR-010). */
const ACCESS_TOKEN_KEY = 'verireview.accessToken';
const REFRESH_TOKEN_KEY = 'verireview.refreshToken';

function readStored(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function persistSession(accessToken: string, refreshToken: string | null): void {
  try {
    window.localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
    if (refreshToken) {
      window.localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
    } else {
      window.localStorage.removeItem(REFRESH_TOKEN_KEY);
    }
  } catch {
    // Storage unavailable (private mode): session stays in-memory only.
  }
}

function clearStoredSession(): void {
  try {
    window.localStorage.removeItem(ACCESS_TOKEN_KEY);
    window.localStorage.removeItem(REFRESH_TOKEN_KEY);
  } catch {
    // Ignore: nothing persisted.
  }
}

interface AuthState {
  token: string | null;
  refreshToken: string | null;
  user: UserResponse | null;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName?: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Session tokens persist in localStorage so a page reload keeps the user
 * signed in (ADR-010, user-approved). On startup the profile is re-fetched
 * with the stored access token; an invalid/expired token clears the session.
 * Tests inject `initial` state and skip the restore path.
 */
export function AuthProvider({
  children,
  initial,
}: {
  children: ReactNode;
  initial?: Pick<AuthState, 'token' | 'refreshToken' | 'user'>;
}) {
  const [token, setToken] = useState<string | null>(
    () => initial?.token ?? readStored(ACCESS_TOKEN_KEY),
  );
  const [refreshToken, setRefreshToken] = useState<string | null>(
    () => initial?.refreshToken ?? readStored(REFRESH_TOKEN_KEY),
  );
  const [user, setUser] = useState<UserResponse | null>(initial?.user ?? null);

  const login = useCallback(async (email: string, password: string) => {
    const tokens = await apiLogin(client, email, password);
    const profile = await apiMe(client, tokens.accessToken);
    setToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    setUser(profile);
    persistSession(tokens.accessToken, tokens.refreshToken);
  }, []);

  const register = useCallback(
    async (email: string, password: string, displayName?: string) => {
      const tokens = await apiRegister(client, email, password, displayName);
      const profile = await apiMe(client, tokens.accessToken);
      setToken(tokens.accessToken);
      setRefreshToken(tokens.refreshToken);
      setUser(profile);
      persistSession(tokens.accessToken, tokens.refreshToken);
    },
    [],
  );

  const logout = useCallback(async () => {
    try {
      if (token && refreshToken) {
        await apiLogout(client, token, refreshToken);
      }
    } finally {
      clearStoredSession();
      setToken(null);
      setRefreshToken(null);
      setUser(null);
    }
  }, [token, refreshToken]);

  // Restore the profile for a persisted session (real app only: tests pass
  // `initial` and manage state explicitly).
  useEffect(() => {
    if (initial !== undefined || !token || user) {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const profile = await apiMe(client, token);
        if (!cancelled) {
          setUser(profile);
        }
      } catch {
        if (!cancelled) {
          clearStoredSession();
          setToken(null);
          setRefreshToken(null);
          setUser(null);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [initial, token, user]);

  const value = useMemo(
    () => ({ token, refreshToken, user, login, register, logout }),
    [token, refreshToken, user, login, register, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) {
    throw new Error('useAuth must be used inside AuthProvider.');
  }
  return state;
}

/** Redirects anonymous visitors to login. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { token } = useAuth();
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

export function apiClient(): ApiClient {
  return client;
}
