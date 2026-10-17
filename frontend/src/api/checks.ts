import type { ApiClient } from './client';
import { bearer } from './client';
import type { DashboardSummaryResponse } from './types';

/** Authenticated dashboard summary (totals + per-project rollup). */
export async function getDashboardSummary(
  client: ApiClient,
  token: string,
): Promise<DashboardSummaryResponse> {
  return client.request<DashboardSummaryResponse>(`/dashboard/summary`, {
    headers: bearer(token),
  });
}

// Re-export DashboardSummaryResponse for consumers
export type { DashboardSummaryResponse } from './types';
