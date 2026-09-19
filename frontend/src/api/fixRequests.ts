import type { ApiClient } from './client';
import { bearer } from './client';
import type { FixRequestResponse } from './types';

/** POST /api/v1/findings/{id}/fix-requests — explicit approval gate (202). 409 if open request exists. */
export async function createFixRequest(
  client: ApiClient,
  token: string,
  findingId: string,
  scopeNote?: string,
): Promise<FixRequestResponse> {
  const body = scopeNote?.trim() ? { scopeNote: scopeNote.trim() } : {};
  return client.request<FixRequestResponse>(
    `/findings/${encodeURIComponent(findingId)}/fix-requests`,
    {
      method: 'POST',
      headers: bearer(token),
      body: JSON.stringify(body),
    },
  );
}

/** GET /api/v1/findings/{id}/fix-requests — history for finding (404 if not owner). */
export async function listFixRequests(
  client: ApiClient,
  token: string,
  findingId: string,
): Promise<FixRequestResponse[]> {
  return client.request<FixRequestResponse[]>(
    `/findings/${encodeURIComponent(findingId)}/fix-requests`,
    { headers: bearer(token) },
  );
}

/** GET /api/v1/fix-requests/{fixId} — single detail. */
export async function getFixRequest(
  client: ApiClient,
  token: string,
  fixId: string,
): Promise<FixRequestResponse> {
  return client.request<FixRequestResponse>(`/fix-requests/${encodeURIComponent(fixId)}`, {
    headers: bearer(token),
  });
}
