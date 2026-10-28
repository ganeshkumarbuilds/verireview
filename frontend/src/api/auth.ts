import { ApiError, type ApiClient } from './client';
import { bearer } from './client';
import type { TokenResponse, UserResponse } from './types';

/** Phase 3 auth endpoints (API_DESIGN §2). Token storage stays in AuthContext. */
export async function login(
  client: ApiClient,
  email: string,
  password: string,
): Promise<TokenResponse> {
  return authRequest(client, '/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function register(
  client: ApiClient,
  email: string,
  password: string,
  displayName?: string,
): Promise<TokenResponse> {
  return authRequest(client, '/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, displayName }),
  });
}

async function authRequest(
  client: ApiClient,
  path: string,
  init: RequestInit,
): Promise<TokenResponse> {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      return await client.request<TokenResponse>(path, init, { timeoutMs: 90_000 });
    } catch (error) {
      const retryable =
        !(error instanceof ApiError) ||
        [502, 503, 504].includes(error.status);
      if (!retryable || attempt === 1) {
        throw error;
      }
    }
  }
  throw new Error('Authentication request failed.');
}

export async function me(client: ApiClient, token: string): Promise<UserResponse> {
  return client.request<UserResponse>('/auth/me', { headers: bearer(token) });
}

export async function logout(client: ApiClient, token: string, refreshToken: string): Promise<void> {
  await client.request<void>('/auth/logout', {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify({ refreshToken }),
  });
}

/** Requests a password-reset email. Always succeeds from the caller's view
 *  (the backend never reveals whether the account exists). */
export async function requestPasswordReset(client: ApiClient, email: string): Promise<void> {
  await client.request<void>('/auth/password-reset/request', {
    method: 'POST',
    body: JSON.stringify({ email }),
  });
}

export async function refreshToken(client: ApiClient, refreshToken: string): Promise<TokenResponse> {
  return client.request<TokenResponse>('/auth/refresh', {
    method: 'POST',
    body: JSON.stringify({ refreshToken }),
  });
}
