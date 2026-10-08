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
export type CheckStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';

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

export interface FeatureSummary {
  feature: string;
  totalRuns: number;
  running: number;
  succeeded: number;
  failed: number;
  skipped: number;
  lastRunAt: string | null;
}

export interface SeveritySummary {
  severity: string;
  count: number;
}

export interface RunSummary {
  runId: string;
  feature: string;
  status: string;
  progress: number;
  startedAt: string | null;
  finishedAt: string | null;
  durationMs: number | null;
  totalIssues: number;
}

export interface TrendPoint {
  timestamp: string;
  generateScore: number;
  reviewScore: number;
  fixScore: number;
  verifyScore: number;
  totalScore: number;
}

export interface ProjectHealthScore {
  id: string;
  projectId: string;
  score: number;
  generateScore: number;
  reviewScore: number;
  fixScore: number;
  verifyScore: number;
  openCriticalHigh: number;
  openFindingsTotal: number;
  fixedVerifiedRatio: number | null;
  lastComputedAt: string;
}

export interface DashboardSummaryResponse {
  features: FeatureSummary[];
  openIssuesBySeverity: SeveritySummary[];
  lastRuns: RunSummary[];
  trend: TrendPoint[];
  latestHealthScore: ProjectHealthScore;
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
