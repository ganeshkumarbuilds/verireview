import type { ApiEnvelope } from './types';

/**
 * Backend API client (Phase 5). The backend returns plain DTO JSON (no
 * envelope); errors surface as HTTP status + Boot's default error body.
 * No token storage here — callers pass the auth header explicitly.
 *
 * Rule (ARCHITECTURE.md): the frontend calls the Spring Boot backend only
 * (`/api/v1`), never the AI service or sandbox directly.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: unknown;

  constructor(status: number, code: string, message: string, details: unknown = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export interface ApiClientOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  /**
   * Called once whenever any request comes back 401. AuthContext registers
   * this to clear the stored session and redirect to /login — so an expired
   * or invalid token fails the same way everywhere instead of surfacing as
   * a raw "Request failed with status 401." on whichever screen triggered it.
   */
  onUnauthorized?: () => void;
}

const DEFAULT_BASE_URL = 'http://localhost:8080/api/v1';

export class ApiClient {
  readonly baseUrl: string;
  private readonly fetchImpl?: typeof fetch;
  private readonly onUnauthorized?: () => void;

  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '');
    // Resolved per call (not in the constructor) so tests can stub fetch.
    this.fetchImpl = options.fetchImpl;
    this.onUnauthorized = options.onUnauthorized;
  }

  /** Joins a resource path onto the configured API base URL. */
  buildUrl(path: string): string {
    return `${this.baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
  }

  /** JSON request; throws ApiError on non-2xx. */
  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers);
    const isForm = init.body instanceof FormData;
    if (!isForm && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    const fetchImpl = this.fetchImpl ?? fetch;
    const response = await fetchImpl(this.buildUrl(path), { ...init, headers });
    if (response.status === 401) {
      this.onUnauthorized?.();
    }
    return parseBody<T>(response);
  }
}

/** Reads plain DTO JSON or throws ApiError (status + backend message).
 *  Empty 2xx bodies (202 accepted, 204 no content) resolve as undefined. */
export async function parseBody<T>(response: Response): Promise<T> {
  let body: ApiEnvelope<T> | unknown = null;
  try {
    body = await response.json();
  } catch {
    body = null;
  }
  if (body === null || body === undefined) {
    if (response.ok) {
      return undefined as T;
    }
    throw new ApiError(
      response.status,
      `HTTP_${response.status}`,
      `Request failed with status ${response.status}.`,
    );
  }
  if (!response.ok) {
    const err = body as { message?: string; code?: string };
    throw new ApiError(
      response.status,
      err.code ?? `HTTP_${response.status}`,
      err.message ?? `Request failed with status ${response.status}.`,
    );
  }
  return body as T;
}

/** Bearer header helper; token lives in AuthContext (memory only). */
export function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}