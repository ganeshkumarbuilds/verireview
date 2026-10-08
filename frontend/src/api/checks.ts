import type { ApiClient } from './client';
import { bearer } from './client';
import type {
  CheckRunResponse,
  CheckStatus,
  DashboardSummaryResponse,
} from './types';

/** Check run endpoints - unified across GENERATE, REVIEW, FIX, VERIFY */
export async function getProjectChecks(
  client: ApiClient,
  token: string,
  projectId: string,
): Promise<CheckRunResponse[]> {
  return client.request<CheckRunResponse[]>(
    `/projects/${encodeURIComponent(projectId)}/checks`,
    { headers: bearer(token) },
  );
}

export async function getCheckRun(
  client: ApiClient,
  token: string,
  projectId: string,
  runId: string,
): Promise<CheckRunResponse> {
  return client.request<CheckRunResponse>(
    `/projects/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}`,
    { headers: bearer(token) },
  );
}

export async function getDashboardSummary(
  client: ApiClient,
  token: string,
): Promise<DashboardSummaryResponse> {
  return client.request<DashboardSummaryResponse>(
    `/dashboard/summary`,
    { headers: bearer(token) },
  );
}

// Re-export DashboardSummaryResponse for consumers
export type { DashboardSummaryResponse } from './types';

/** SSE event types */
export interface SseEvent<T = unknown> {
  type: 'init' | 'update' | 'heartbeat' | 'error';
  data: T;
  timestamp: string;
}

/** Fetch-based SSE client with auth, auto-reconnect, and 3s polling fallback */
export class SseClient<T = unknown> {
  private eventSource: EventSource | null = null;
  private fetchController: AbortController | null = null;
  private reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
  private pollingInterval: ReturnType<typeof setInterval> | null = null;
  private isConnecting = false;
  private readonly url: string;
  private readonly token: string;
  private readonly onMessage: (event: SseEvent<T>) => void;
  private readonly onError: (error: Error) => void;
  private readonly onClose: () => void;
  private readonly useFetchFallback: boolean;

  constructor(
    baseUrl: string,
    path: string,
    token: string,
    onMessage: (event: SseEvent<T>) => void,
    onError: (error: Error) => void,
    onClose: () => void,
    options: { useFetchFallback?: boolean } = {}
  ) {
    this.url = `${baseUrl}${path}`;
    this.token = token;
    this.onMessage = onMessage;
    this.onError = onError;
    this.onClose = onClose;
    this.useFetchFallback = options.useFetchFallback ?? true;
  }

  /** Connect using EventSource (preferred) or fetch-based streaming */
  connect(): void {
    if (this.isConnecting) return;
    this.isConnecting = true;

    // Try EventSource first (simpler, native reconnect)
    try {
      this.eventSource = new EventSource(this.url);
      this.setupEventSource();
      this.isConnecting = false;
      return;
    } catch (e) {
      console.warn('EventSource not available, falling back to fetch:', e);
    }

    // Fallback: fetch-based streaming
    if (this.useFetchFallback) {
      this.connectFetch();
    } else {
      this.isConnecting = false;
      this.startPolling();
    }
  }

  private setupEventSource(): void {
    if (!this.eventSource) return;

    this.eventSource.onopen = () => {
      this.isConnecting = false;
      this.stopPolling();
    };

    this.eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        this.onMessage({ type: 'update', data, timestamp: new Date().toISOString() });
      } catch {
        // Ignore parse errors
      }
    };

    this.eventSource.addEventListener('init', (event) => {
      try {
        const data = JSON.parse(event.data);
        this.onMessage({ type: 'init', data, timestamp: new Date().toISOString() });
      } catch {}
    });

    this.eventSource.addEventListener('heartbeat', () => {
      // Keep alive
    });

    this.eventSource.onerror = () => {
      this.onError(new Error('EventSource connection error'));
      this.eventSource?.close();
      this.scheduleReconnect();
    };
  }

  /** Fetch-based streaming with manual reconnect and auth header */
  private async connectFetch(): Promise<void> {
    this.fetchController = new AbortController();
    
    try {
      const response = await fetch(this.url, {
        headers: {
          'Authorization': `Bearer ${this.token}`,
          'Accept': 'text/event-stream',
        },
        signal: this.fetchController.signal,
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No response body');

      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            try {
              const data = JSON.parse(line.slice(6));
              this.onMessage({ type: 'update', data, timestamp: new Date().toISOString() });
            } catch {}
          } else if (line.startsWith('event: ')) {
            // Named events handled by event listeners
          }
        }
      }
    } catch (_error) {
      // AbortError is expected when closing
    } finally {
      this.scheduleReconnect();
    }
  }

  /** 3-second polling fallback when SSE is unavailable */
  private startPolling(): void {
    this.stopPolling();
    this.pollingInterval = setInterval(async () => {
      try {
        const response = await fetch(this.url.replace('/stream', ''), {
          headers: { 'Authorization': `Bearer ${this.token}` },
        });
        if (response.ok) {
          const data = await response.json();
          this.onMessage({ type: 'update', data, timestamp: new Date().toISOString() });
        }
      } catch {
        // Ignore polling errors
      }
    }, 3000);
  }

  private stopPolling(): void {
    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
      this.pollingInterval = null;
    }
  }

  private scheduleReconnect(): void {
    this.stopPolling();
    if (this.reconnectTimeout) clearTimeout(this.reconnectTimeout);
    this.reconnectTimeout = setTimeout(() => {
      this.isConnecting = false;
      this.connect();
    }, 3000); // 3s backoff
  }

  /** Close the connection */
  close(): void {
    this.stopPolling();
    if (this.reconnectTimeout) clearTimeout(this.reconnectTimeout);
    this.eventSource?.close();
    this.fetchController?.abort();
    this.onClose();
  }
}

/** Helper to create check run SSE client */
export function createCheckSseClient<T>(
  client: ApiClient,
  token: string,
  projectId: string,
  onMessage: (event: SseEvent<T>) => void,
  onError: (error: Error) => void,
  onClose: () => void,
): SseClient<T> {
  return new SseClient<T>(
    client.buildUrl(''),
    `/projects/${encodeURIComponent(projectId)}/checks/stream`,
    token,
    onMessage,
    onError,
    onClose,
  );
}

/** Helper to create dashboard SSE client */
export function createDashboardSseClient<T>(
  client: ApiClient,
  token: string,
  onMessage: (event: SseEvent<T>) => void,
  onError: (error: Error) => void,
  onClose: () => void,
): SseClient<T> {
  return new SseClient<T>(
    client.buildUrl(''),
    `/dashboard/stream`,
    token,
    onMessage,
    onError,
    onClose,
  );
}

/** Helper to get status display info */
export function getCheckStatusInfo(status: CheckStatus): {
  label: string;
  color: 'gray' | 'blue' | 'amber' | 'green' | 'red' | 'violet';
  icon: string;
} {
  switch (status) {
    case 'QUEUED':
      return { label: 'Queued', color: 'gray', icon: '⏳' };
    case 'RUNNING':
      return { label: 'Running', color: 'blue', icon: '🔄' };
    case 'SUCCEEDED':
      return { label: 'Succeeded', color: 'green', icon: '✅' };
    case 'FAILED':
      return { label: 'Failed', color: 'red', icon: '❌' };
    case 'SKIPPED':
      return { label: 'Skipped', color: 'amber', icon: '⏭️' };
    default:
      return { label: status, color: 'gray', icon: '❓' };
  }
}

/** Calculate overall health score color */
export function getHealthScoreColor(score: number): 'red' | 'amber' | 'emerald' {
  if (score >= 80) return 'emerald';
  if (score >= 60) return 'amber';
  return 'red';
}