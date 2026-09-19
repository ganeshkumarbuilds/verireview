import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from './client';
import { createProject, listProjects, uploadZip } from './projects';

function stubFetch(handler: (url: string, init?: RequestInit) => Response) {
  return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    return handler(String(url), init);
  }) as unknown as typeof fetch;
}

const ok = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status });

describe('projects api', () => {
  it('lists with paging params and the bearer token', async () => {
    const fetchImpl = stubFetch((url, init) => {
      expect(url).toBe('http://localhost:8080/api/v1/projects?size=50');
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer tok');
      return ok({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
    });
    const page = await listProjects(new ApiClient({ fetchImpl }), 'tok', { size: 50 });
    expect(page.totalElements).toBe(0);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('creates a shell with a JSON body', async () => {
    const fetchImpl = stubFetch((url, init) => {
      expect(url).toBe('http://localhost:8080/api/v1/projects');
      expect(init?.method).toBe('POST');
      expect(init?.body).toBe(JSON.stringify({ name: 'demo' }));
      return ok({ id: 'p1', name: 'demo' }, 201);
    });
    const project = await createProject(new ApiClient({ fetchImpl }), 'tok', {
      name: 'demo',
    });
    expect(project.name).toBe('demo');
  });

  it('uploads a ZIP as multipart form data', async () => {
    const fetchImpl = stubFetch((url, init) => {
      expect(url).toBe('http://localhost:8080/api/v1/projects/import/zip');
      const body = init?.body;
      expect(body).toBeInstanceOf(FormData);
      expect((body as FormData).get('name')).toBe('zipped');
      expect((body as FormData).get('file')).toBeInstanceOf(File);
      return ok({ id: 'p2', name: 'zipped', fileCount: 1 }, 201);
    });
    const project = await uploadZip(new ApiClient({ fetchImpl }), 'tok', {
      file: new File(['PK'], 'demo.zip', { type: 'application/zip' }),
      name: 'zipped',
    });
    expect(project.fileCount).toBe(1);
  });
});
