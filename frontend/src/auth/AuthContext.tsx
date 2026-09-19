import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { ApiClient } from '../api/client';
import { login as apiLogin, logout as apiLogout, me as apiMe, register as apiRegister } from '../api/auth';
import type { UserResponse } from '../api/types';

const client = new ApiClient({});

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
 * In-memory session (SECURITY_DESIGN §2 prefers memory over persisted
 * storage). Silent refresh is a later concern; a 401 elsewhere drops the
 * session and RequireAuth redirects to login.
 */
export function AuthProvider({
  children,
  initial,
}: {
  children: ReactNode;
  initial?: Pick<AuthState, 'token' | 'refreshToken' | 'user'>;
}) {
  const [token, setToken] = useState<string | null>(initial?.token ?? null);
  const [refreshToken, setRefreshToken] = useState<string | null>(
    initial?.refreshToken ?? null,
  );
  const [user, setUser] = useState<UserResponse | null>(initial?.user ?? null);

  const login = useCallback(async (email: string, password: string) => {
    const tokens = await apiLogin(client, email, password);
    const profile = await apiMe(client, tokens.accessToken);
    setToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    setUser(profile);
  }, []);

  const register = useCallback(
    async (email: string, password: string, displayName?: string) => {
      const tokens = await apiRegister(client, email, password, displayName);
      const profile = await apiMe(client, tokens.accessToken);
      setToken(tokens.accessToken);
      setRefreshToken(tokens.refreshToken);
      setUser(profile);
    },
    [],
  );

  const logout = useCallback(async () => {
    try {
      if (token && refreshToken) {
        await apiLogout(client, token, refreshToken);
      }
    } finally {
      setToken(null);
      setRefreshToken(null);
      setUser(null);
    }
  }, [token, refreshToken]);

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
