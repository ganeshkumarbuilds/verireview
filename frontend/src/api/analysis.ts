import type { ApiClient } from './client';
import { bearer } from './client';
import type { FindingResponse, Page, ReviewFixPreviewResponse, ReviewResponse } from './types';
import { createProjectFixRequests, startFixRun } from './fixRuns';
import type { FixRunDetailResponse } from './types';

export type DashboardStatsResponse = {
  projectId: string;
  projectName: string;
  totalFindings: number;
  openFindings: number;
  fixedFindings: number;
  verifiedFindings: number;
  rejectedFindings: number;
  wontfixFindings: number;
  criticalHighFindings: number;
  deterministicFindings: number;
  aiFindings: number;
  latestReviewId: number;
  latestReviewStatus: string;
  totalReviews: number;
};

export type StartReviewOptions = {
  provider?: string;
  model?: string;
  apiKey?: string;
};

/** Phase 6 deterministic analysis endpoints (API_DESIGN §2, reviews section).
 *  Triggering is async: 202 with the QUEUED review, then poll until terminal. */
export async function triggerAnalysis(
  client: ApiClient,
  token: string,
  projectId: string,
  options: StartReviewOptions = {},
): Promise<ReviewResponse> {
  return client.request<ReviewResponse>(`/projects/${encodeURIComponent(projectId)}/analysis`, {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify({
      provider: options.provider ?? 'OPENROUTER',
      model: options.model ?? '',
      apiKey: options.apiKey ?? '',
    }),
  });
}

export async function listReviews(
  client: ApiClient,
  token: string,
  projectId: string,
): Promise<Page<ReviewResponse>> {
  return client.request<Page<ReviewResponse>>(
    `/projects/${encodeURIComponent(projectId)}/reviews?size=20`,
    { headers: bearer(token) },
  );
}

export async function getReview(
  client: ApiClient,
  token: string,
  reviewId: string,
): Promise<ReviewResponse> {
  return client.request<ReviewResponse>(`/reviews/${encodeURIComponent(reviewId)}`, {
    headers: bearer(token),
  });
}

export type RetryReviewOptions = StartReviewOptions & { stage: 'analyze' | 'review' | 'rating' };

export async function retryReview(
  client: ApiClient,
  token: string,
  reviewId: string,
  options: RetryReviewOptions,
): Promise<ReviewResponse> {
  return client.request<ReviewResponse>(`/reviews/${encodeURIComponent(reviewId)}/retry`, {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify(options),
  });
}

export async function streamReviewEvents(
  client: ApiClient,
  token: string,
  reviewId: string,
  onUpdate: () => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(client.buildUrl(`/reviews/${encodeURIComponent(reviewId)}/events`), {
    headers: bearer(token),
    signal,
  });
  if (!response.ok || !response.body) throw new Error('Review live updates are unavailable.');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split('\n\n');
    buffer = events.pop() ?? '';
    if (events.some((event) => event.includes('event: update'))) onUpdate();
  }
}

export async function listFindings(
  client: ApiClient,
  token: string,
  reviewId: string,
  severity?: string,
  category?: string,
  status?: string,
  source?: string,
  toolConfirmed?: boolean,
): Promise<Page<FindingResponse>> {
  const params = new URLSearchParams();
  if (severity) params.append('severity', severity);
  if (category) params.append('category', category);
  if (status) params.append('status', status);
  if (source) params.append('source', source);
  if (toolConfirmed !== undefined) params.append('toolConfirmed', String(toolConfirmed));
  params.append('size', '200');
  return client.request<Page<FindingResponse>>(
    `/reviews/${encodeURIComponent(reviewId)}/findings?${params.toString()}`,
    { headers: bearer(token) },
  );
}

export async function previewReviewFix(
  client: ApiClient,
  token: string,
  reviewId: string,
  findingIds: string[],
): Promise<ReviewFixPreviewResponse> {
  return client.request<ReviewFixPreviewResponse>(`/reviews/${encodeURIComponent(reviewId)}/fix-preview`, {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify(findingIds),
  });
}

export async function approveReviewFix(
  client: ApiClient,
  token: string,
  projectId: string,
  findingIds: string[],
): Promise<FixRunDetailResponse> {
  await createProjectFixRequests(client, token, projectId, findingIds);
  return startFixRun(client, token, projectId, findingIds);
}

export function isTerminal(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'NOT_ANALYZED';
}

/** Severity rank for client-side filtering/sorting. */
export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const;

export async function getDashboardStats(
  client: ApiClient,
  token: string,
  projectId: string,
): Promise<DashboardStatsResponse> {
  return client.request<DashboardStatsResponse>(
    `/projects/${encodeURIComponent(projectId)}/dashboard-stats`,
    { headers: bearer(token) },
  );
}

export async function getAllDashboardStats(
  client: ApiClient,
  token: string,
): Promise<DashboardStatsResponse[]> {
  return client.request<DashboardStatsResponse[]>(
    `/dashboard/stats`,
    { headers: bearer(token) },
  );
}
