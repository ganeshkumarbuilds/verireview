import type { ApiClient } from './client';
import { bearer } from './client';
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
