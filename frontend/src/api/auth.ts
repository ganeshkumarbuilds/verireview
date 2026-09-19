import type { ApiClient } from './client';
import { bearer } from './client';
import type { TokenResponse, UserResponse } from './types';

/** Phase 3 auth endpoints (API_DESIGN §2). Token storage stays in AuthContext. */
export async function login(
  client: ApiClient,
  email: string,
  password: string,
): Promise<TokenResponse> {
  return client.request<TokenResponse>('/auth/login', {
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
  return client.request<TokenResponse>('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, displayName }),
  });
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
