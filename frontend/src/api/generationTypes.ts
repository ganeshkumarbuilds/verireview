/**
 * Generation DTO mirrors. Secrets travel client → backend in the create
 * body only; they are never stored locally and never appear in responses.
 */

export type GenerationBackend =
  | 'JAVA_SPRING_BOOT'
  | 'PYTHON_FASTAPI'
  | 'NODEJS'
  | 'OTHER'
  | 'NONE';
export type GenerationFrontend = 'REACT_TYPESCRIPT' | 'NEXTJS' | 'VUE' | 'OTHER' | 'NONE';
export type GenerationDatabase = 'POSTGRESQL' | 'MYSQL' | 'MONGODB' | 'SQLITE' | 'NONE';
export type GenerationAuth = 'JWT' | 'OAUTH_GOOGLE' | 'NONE';
export type GenerationIntegration =
  | 'STRIPE'
  | 'RAZORPAY'
  | 'EMAIL'
  | 'FILE_STORAGE_S3'
  | 'AI_LLM'
  | 'MAPS'
  | 'ANALYTICS'
  | 'DOCKER_CI';
export type GenerationAiProvider = 'OPENROUTER' | 'CUSTOM' | 'NONE';
export type GenerationStatus =
  | 'DRAFT'
  | 'READY'
  | 'QUEUED'
  | 'PLANNING'
  | 'GENERATING'
  | 'CODING'
  | 'BUILDING'
  | 'TESTING'
  | 'VERIFYING'
  | 'NOT_VERIFIED'
  | 'VERIFIED'
  | 'REVIEWING'
  | 'REVIEWED'
  | 'AWAITING_FIX_APPROVAL'
  | 'REBUILDING'
  | 'REVERIFYING'
  | 'FIXING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export interface GenerationDatabaseInput {
  host: string;
  port: number;
  name: string;
  username: string;
  password: string;
  sslMode?: string;
}

export interface GenerationAiInput {
  provider: GenerationAiProvider;
  /** Omitted for drafts and for the NONE provider (template fallback). */
  apiKey?: string;
  baseUrl?: string;
  model: string;
}

export interface CreateGenerationInput {
  name: string;
  requirement: string;
  description?: string;
  backend: GenerationBackend;
  frontend: GenerationFrontend;
  database: GenerationDatabase;
  auth?: GenerationAuth;
  integrations?: GenerationIntegration[];
  languageRuntime?: string;
  backendCustom?: string;
  frontendCustom?: string;
  answers?: GenerationAnswer[];
  databaseConfigureLater?: boolean;
  databaseConfig?: GenerationDatabaseInput;
  aiConfig: GenerationAiInput;
  /** The user's reviewed plan. The backend persists and generates exactly these files. */
  approvedPlan?: PlanPreviewResponse;
  /** Draft mode persists configuration only: secrets optional, nothing dispatched. */
  draft?: boolean;
}

export type GenerationQuestionType = 'CHOICE' | 'TEXT' | 'YES_NO';

export interface GenerationQuestionOption {
  value: string;
  label: string;
}

export interface GenerationQuestion {
  id: string;
  prompt: string;
  help: string;
  type: GenerationQuestionType;
  required: boolean;
  allowSkip: boolean;
  secret: boolean;
  options: GenerationQuestionOption[];
}

export interface GenerationAnswer {
  questionId: string;
  value?: string;
  skipped: boolean;
}

/** Steps 1–2 context sent to the backend to derive step 3 questions. */
export interface GenerationQuestionsInput {
  name: string;
  requirement: string;
  description?: string;
  backend: GenerationBackend;
  frontend: GenerationFrontend;
  languageRuntime?: string;
  backendCustom?: string;
  frontendCustom?: string;
}

export interface GenerationWizardPlanInput {
  project: GenerationQuestionsInput;
  answers: GenerationAnswer[];
}

export interface UpdateGenerationInput {
  name?: string;
  requirement?: string;
  description?: string;
  backend?: GenerationBackend;
  frontend?: GenerationFrontend;
  database?: GenerationDatabase;
  auth?: GenerationAuth;
  languageRuntime?: string;
  backendCustom?: string;
  frontendCustom?: string;
  integrations?: GenerationIntegration[];
  databaseConfig?: {
    host?: string;
    port?: number;
    name?: string;
    username?: string;
    sslMode?: string;
  };
  aiConfig?: {
    provider?: GenerationAiProvider;
    baseUrl?: string;
    model?: string;
  };
}

export interface StartGenerationInput {
  /** Re-supplied at start; drafts hold no secrets server-side. */
  password?: string;
  apiKey?: string;
}

export interface GenerationDatabaseView {
  host: string;
  port: number;
  name: string;
  username: string;
  sslMode: string | null;
  passwordConfigured: boolean;
}

export interface GenerationAiView {
  provider: GenerationAiProvider;
  model: string;
  baseUrl: string | null;
  keyConfigured: boolean;
}

export interface GenerationResponse {
  id: string;
  name: string;
  requirement: string;
  description: string | null;
  backend: GenerationBackend;
  frontend: GenerationFrontend;
  database: GenerationDatabase;
  auth: GenerationAuth;
  integrations: GenerationIntegration[];
  databaseConfig: GenerationDatabaseView | null;
  aiConfig: GenerationAiView;
  status: GenerationStatus;
  error: string | null;
  projectId: string | null;
  iteration: number;
  maxIterations: number;
  revisionNumber: number;
  revisionCount: number;
  artifact: ArtifactView | null;
  plan: PlanView | null;
  workflowStats: WorkflowStatsView | null;
  verification: VerificationView | null;
  review: ReviewView | null;
  rating: RatingView | null;
  findings: GenerationFindingView[];
  execution: ExecutionView | null;
  agentWorkflow: AgentStepView[];
  fixRounds: GenerationFixRoundView[];
  fileVersions: GenerationFileVersionView[];
  verificationLevel: 'NOT_RUN' | 'ISSUES_FOUND' | 'SANDBOX_UNAVAILABLE' | 'STATIC' | 'BUILD_AND_TESTS';
  downloadReady: boolean;
  previewReady: boolean;
  languageRuntime: string | null;
  backendCustom: string | null;
  frontendCustom: string | null;
  databaseConfigureLater: boolean;
  answers: GenerationAnswerView[];
  createdAt: string;
  updatedAt: string;
}

export interface GenerationAnswerView {
  questionId: string;
  prompt: string;
  type: string;
  value: string | null;
  skipped: boolean;
  required: boolean;
  secret: boolean;
  configured: boolean;
}

export interface ArtifactView {
  id: string;
  iteration: number;
  fileCount: number;
  totalChars: number;
  sha256: string;
  createdAt: string;
}

export interface PlanView {
  iteration: number;
  fileCount: number;
  architecture: string;
  dependencies: string[];
  directories: string[];
  apis: string;
  steps: string[];
  files: PlanFileView[];
}

export interface PlanFileView {
  path: string;
  purpose: string;
}

/** Step-4 scaffold preview (not persisted, no job created). */
export interface PlanPreviewInput {
  requirement: string;
  backend: GenerationBackend;
  frontend: GenerationFrontend;
  database: GenerationDatabase;
  auth?: GenerationAuth;
  integrations?: GenerationIntegration[];
}

export interface PlanPreviewResponse {
  architecture: string;
  dependencies: string[];
  directories: string[];
  apis: string;
  steps: string[];
  files: PlanFileView[];
  notes: string;
}

export interface GenerationFileView {
  path: string;
  sizeBytes: number;
}

export interface GenerationFileContentView {
  path: string;
  content: string | null;
  truncated: boolean;
  binary: boolean;
}

export type RestructurePreset = 'MVC' | 'LAYERED' | 'FEATURE_FOLDERS' | 'CUSTOM';

export interface RestructureView {
  id: string;
  generationId: string;
  iteration: number;
  preset: string;
  description: string | null;
  status: 'PROPOSED' | 'APPLIED' | 'REVERTED' | 'REJECTED';
  moves: { from: string; to: string }[];
  directories: string[];
  notes: string;
  canRevert: boolean;
  createdAt: string;
}

export interface WorkflowStatsView {
  totalFindings: number;
  openFindings: number;
  fixedFindings: number;
  bugCount: number;
  issueCount: number;
  errorCount: number;
}

export interface VerificationView {
  verdict: string;
  buildStatus: string;
  testsTotal: number;
  testsPassed: number;
  testsFailed: number;
  testsSkipped: number;
  durationMs: number | null;
  logRef: string | null;
}

export interface ReviewView {
  status: string;
  findingCount: number;
  error: string | null;
}

export interface RatingView {
  overall: number;
  syntax: number;
  logic: number;
  security: number;
  style: number;
}

export interface GenerationFindingView {
  id: string;
  file: string | null;
  line: number | null;
  category: string;
  severity: string;
  title: string;
  explanation: string | null;
  status: string;
}

export interface AgentStepView {
  id: string;
  agentType: string;
  status: string;
  durationMs: number | null;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
  activityLog: string | null;
  roundNumber: number;
}

export interface GenerationFixRoundView {
  approvalId: string;
  roundNumber: number;
  findingIds: string[];
  beforeFindingCount: number;
  afterFindingCount: number | null;
  status: string;
  approvedAt: string;
  completedAt: string | null;
}

export interface GenerationFileVersionView {
  id: string;
  roundNumber: number;
  path: string;
  beforeContent: string;
  afterContent: string;
  reverted: boolean;
  createdAt: string;
}

export interface GenerationFixPreviewResponse {
  files: string[];
  findingCount: number;
}

/**
 * Latest sandbox evidence. `notVerified` is true when Docker was disabled and
 * nothing was built or tested — the UI must never render that as success.
 */
export interface ExecutionView {
  command: string;
  exitCode: number;
  buildStatus: string;
  testStatus: string;
  failureReason: string | null;
  durationMs: number;
  notVerified: boolean;
  createdAt: string;
}
