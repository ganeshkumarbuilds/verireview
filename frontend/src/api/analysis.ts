import type { ApiClient } from './client';
import { bearer } from './client';
import type { FindingResponse, Page, ReviewResponse } from './types';

/** Phase 6 deterministic analysis endpoints (API_DESIGN §2, reviews section).
 *  Triggering is async: 202 with the QUEUED review, then poll until terminal. */
export async function triggerAnalysis(
  client: ApiClient,
  token: string,
  projectId: string,
): Promise<ReviewResponse> {
  return client.request<ReviewResponse>(`/projects/${encodeURIComponent(projectId)}/analysis`, {
    method: 'POST',
    headers: bearer(token),
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

export async function listFindings(
  client: ApiClient,
  token: string,
  reviewId: string,
): Promise<Page<FindingResponse>> {
  return client.request<Page<FindingResponse>>(
    `/reviews/${encodeURIComponent(reviewId)}/findings?size=200`,
    { headers: bearer(token) },
  );
}

export function isTerminal(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED';
}

/** Severity rank for client-side filtering/sorting. */
export const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const;
