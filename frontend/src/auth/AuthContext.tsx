import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { ApiClient } from '../api/client';
import { login as apiLogin, logout as apiLogout, me as apiMe, register as apiRegister } from '../api/auth';
import type { UserResponse } from '../api/types';

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

// Module-level so `apiClient()` (used by pages like GeneratePage that call
// authenticated endpoints outside the AuthProvider's own callbacks) always
// gets the *same* instance the provider wired up — so a 401 from any of
// those calls still triggers onUnauthorized and clears the session.
let sharedClient: ApiClient | null = null;

/**
 * Session tokens persist in localStorage so a page reload keeps the user
 * signed in (ADR-010, user-approved). On startup the profile is re-fetched
 * with the stored access token; an invalid/expired token clears the session.
 * Tests inject `initial` state and skip the restore path.
 *
 * There is no refresh-token endpoint on the backend yet, so an expired
 * access token cannot be silently renewed. Instead the shared ApiClient's
 * `onUnauthorized` hook clears the session the moment any request 401s, so
 * every screen fails the same way (redirect to /login) instead of leaving a
 * stale "logged in" navbar next to a dead token, as happened on /generate.
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

  // Kept in a ref so the ApiClient instance (created once) always calls the
  // latest handler without needing to be recreated on every render.
  const handleUnauthorizedRef = useRef<() => void>(() => {});
  handleUnauthorizedRef.current = () => {
    clearStoredSession();
    setToken(null);
    setRefreshToken(null);
    setUser(null);
  };

  const [client] = useState(() => {
    if (!sharedClient) {
      sharedClient = new ApiClient({
        onUnauthorized: () => handleUnauthorizedRef.current(),
      });
    }
    return sharedClient;
  });

  const login = useCallback(
    async (email: string, password: string) => {
      const tokens = await apiLogin(client, email, password);
      const profile = await apiMe(client, tokens.accessToken);
      setToken(tokens.accessToken);
      setRefreshToken(tokens.refreshToken);
      setUser(profile);
      persistSession(tokens.accessToken, tokens.refreshToken);
    },
    [client],
  );

  const register = useCallback(
    async (email: string, password: string, displayName?: string) => {
      const tokens = await apiRegister(client, email, password, displayName);
      const profile = await apiMe(client, tokens.accessToken);
      setToken(tokens.accessToken);
      setRefreshToken(tokens.refreshToken);
      setUser(profile);
      persistSession(tokens.accessToken, tokens.refreshToken);
    },
    [client],
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
  }, [client, token, refreshToken]);

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
  }, [client, initial, token, user]);

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

/**
 * Returns the single ApiClient instance wired to this app's AuthProvider,
 * so any 401 from an authenticated call (e.g. GeneratePage's
 * createGeneration/startGeneration/downloadGeneration) clears the session
 * and redirects to /login exactly like a 401 hit through the login/me path.
 * Falls back to an unwired client only if called before AuthProvider mounts
 * (should not happen in the real app tree).
 */
export function apiClient(): ApiClient {
  return sharedClient ?? new ApiClient({});
}