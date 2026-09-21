import type { ApiClient } from './client';
import { ApiError, bearer } from './client';
import type {
  CreateGenerationInput,
  GenerationResponse,
  StartGenerationInput,
  UpdateGenerationInput,
} from './generationTypes';
import type { Page } from './types';

/** Generation endpoints. The create body carries secrets once (TLS only);
 *  nothing secret is ever written to localStorage or URLs by callers. */
export async function createGeneration(
  client: ApiClient,
  token: string,
  input: CreateGenerationInput,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>('/generations', {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify(input),
  });
}

export async function getGeneration(
  client: ApiClient,
  token: string,
  id: string,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>(`/generations/${encodeURIComponent(id)}`, {
    headers: bearer(token),
  });
}

/** Edits a draft's task and configuration. Drafts only (409 once dispatched). */
export async function updateGeneration(
  client: ApiClient,
  token: string,
  id: string,
  input: UpdateGenerationInput,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>(`/generations/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: bearer(token),
    body: JSON.stringify(input),
  });
}

/** Starts a draft: moves DRAFT → READY and dispatches the runner. */
export async function startGeneration(
  client: ApiClient,
  token: string,
  id: string,
  input: StartGenerationInput,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>(
    `/generations/${encodeURIComponent(id)}/start`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify(input),
    },
  );
}

/** Latest generation linked to a project (404 when the project has none). */
export async function getProjectGeneration(
  client: ApiClient,
  token: string,
  projectId: string,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>(
    `/projects/${encodeURIComponent(projectId)}/generation`,
    { headers: bearer(token) },
  );
}

export async function listGenerations(
  client: ApiClient,
  token: string,
  params: { page?: number; size?: number } = {},
): Promise<Page<GenerationResponse>> {
  const query = Object.entries(params)
    .filter((entry): entry is [string, number] => entry[1] !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
  return client.request<Page<GenerationResponse>>(
    `/generations${query ? `?${query}` : ''}`,
    { headers: bearer(token) },
  );
}

export function isTerminalGeneration(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED';
}

/**
 * Downloads the generated project ZIP (backend-authoritative gate).
 *
 * Uses `client.buildUrl` instead of hand-concatenating the path: the
 * previous version appended `/api/v1/generations/...` onto
 * `client.baseUrl`, which already ends in `/api/v1` — every download
 * request hit `/api/v1/api/v1/generations/.../download` and 404'd.
 * Routing through `client.request` also means a 401 here now clears the
 * session via `onUnauthorized`, same as every other endpoint.
 */
export async function downloadGeneration(
  client: ApiClient,
  token: string,
  id: string,
): Promise<Blob> {
  const response = await fetch(
    client.buildUrl(`/generations/${encodeURIComponent(id)}/download`),
    {
      method: 'GET',
      headers: bearer(token),
    },
  );
  if (response.status === 401) {
    throw new ApiError(401, 'HTTP_401', 'Session expired.');
  }
  if (!response.ok) {
    const error = await response.text();
    throw new ApiError(
      response.status,
      `HTTP_${response.status}`,
      error || 'Download failed',
    );
  }
  return response.blob();
}

/**
 * Approval gate for the generation fix loop: creates FixRequests for every
 * OPEN finding of the generation's latest review. Records permission only —
 * the backend never mutates source code here. Only REVIEWED generations
 * qualify (409 otherwise).
 */
export interface BulkFixRequestsResponse {
  reviewId: string;
  created: string[];
  skippedOpen: number;
}

export async function createGenerationFixRequests(
  client: ApiClient,
  token: string,
  id: string,
  scopeNote?: string,
): Promise<BulkFixRequestsResponse> {
  return client.request<BulkFixRequestsResponse>(
    `/generations/${encodeURIComponent(id)}/fix-requests`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify(scopeNote ? { scopeNote } : {}),
    },
  );
}