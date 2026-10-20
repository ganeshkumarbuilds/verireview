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
  evidenceSnippet: string | null;
  explanation: string | null;
  suggestedFix: string | null;
  confidence: number | null;
  toolConfirmed: boolean | null;
  dedupKey: string | null;
  createdAt: string;
}

/** Check run status shared by all 4 features */
export type CheckStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'PENDING';

export interface CheckStepResponse {
  id: string;
  checkRunId: string;
  stepOrder: number;
  name: string;
  status: string;
  progress: number;
  currentMessage: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  logTail: string | null;
}

export interface CheckRunGateResponse {
  id: string;
  checkRunId: string;
  gateName: string;
  gateDescription: string | null;
  passed: boolean;
  evidence: string | null;
  details: string | null;
}

export interface CheckRunResponse {
  id: string;
  projectId: string;
  generationId: string | null;
  feature: string;
  status: CheckStatus;
  progress: number;
  currentStep: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  severityCritical: number;
  severityHigh: number;
  severityMedium: number;
  severityLow: number;
  severityInfo: number;
  createdAt: string;
  updatedAt: string;
  steps?: CheckStepResponse[];
  gates?: CheckRunGateResponse[];
}

export interface ProjectSummary {
  id: string;
  name: string;
  filesVerified: number;
  issuesFound: number;
  issuesFixed: number;
  lastActivityAt: string | null;
}

/** Simplified dashboard payload: owner-scoped totals plus a project rollup. */
export interface DashboardSummaryResponse {
  totalFilesVerified: number;
  totalIssuesFound: number;
  totalIssuesFixed: number;
  projects: ProjectSummary[];
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

export type PatchStatus = 'PROPOSED' | 'APPLIED' | 'REJECTED' | 'REVERTED';

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

export type ExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'FAILURE' | 'TIMEOUT' | 'SKIPPED';
export type BuildStatus = 'SUCCESS' | 'FAILURE' | 'TIMEOUT' | 'PENDING' | 'RUNNING' | 'SKIPPED';

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
  sandboxSkipped: boolean;
}

/** Agent pipeline (Import -> Analyze -> Review -> Fix -> Verify). */
export type PipelineStageState = 'WAITING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';

export interface PipelineStageResponse {
  key: string;
  label: string;
  state: PipelineStageState;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  detail: string | null;
  error: string | null;
  checkRunId: string | null;
}

export interface PipelineResponse {
  projectId: string;
  stages: PipelineStageResponse[];
}

/** Bulk consent result (create approved fix requests for selected findings). */
export interface BulkFixRequestCreated {
  projectId: string;
  fixRequestIds: string[];
  created: number;
  skipped: number;
}

export interface FixRunSummaryResponse {
  fixed: number;
  stillOpen: number;
  newlyIntroduced: number;
}

export interface FixRunChangedFileResponse {
  path: string;
  patchId: string;
  status: string;
  diff: string | null;
}

export interface FixRunDetailResponse {
  checkRunId: string;
  projectId: string;
  status: CheckStatus;
  error: string | null;
  steps: CheckStepResponse[];
  summary: FixRunSummaryResponse;
  changedFiles: FixRunChangedFileResponse[];
  verificationVerdict: VerificationVerdict | null;
  sandboxSkipped: boolean;
  startedAt: string | null;
  finishedAt: string | null;
}

/** Legacy envelope shape (kept for forward-compat parsing only). */
export interface ApiEnvelope<T> {
  data: T | null;
  error: { status?: number; code: string; message: string; details?: unknown } | null;
  meta?: { traceId?: string };
  traceId?: string;
}
