import type { ApiClient } from './client';
import { bearer } from './client';
import type { ExecutionRunResponse } from './types';

/** POST /api/v1/projects/{projectId}/execute — start execution (202). */
export async function startExecution(
  client: ApiClient,
  token: string,
  projectId: string,
  patchId?: string,
): Promise<ExecutionRunResponse> {
  const url = `/projects/${encodeURIComponent(projectId)}/execute${patchId ? `?patchId=${encodeURIComponent(patchId)}` : ''}`;
  return client.request<ExecutionRunResponse>(url, {
    method: 'POST',
    headers: bearer(token),
  });
}

/** GET /api/v1/projects/{projectId}/executions — list executions for project. */
export async function listExecutions(
  client: ApiClient,
  token: string,
  projectId: string,
): Promise<ExecutionRunResponse[]> {
  return client.request<ExecutionRunResponse[]>(
    `/projects/${encodeURIComponent(projectId)}/executions`,
    { headers: bearer(token) },
  );
}

/** GET /api/v1/executions/{executionId} — get single execution. */
export async function getExecution(
  client: ApiClient,
  token: string,
  executionId: string,
): Promise<ExecutionRunResponse> {
  return client.request<ExecutionRunResponse>(
    `/executions/${encodeURIComponent(executionId)}`,
    { headers: bearer(token) },
  );
}