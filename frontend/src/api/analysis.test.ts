import { describe, expect, it, vi } from 'vitest';
import {
  getReview,
  isTerminal,
  listFindings,
  listReviews,
  triggerAnalysis,
} from './analysis';
import { ApiClient } from './client';

function stubFetch(handler: (url: string, init?: RequestInit) => Response) {
  return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    return handler(String(url), init);
  }) as unknown as typeof fetch;
}

const ok = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status });

describe('analysis api', () => {
  it('triggers an analysis with POST and polls reads with GET', async () => {
    const calls: { url: string; method: string }[] = [];
    const fetchImpl = stubFetch((url, init) => {
      calls.push({ url, method: init?.method ?? 'GET' });
      return ok({ id: 'r1', status: 'QUEUED', findingCount: 0 });
    });
    const client = new ApiClient({ fetchImpl });
    await triggerAnalysis(client, 'tok', 'p1');
    await listReviews(client, 'tok', 'p1');
    await getReview(client, 'tok', 'r1');
    await listFindings(client, 'tok', 'r1');
    expect(calls).toEqual([
      { url: 'http://localhost:8080/api/v1/projects/p1/analysis', method: 'POST' },
      { url: 'http://localhost:8080/api/v1/projects/p1/reviews?size=20', method: 'GET' },
      { url: 'http://localhost:8080/api/v1/reviews/r1', method: 'GET' },
      { url: 'http://localhost:8080/api/v1/reviews/r1/findings?size=200', method: 'GET' },
    ]);
  });

  it('recognizes terminal review states', () => {
    expect(isTerminal('COMPLETED')).toBe(true);
    expect(isTerminal('FAILED')).toBe(true);
    expect(isTerminal('QUEUED')).toBe(false);
    expect(isTerminal('RUNNING')).toBe(false);
  });
});
