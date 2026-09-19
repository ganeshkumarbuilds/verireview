import type { ApiClient } from './client';
import { bearer } from './client';
import type { PatchResponse } from './types';

/** POST /api/v1/fix-requests/{fixRequestId}/patch — propose a patch (201). */
export async function proposePatch(
  client: ApiClient,
  token: string,
  fixRequestId: string,
): Promise<PatchResponse> {
  return client.request<PatchResponse>(
    `/fix-requests/${encodeURIComponent(fixRequestId)}/patch`,
    {
      method: 'POST',
      headers: bearer(token),
    },
  );
}

/** GET /api/v1/fix-requests/{fixRequestId}/patch — get patch for fix request. */
export async function getPatchForFixRequest(
  client: ApiClient,
  token: string,
  fixRequestId: string,
): Promise<PatchResponse> {
  return client.request<PatchResponse>(
    `/fix-requests/${encodeURIComponent(fixRequestId)}/patch`,
    { headers: bearer(token) },
  );
}

/** GET /api/v1/patches/{patchId} — get single patch. */
export async function getPatch(
  client: ApiClient,
  token: string,
  patchId: string,
): Promise<PatchResponse> {
  return client.request<PatchResponse>(
    `/patches/${encodeURIComponent(patchId)}`,
    { headers: bearer(token) },
  );
}

/** POST /api/v1/patches/{patchId}/apply — apply patch (returns updated patch). */
export async function applyPatch(
  client: ApiClient,
  token: string,
  patchId: string,
): Promise<PatchResponse> {
  return client.request<PatchResponse>(
    `/patches/${encodeURIComponent(patchId)}/apply`,
    {
      method: 'POST',
      headers: bearer(token),
    },
  );
}