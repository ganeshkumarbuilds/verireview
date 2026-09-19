import type { ApiClient } from './client';
import { bearer } from './client';
import type { VerificationRunResponse } from './types';

/** POST /api/v1/executions/{executionId}/verification — run verification (201/200). */
export async function runVerification(
  client: ApiClient,
  token: string,
  executionId: string,
): Promise<VerificationRunResponse> {
  return client.request<VerificationRunResponse>(
    `/executions/${encodeURIComponent(executionId)}/verification`,
    {
      method: 'POST',
      headers: bearer(token),
    },
  );
}

/** GET /api/v1/verifications/{verificationId} — get single verification. */
export async function getVerification(
  client: ApiClient,
  token: string,
  verificationId: string,
): Promise<VerificationRunResponse> {
  return client.request<VerificationRunResponse>(
    `/verifications/${encodeURIComponent(verificationId)}`,
    { headers: bearer(token) },
  );
}

/** GET /api/v1/executions/{executionId}/verification — get verification for execution. */
export async function getVerificationByExecution(
  client: ApiClient,
  token: string,
  executionId: string,
): Promise<VerificationRunResponse> {
  return client.request<VerificationRunResponse>(
    `/executions/${encodeURIComponent(executionId)}/verification`,
    { headers: bearer(token) },
  );
}

/** GET /api/v1/patches/{patchId}/verifications — list verifications for patch. */
export async function listVerificationsByPatch(
  client: ApiClient,
  token: string,
  patchId: string,
): Promise<VerificationRunResponse[]> {
  return client.request<VerificationRunResponse[]>(
    `/patches/${encodeURIComponent(patchId)}/verifications`,
    { headers: bearer(token) },
  );
}