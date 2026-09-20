/**
 * Generation DTO mirrors. Secrets travel client → backend in the create
 * body only; they are never stored locally and never appear in responses.
 */

export type GenerationBackend = 'JAVA_SPRING_BOOT' | 'PYTHON_FASTAPI' | 'NODEJS';
export type GenerationFrontend = 'REACT_TYPESCRIPT' | 'NONE';
export type GenerationDatabase = 'POSTGRESQL' | 'MYSQL' | 'MONGODB' | 'NONE';
export type GenerationAiProvider = 'OPENROUTER' | 'CUSTOM';
export type GenerationStatus =
  | 'DRAFT'
  | 'READY'
  | 'QUEUED'
  | 'PLANNING'
  | 'GENERATING'
  | 'CODING'
  | 'REVIEWING'
  | 'FIXING'
  | 'TESTING'
  | 'VERIFYING'
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
  apiKey: string;
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
  databaseConfig?: GenerationDatabaseInput;
  aiConfig: GenerationAiInput;
  /** Draft mode persists configuration only: secrets optional, nothing dispatched. */
  draft?: boolean;
}

export interface UpdateGenerationInput {
  name?: string;
  requirement?: string;
  description?: string;
  backend?: GenerationBackend;
  frontend?: GenerationFrontend;
  database?: GenerationDatabase;
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
  databaseConfig: GenerationDatabaseView | null;
  aiConfig: GenerationAiView;
  status: GenerationStatus;
  error: string | null;
  projectId: string | null;
  createdAt: string;
  updatedAt: string;
}
