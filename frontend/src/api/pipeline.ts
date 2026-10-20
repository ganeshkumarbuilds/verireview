import type { ApiClient } from './client';
import { bearer } from './client';
import type { PipelineResponse } from './types';

/** Real agent pipeline for a project: Import -> Analyze -> Review -> Fix -> Verify.
 *  Stages are derived from real backend records, never hardcoded client state. */
export async function getPipeline(
  client: ApiClient,
  token: string,
  projectId: string,
): Promise<PipelineResponse> {
  return client.request<PipelineResponse>(
    `/projects/${encodeURIComponent(projectId)}/pipeline`,
    { headers: bearer(token) },
  );
}
