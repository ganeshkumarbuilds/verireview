import type { ApiClient } from './client';
import { bearer } from './client';
import type {
  BulkFixRequestCreated,
  FixRunDetailResponse,
  PatchResponse,
} from './types';

/**
 * Consent step: create approved fix requests for the selected findings.
 * Nothing is changed here — this records the user's explicit approval.
 */
export async function createProjectFixRequests(
  client: ApiClient,
  token: string,
  projectId: string,
  findingIds: string[],
  scopeNote?: string,
): Promise<BulkFixRequestCreated> {
  return client.request<BulkFixRequestCreated>(
    `/projects/${encodeURIComponent(projectId)}/fix-requests`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify({ findingIds, scopeNote }),
    },
  );
}

/**
 * Starts a fix run. The backend rejects (409) unless an approved fix request
 * exists for the user and every selected finding, then applies the coding
 * agent's patch and runs the automatic re-check.
 */
export async function startFixRun(
  client: ApiClient,
  token: string,
  projectId: string,
  findingIds: string[],
  scopeNote?: string,
): Promise<FixRunDetailResponse> {
  return client.request<FixRunDetailResponse>(
    `/projects/${encodeURIComponent(projectId)}/fix-runs`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify({ findingIds, scopeNote }),
    },
  );
}

export async function listFixRuns(
  client: ApiClient,
  token: string,
  projectId: string,
): Promise<FixRunDetailResponse[]> {
  const result = await client.request<FixRunDetailResponse[] | { content: FixRunDetailResponse[] }>(
    `/projects/${encodeURIComponent(projectId)}/fix-runs`,
    { headers: bearer(token) },
  );
  if (Array.isArray(result)) return result;
  if (result && Array.isArray((result as { content?: FixRunDetailResponse[] }).content)) {
    return (result as { content: FixRunDetailResponse[] }).content;
  }
  return [];
}

export async function getFixRun(
  client: ApiClient,
  token: string,
  projectId: string,
  checkRunId: string,
): Promise<FixRunDetailResponse> {
  return client.request<FixRunDetailResponse>(
    `/projects/${encodeURIComponent(projectId)}/fix-runs/${encodeURIComponent(checkRunId)}`,
    { headers: bearer(token) },
  );
}

/** Rolls back an applied patch, restoring the previous file content. */
export async function revertPatch(
  client: ApiClient,
  token: string,
  patchId: string,
): Promise<PatchResponse> {
  return client.request<PatchResponse>(
    `/patches/${encodeURIComponent(patchId)}/revert`,
    { method: 'POST', headers: bearer(token) },
  );
}
