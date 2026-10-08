import type { ApiClient } from './client';
import { bearer } from './client';
import type { FindingResponse, Page, ReviewResponse } from './types';

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

export function isTerminal(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED';
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
