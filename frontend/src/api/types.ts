/**
 * Backend DTO mirrors (API_DESIGN §1–§2). Types only — no runtime code.
 */

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UserResponse {
  id: string;
  email: string;
  displayName: string | null;
  roles: string[];
  enabled: boolean;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface ProjectResponse {
  id: string;
  name: string;
  description: string | null;
  sourceType: string;
  language: string | null;
  status: string;
  fileCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ProjectFileResponse {
  id: string;
  path: string;
  language: string | null;
  sizeBytes: number;
  sha256: string;
}

export interface FileContentResponse {
  path: string;
  sizeBytes: number;
  truncated: boolean;
  content: string;
}

export type ReviewStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface ReviewResponse {
  id: string;
  projectId: string;
  status: ReviewStatus;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  findingCount: number;
  error: string | null;
  createdAt: string;
}

export interface FindingResponse {
  id: string;
  reviewId: string;
  category: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
  source: string;
  status: string;
  analyzer: string | null;
  rule: string | null;
  title: string;
  description: string | null;
  filePath: string | null;
  lineStart: number | null;
  lineEnd: number | null;
  evidence: string | null;
  dedupKey: string | null;
  createdAt: string;
}

export type FixRequestStatus = 'REQUESTED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface FixRequestResponse {
  id: string;
  findingId: string;
  projectId: string;
  requestedBy: string;
  status: FixRequestStatus;
  scopeNote: string | null;
  error: string | null;
  createdAt: string;
  updatedAt: string;
}

export type PatchStatus = 'PROPOSED' | 'APPLIED' | 'REJECTED';

export interface PatchResponse {
  id: string;
  fixRequestId: string;
  projectId: string;
  diff: string;
  filesChanged: number;
  additions: number;
  deletions: number;
  status: PatchStatus;
  validationError: string | null;
  createdAt: string;
  updatedAt: string;
}

export type ExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT';
export type BuildStatus = 'SUCCESS' | 'FAILURE' | 'TIMEOUT' | 'PENDING' | 'RUNNING';

export interface ExecutionRunResponse {
  id: string;
  projectId: string;
  patchId: string;
  status: ExecutionStatus;
  exitCode: number | null;
  stdout: string | null;
  stderr: string | null;
  durationMs: number | null;
  buildStatus: BuildStatus;
  createdAt: string;
  updatedAt: string;
}

export type VerificationVerdict = 'PENDING' | 'VERIFIED' | 'REJECTED';

export interface VerificationRunResponse {
  id: string;
  patchId: string;
  executionRunId: string | null;
  buildStatus: BuildStatus;
  testsTotal: number;
  testsPassed: number;
  testsFailed: number;
  testsSkipped: number;
  verdict: VerificationVerdict;
  logRef: string | null;
  durationMs: number | null;
  createdAt: string;
  updatedAt: string;
}

/** Legacy envelope shape (kept for forward-compat parsing only). */
export interface ApiEnvelope<T> {
  data: T | null;
  error: { code: string; message: string; details?: unknown } | null;
  meta?: { traceId?: string };
}
