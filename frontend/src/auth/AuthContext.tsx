import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Navigate,
  useLocation,
} from 'react-router-dom';
import { ApiClient, ApiError } from '../api/client';
import {
  login as apiLogin,
  logout as apiLogout,
  me as apiMe,
  register as apiRegister,
  refreshToken as apiRefreshToken,
} from '../api/auth';
import type { UserResponse } from '../api/types';
import { clearGenerateDrafts } from '../features/generate/draftStorage';

const ACCESS_TOKEN_KEY = 'verireview.accessToken';
const REFRESH_TOKEN_KEY = 'verireview.refreshToken';
const EXPIRED_MESSAGE_KEY = 'verireview.authMessage';
const VALIDATION_TIMEOUT_MS = 15_000;
const RETRY_DELAYS_MS = [2_000, 4_000, 8_000, 16_000, 24_000, 36_000];
const EXPIRED_MESSAGE = 'Your session expired. Please log in again.';

export type SessionStatus = 'checking' | 'authenticated' | 'anonymous' | 'unreachable';

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
    // Storage unavailable: the in-memory session remains usable.
  }
}

function clearAllVeriReviewStorage(): void {
  try {
    for (let index = window.localStorage.length - 1; index >= 0; index -= 1) {
      const key = window.localStorage.key(index);
      if (key?.startsWith('verireview.')) {
        window.localStorage.removeItem(key);
      }
    }
    clearGenerateDrafts();
  } catch {
    // Storage cleanup is best effort.
  }
}

function rememberExpiredSession(): void {
  try {
    window.sessionStorage.setItem(EXPIRED_MESSAGE_KEY, EXPIRED_MESSAGE);
  } catch {
    // The login screen still renders without session storage.
  }
}

function readExpiredMessage(): string | null {
  try {
    const message = window.sessionStorage.getItem(EXPIRED_MESSAGE_KEY);
    if (message) window.sessionStorage.removeItem(EXPIRED_MESSAGE_KEY);
    return message;
  } catch {
    return null;
  }
}

function tokenExpired(token: string): boolean {
  try {
    const payload = token.split('.')[1];
    if (!payload) return true;
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
      .padEnd(Math.ceil(payload.length / 4) * 4, '=');
    const decoded = JSON.parse(window.atob(normalized)) as { exp?: unknown };
    return typeof decoded.exp !== 'number' || decoded.exp * 1000 <= Date.now();
  } catch {
    return true;
  }
}

function isUnreachable(error: unknown): boolean {
  return !(error instanceof ApiError) || [0, 408, 502, 503, 504].includes(error.status);
}

function redirectToLogin(): void {
  if (window.location.pathname === '/login') return;
  window.history.replaceState({}, '', '/login');
  window.dispatchEvent(new PopStateEvent('popstate'));
}

interface AuthState {
  status: SessionStatus;
  token: string | null;
  refreshToken: string | null;
  user: UserResponse | null;
  notice: string | null;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName?: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<boolean>;
  retry: () => void;
  dismissNotice: () => void;
}

const AuthContext = createContext<AuthState | null>(null);
let sharedClient: ApiClient | null = null;
let unauthorizedHandled = false;
let activeUnauthorizedHandler: (() => void) | null = null;

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
  const [status, setStatus] = useState<SessionStatus>(
    initial ? 'authenticated' : 'checking',
  );
  const [notice, setNotice] = useState<string | null>(() => readExpiredMessage());
  const retryTimer = useRef<number | null>(null);
  const validationAttempt = useRef(0);

  const clearSession = useCallback((expired: boolean) => {
    clearAllVeriReviewStorage();
    setToken(null);
    setRefreshToken(null);
    setUser(null);
    setStatus('anonymous');
    if (expired) {
      rememberExpiredSession();
      setNotice(EXPIRED_MESSAGE);
    }
  }, []);

  const handleUnauthorizedRef = useRef<() => void>(() => {});
  handleUnauthorizedRef.current = () => {
    if (unauthorizedHandled) return;
    unauthorizedHandled = true;
    clearSession(true);
    redirectToLogin();
    window.setTimeout(() => {
      unauthorizedHandled = false;
    }, 1_000);
  };
  activeUnauthorizedHandler = handleUnauthorizedRef.current;

  const [client] = useState(() => {
    if (!sharedClient) {
      sharedClient = new ApiClient({
        onUnauthorized: () => activeUnauthorizedHandler?.(),
      });
    }
    return sharedClient;
  });

  const validate = useCallback(async (tokenToValidate = token) => {
    if (!tokenToValidate) {
      setStatus('anonymous');
      return;
    }
    if (tokenExpired(tokenToValidate)) {
      clearSession(true);
      return;
    }
    setStatus('checking');
    try {
      const profile = await apiMe(client, tokenToValidate, VALIDATION_TIMEOUT_MS);
      setUser(profile);
      setStatus('authenticated');
      validationAttempt.current = 0;
    } catch (error) {
      if (error instanceof ApiError && [401, 403].includes(error.status)) {
        clearSession(true);
        return;
      }
      if (isUnreachable(error)) {
        setStatus('unreachable');
        const attempt = validationAttempt.current;
        if (attempt < RETRY_DELAYS_MS.length) {
          validationAttempt.current += 1;
          retryTimer.current = window.setTimeout(() => void validate(), RETRY_DELAYS_MS[attempt]);
        }
        return;
      }
      clearSession(false);
    }
  }, [clearSession, client, token]);

  useEffect(() => {
    if (initial !== undefined) return undefined;
    void validate();
    return () => {
      if (retryTimer.current !== null) window.clearTimeout(retryTimer.current);
    };
  }, [initial, validate]);

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (!event.key?.startsWith('verireview.')) return;
      const nextToken = readStored(ACCESS_TOKEN_KEY);
      const nextRefresh = readStored(REFRESH_TOKEN_KEY);
      if (!nextToken) {
        clearSession(false);
      } else if (nextToken !== token) {
        setToken(nextToken);
        setRefreshToken(nextRefresh);
        setUser(null);
        validationAttempt.current = 0;
        void validate(nextToken);
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [clearSession, token, validate]);

  const login = useCallback(async (email: string, password: string) => {
    const tokens = await apiLogin(client, email, password);
    clearAllVeriReviewStorage();
    persistSession(tokens.accessToken, tokens.refreshToken);
    setToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    const profile = await apiMe(client, tokens.accessToken, VALIDATION_TIMEOUT_MS);
    setUser(profile);
    setStatus('authenticated');
    setNotice(null);
  }, [client]);

  const register = useCallback(async (email: string, password: string, displayName?: string) => {
    const tokens = await apiRegister(client, email, password, displayName);
    clearAllVeriReviewStorage();
    persistSession(tokens.accessToken, tokens.refreshToken);
    setToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    const profile = await apiMe(client, tokens.accessToken, VALIDATION_TIMEOUT_MS);
    setUser(profile);
    setStatus('authenticated');
    setNotice(null);
  }, [client]);

  const logout = useCallback(async () => {
    client.cancelInFlight();
    try {
      if (token && refreshToken) {
        await apiLogout(client, token, refreshToken);
      }
    } catch {
      // Local logout must complete even when the server is unavailable.
    } finally {
      clearSession(false);
      redirectToLogin();
    }
  }, [clearSession, client, refreshToken, token]);

  const refresh = useCallback(async () => {
    if (!refreshToken) return false;
    try {
      const tokens = await apiRefreshToken(client, refreshToken);
      persistSession(tokens.accessToken, tokens.refreshToken);
      setToken(tokens.accessToken);
      setRefreshToken(tokens.refreshToken);
      await validate();
      return true;
    } catch {
      return false;
    }
  }, [client, refreshToken, validate]);

  const retry = useCallback(() => {
    if (retryTimer.current !== null) window.clearTimeout(retryTimer.current);
    validationAttempt.current = 0;
    void validate();
  }, [validate]);

  const dismissNotice = useCallback(() => setNotice(null), []);

  const value = useMemo(
    () => ({
      status,
      token,
      refreshToken,
      user,
      notice,
      login,
      register,
      logout,
      refresh,
      retry,
      dismissNotice,
    }),
    [dismissNotice, login, logout, notice, refresh, refreshToken, register, retry, status, token, user],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error('useAuth must be used inside AuthProvider.');
  return state;
}

function AuthChecking() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center" role="status">
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-indigo-200 border-t-indigo-600" />
      <span className="sr-only">Checking your session…</span>
    </div>
  );
}

export function UnreachablePage() {
  const { retry } = useAuth();
  return (
    <div className="mx-auto flex min-h-[60vh] max-w-lg flex-col items-center justify-center rounded-2xl border border-amber-200 bg-amber-50 p-8 text-center">
      <h1 className="text-xl font-bold text-slate-900">Can&apos;t reach the server</h1>
      <p className="mt-2 text-sm text-slate-600">The server is waking up…</p>
      <button type="button" onClick={retry} className="mt-5 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white">
        Retry
      </button>
    </div>
  );
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'checking') return <AuthChecking />;
  if (status === 'unreachable') return <UnreachablePage />;
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
  }
  return <>{children}</>;
}

export function RedirectIfAuthenticated({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  if (status === 'checking') return <AuthChecking />;
  if (status === 'unreachable') return <UnreachablePage />;
  return <>{children}</>;
}

export function apiClient(): ApiClient {
  return sharedClient ?? new ApiClient({});
}

export function pollingApiClient(): ApiClient {
  return new ApiClient({});
}
