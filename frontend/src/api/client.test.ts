import { describe, expect, it } from 'vitest';
import { ApiClient, ApiError, parseBody } from './client';

describe('ApiClient', () => {
  it('joins paths onto the base URL', () => {
    const client = new ApiClient({ baseUrl: 'http://localhost:8080/api/v1/' });
    expect(client.buildUrl('/projects')).toBe('http://localhost:8080/api/v1/projects');
    expect(client.buildUrl('auth/me')).toBe('http://localhost:8080/api/v1/auth/me');
  });

  it('returns plain DTO JSON on success', async () => {
    const response = new Response(JSON.stringify({ id: '1', name: 'demo' }), {
      status: 200,
    });
    await expect(parseBody(response)).resolves.toEqual({ id: '1', name: 'demo' });
  });

  it('throws ApiError with the backend message on failure', async () => {
    const response = new Response(JSON.stringify({ message: 'Project not found.' }), {
      status: 404,
    });
    const failure = parseBody(response).catch((error: unknown) => error);
    await expect(failure).resolves.toBeInstanceOf(ApiError);
    const apiError = (await failure) as ApiError;
    expect(apiError.status).toBe(404);
    expect(apiError.message).toBe('Project not found.');
  });

  it('resolves empty 2xx bodies as undefined', async () => {
    const accepted = new Response(null, { status: 202 });
    await expect(parseBody(accepted)).resolves.toBeUndefined();
    const noContent = new Response(null, { status: 204 });
    await expect(parseBody(noContent)).resolves.toBeUndefined();
  });

  it('throws ApiError on unreadable error bodies', async () => {
    const response = new Response('not json{{{', { status: 500 });
    await expect(parseBody(response)).rejects.toMatchObject({ code: 'HTTP_500' });
  });

  it('keeps FormData free of a JSON content type', async () => {
    let sent: RequestInit | undefined;
    const client = new ApiClient({
      fetchImpl: (async (_url: string | URL | Request, init?: RequestInit) => {
        sent = init;
        return new Response(JSON.stringify({}), { status: 200 });
      }) as typeof fetch,
    });
    await client.request('/projects/import/zip', { method: 'POST', body: new FormData() });
    expect(new Headers(sent?.headers).get('Content-Type')).toBeNull();
  });
});
