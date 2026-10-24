import type { ApiClient } from './client';
import { ApiError, bearer } from './client';
import type {
  CreateGenerationInput,
  GenerationFileContentView,
  GenerationFileView,
  GenerationFixPreviewResponse,
  GenerationQuestion,
  GenerationQuestionsInput,
  GenerationResponse,
  GenerationWizardPlanInput,
  PlanPreviewInput,
  PlanPreviewResponse,
  RestructurePreset,
  RestructureView,
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

/** Streams change notifications; callers reload the authoritative generation DTO. */
export async function streamGenerationUpdates(
  client: ApiClient,
  token: string,
  id: string,
  signal: AbortSignal,
  onConnected: () => void,
  onUpdate: () => void,
): Promise<void> {
  const response = await fetch(
    client.buildUrl(`/generations/${encodeURIComponent(id)}/events`),
    {
      headers: { ...bearer(token), Accept: 'text/event-stream' },
      signal,
    },
  );
  if (!response.ok) {
    throw new ApiError(response.status, `HTTP_${response.status}`, 'Live updates unavailable.');
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream')) {
    throw new Error('The live-update endpoint did not return an event stream.');
  }
  if (!response.body) {
    throw new Error('Live updates are not supported by this connection.');
  }

  onConnected();
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) return;
      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, '\n');
      let boundary = buffer.indexOf('\n\n');
      while (boundary >= 0) {
        const event = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        if (event.split(/\r?\n/).some((line) => line.startsWith('data:'))) {
          onUpdate();
        }
        boundary = buffer.indexOf('\n\n');
      }
    }
  } finally {
    reader.releaseLock();
  }
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
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
    || status === 'AWAITING_FIX_APPROVAL';
}

export async function previewGenerationFix(
  client: ApiClient,
  token: string,
  id: string,
  findingIds: string[],
): Promise<GenerationFixPreviewResponse> {
  return client.request<GenerationFixPreviewResponse>(
    `/generations/${encodeURIComponent(id)}/fix-approvals/preview`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify({ findingIds }),
    },
  );
}

export async function approveGenerationFix(
  client: ApiClient,
  token: string,
  id: string,
  findingIds: string[],
  apiKey: string,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>(
    `/generations/${encodeURIComponent(id)}/fix-approvals`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify({ findingIds, apiKey }),
    },
  );
}

export async function revertGenerationFixRound(
  client: ApiClient,
  token: string,
  id: string,
  round: number,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>(
    `/generations/${encodeURIComponent(id)}/fix-rounds/${round}/revert`,
    { method: 'POST', headers: bearer(token) },
  );
}

export async function retryGenerationStage(
  client: ApiClient,
  token: string,
  id: string,
  executionId: string,
  apiKey: string,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>(
    `/generations/${encodeURIComponent(id)}/agent-executions/${encodeURIComponent(executionId)}/retry`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify({ apiKey }),
    },
  );
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

/** Step 3: backend-derived clarifying questions. No defaults, no job. */
export async function getGenerationQuestions(
  client: ApiClient,
  token: string,
  input: GenerationQuestionsInput,
): Promise<GenerationQuestion[]> {
  return client.request<GenerationQuestion[]>('/generate/questions', {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify(input),
  });
}

/** Step 4: plan built only from the explicit answers. 400 lists missing ids. */
export async function previewWizardPlan(
  client: ApiClient,
  token: string,
  input: GenerationWizardPlanInput,
): Promise<PlanPreviewResponse> {
  return client.request<PlanPreviewResponse>('/generate/plan', {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify(input),
  });
}

/** Step 4: stack-aware scaffold preview. Creates no generation job. */
export async function previewGenerationPlan(
  client: ApiClient,
  token: string,
  input: PlanPreviewInput,
): Promise<PlanPreviewResponse> {
  return client.request<PlanPreviewResponse>('/generations/plan-preview', {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify(input),
  });
}

/** Files actually present in the generated workspace (paths + sizes). */
export async function listGenerationFiles(
  client: ApiClient,
  token: string,
  id: string,
): Promise<GenerationFileView[]> {
  return client.request<GenerationFileView[]>(
    `/generations/${encodeURIComponent(id)}/files`,
    { headers: bearer(token) },
  );
}

/** Materializes the reviewed generation as a Project for the Review flow. */
export async function sendGenerationToReview(
  client: ApiClient,
  token: string,
  id: string,
): Promise<GenerationResponse> {
  return client.request<GenerationResponse>(
    `/generations/${encodeURIComponent(id)}/send-to-review`,
    { method: 'POST', headers: bearer(token) },
  );
}

/** Reads one generated text file (backend caps size and jails the path). */
export async function getGenerationFile(
  client: ApiClient,
  token: string,
  id: string,
  path: string,
): Promise<GenerationFileContentView> {
  return client.request<GenerationFileContentView>(
    `/generations/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`,
    { headers: bearer(token) },
  );
}

export async function proposeRestructure(
  client: ApiClient,
  token: string,
  id: string,
  preset: RestructurePreset,
  description?: string,
): Promise<RestructureView> {
  return client.request<RestructureView>(
    `/generations/${encodeURIComponent(id)}/restructure/proposals`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify({ preset, description: description || undefined }),
    },
  );
}

export async function listRestructures(
  client: ApiClient,
  token: string,
  id: string,
): Promise<RestructureView[]> {
  return client.request<RestructureView[]>(
    `/generations/${encodeURIComponent(id)}/restructure/proposals`,
    { headers: bearer(token) },
  );
}

export async function applyRestructure(
  client: ApiClient,
  token: string,
  id: string,
  proposalId: string,
): Promise<RestructureView> {
  return client.request<RestructureView>(
    `/generations/${encodeURIComponent(id)}/restructure/proposals/${encodeURIComponent(proposalId)}/apply`,
    { method: 'POST', headers: bearer(token) },
  );
}

export async function revertRestructure(
  client: ApiClient,
  token: string,
  id: string,
): Promise<RestructureView> {
  return client.request<RestructureView>(
    `/generations/${encodeURIComponent(id)}/restructure/revert`,
    { method: 'POST', headers: bearer(token) },
  );
}