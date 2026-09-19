import type { ApiClient } from './client';
import { bearer } from './client';
import type { FileContentResponse, Page, ProjectFileResponse, ProjectResponse } from './types';

/** Phase 5 project endpoints (API_DESIGN §2). All calls are owner-scoped
 *  server-side; the token only proves identity. */
export type ProjectListParams = {
  search?: string;
  sourceType?: string;
  page?: number;
  size?: number;
};

export type FileListParams = {
  pathPrefix?: string;
  search?: string;
  page?: number;
  size?: number;
};

function query(params: Record<string, string | number | undefined>): string {
  const parts = Object.entries(params)
    .filter((entry): entry is [string, string | number] => entry[1] !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
  return parts.length > 0 ? `?${parts.join('&')}` : '';
}

export async function listProjects(
  client: ApiClient,
  token: string,
  params: ProjectListParams = {},
): Promise<Page<ProjectResponse>> {
  return client.request<Page<ProjectResponse>>(`/projects${query(params)}`, {
    headers: bearer(token),
  });
}

export async function createProject(
  client: ApiClient,
  token: string,
  input: { name: string; description?: string; language?: string },
): Promise<ProjectResponse> {
  return client.request<ProjectResponse>('/projects', {
    method: 'POST',
    headers: bearer(token),
    body: JSON.stringify(input),
  });
}

export async function getProject(
  client: ApiClient,
  token: string,
  id: string,
): Promise<ProjectResponse> {
  return client.request<ProjectResponse>(`/projects/${encodeURIComponent(id)}`, {
    headers: bearer(token),
  });
}

export async function deleteProject(
  client: ApiClient,
  token: string,
  id: string,
): Promise<void> {
  await client.request<void>(`/projects/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: bearer(token),
  });
}

export async function uploadZip(
  client: ApiClient,
  token: string,
  input: { file: File; name: string; description?: string; language?: string },
): Promise<ProjectResponse> {
  const form = new FormData();
  form.append('file', input.file);
  form.append('name', input.name);
  if (input.description) {
    form.append('description', input.description);
  }
  if (input.language) {
    form.append('language', input.language);
  }
  return client.request<ProjectResponse>('/projects/import/zip', {
    method: 'POST',
    headers: bearer(token),
    body: form,
  });
}

export async function listFiles(
  client: ApiClient,
  token: string,
  projectId: string,
  params: FileListParams = {},
): Promise<Page<ProjectFileResponse>> {
  return client.request<Page<ProjectFileResponse>>(
    `/projects/${encodeURIComponent(projectId)}/files${query(params)}`,
    { headers: bearer(token) },
  );
}

export async function getFileContent(
  client: ApiClient,
  token: string,
  projectId: string,
  path: string,
): Promise<FileContentResponse> {
  return client.request<FileContentResponse>(
    `/projects/${encodeURIComponent(projectId)}/files/content?path=${encodeURIComponent(path)}`,
    { headers: bearer(token) },
  );
}
