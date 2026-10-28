import { useEffect } from 'react';
import type { ApiClient } from '../api/client';

const WARMUP_KEY = 'verireview.livenessWarmupAt';
const WARMUP_INTERVAL_MS = 5 * 60 * 1000;

export function useServerWarmup(client: ApiClient): void {
  useEffect(() => {
    let lastWarmup = 0;
    try {
      lastWarmup = Number(window.sessionStorage.getItem(WARMUP_KEY) ?? '0');
    } catch {
      lastWarmup = 0;
    }
    if (Date.now() - lastWarmup < WARMUP_INTERVAL_MS) {
      return;
    }
    try {
      window.sessionStorage.setItem(WARMUP_KEY, String(Date.now()));
    } catch {
      // A best-effort warm-up must not block the page.
    }
    void client.liveness();
  }, [client]);
}
