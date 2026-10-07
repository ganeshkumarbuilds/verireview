import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { listFindings, listReviews } from '../api/analysis';
import { listProjects, importGitHub, importPaste, fetchImportLimits, type PasteFileInput, type ImportLimits } from '../api/projects';
import { createGeneration } from '../api/generations';
import type { FindingResponse, ProjectResponse, ReviewResponse } from '../api/types';
import type {
  GenerationAiProvider,
  GenerationBackend,
  GenerationDatabase,
  GenerationFrontend,
} from '../api/generationTypes';
import { apiClient, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Card,
  EmptyState,
  ErrorAlert,
  LoadingState,
  PageHeader,
  Progress,
  SkeletonList,
  inputClass,
  primaryButtonClass,
  selectClass,
  textareaClass,
} from '../components/ui';
import {
  ProjectAvatar,
  formatDate,
  reviewStatusTone,
} from '../components/workflow';

interface ProjectCardData {
  project: ProjectResponse;
  latest: ReviewResponse | null;
  findings: FindingResponse[];
}

const DB_DEFAULT_PORTS: Record<string, string> = {
  POSTGRESQL: '5432',
  MYSQL: '3306',
  MONGODB: '27017',
};

/**
 * Projects workspace: two creation sections only — (1) generate a new project
 * with AI, (2) upload an existing codebase — followed by the project list.
 *
 * The quick generation form collects the same contract as the full wizard
 * (name, requirement incl. tech stack, stack, database config, AI config)
 * through the same `createGeneration` API, then links to the canonical
 * `/generate?genId=` status view where Planner → Coding → Verify → Review
 * progress renders from real backend state. Secrets travel in the request
 * body only and inputs are cleared after submit.
 */
export function ProjectsPage() {
  const { token } = useAuth();
  const [projects, setProjects] = useState<ProjectCardData[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // --- Quick generation form (section 1) ---
  const [genName, setGenName] = useState('');
  const [genRequirement, setGenRequirement] = useState('');
  const [genBackend, setGenBackend] = useState<GenerationBackend | ''>('');
  const [genFrontend, setGenFrontend] = useState<GenerationFrontend | ''>('');
  const [genDatabase, setGenDatabase] = useState<GenerationDatabase | ''>('');
  const [genDbHost, setGenDbHost] = useState('');
  const [genDbPort, setGenDbPort] = useState('');
  const [genDbName, setGenDbName] = useState('');
  const [genDbUser, setGenDbUser] = useState('');
  const [genDbPassword, setGenDbPassword] = useState('');
  const [genAiProvider, setGenAiProvider] = useState<GenerationAiProvider | ''>('');
  const [genApiKey, setGenApiKey] = useState('');
  const [genModel, setGenModel] = useState('');
  const [genBaseUrl, setGenBaseUrl] = useState('');
  const [genBusy, setGenBusy] = useState(false);
  const [genError, setGenError] = useState<string | null>(null);
  const [createdGen, setCreatedGen] = useState<{ id: string; name: string } | null>(null);

  // --- Upload form (section 2) ---
  const [zipName, setZipName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);

  // --- Import limits (fetched from backend) ---
  const [limits, setLimits] = useState<ImportLimits | null>(null);

  // --- GitHub import form (section 3) ---
  const [ghName, setGhName] = useState('');
  const [ghUrl, setGhUrl] = useState('');
  const [ghDescription, setGhDescription] = useState('');
  const [ghLanguage, setGhLanguage] = useState('');
  const [ghBusy, setGhBusy] = useState(false);
  const [ghError, setGhError] = useState<string | null>(null);

  // --- Paste intake form (section 4) ---
  const [pasteName, setPasteName] = useState('');
  const [pasteDescription, setPasteDescription] = useState('');
  const [pasteLanguage, setPasteLanguage] = useState('');
  const [pasteFiles, setPasteFiles] = useState<PasteFileInput[]>([{ path: '', content: '' }]);
  const [pasteBusy, setPasteBusy] = useState(false);
  const [pasteError, setPasteError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    if (!token) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const page = await listProjects(apiClient(), token, { size: 50 });
      setTotal(page.totalElements);
      const enriched = await Promise.all(
        page.content.map(async (project) => {
          let latest: ReviewResponse | null = null;
          let findings: FindingResponse[] = [];
          try {
            const history = await listReviews(apiClient(), token, project.id);
            latest = history.content[0] ?? null;
            if (latest) {
              try {
                findings = (await listFindings(apiClient(), token, latest.id)).content;
              } catch {
                findings = [];
              }
            }
          } catch {
            latest = null;
          }
          return { project, latest, findings };
        }),
      );
      setProjects(enriched);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load projects.');
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    fetchImportLimits(apiClient())
      .then(setLimits)
      .catch(() => {
        // Silently fail; validation will fall back to server-side errors
      });
  }, []);

  const genWantsDb = genDatabase !== '' && genDatabase !== 'NONE';

  const validateQuickForm = (): string | null => {
    if (!genName.trim()) {
      return 'Please enter a project name.';
    }
    if (genName.trim().length > 200) {
      return 'Project name must be 200 characters or fewer.';
    }
    if (genRequirement.trim().length < 20) {
      return 'Please explain your task in at least 20 characters.';
    }
    if (!genBackend || !genFrontend || !genDatabase) {
      return 'Please choose a backend, frontend, and database option.';
    }
    if (genWantsDb) {
      if (!genDbHost.trim()) {
        return 'Please enter the database host.';
      }
      const port = Number(genDbPort);
      if (!genDbPort.trim() || !Number.isInteger(port) || port < 1 || port > 65535) {
        return 'Please enter a valid database port (1–65535).';
      }
      if (!genDbName.trim() || !genDbUser.trim() || !genDbPassword) {
        return 'Please enter the database name, username, and password.';
      }
    }
    if (!genAiProvider) {
      return 'Please choose an AI provider.';
    }
    if (genAiProvider === 'NONE') {
      if (genBackend !== 'JAVA_SPRING_BOOT') {
        return 'The None provider currently supports Java Spring Boot templates only.';
      }
      return null;
    }
    if (!genApiKey) {
      return 'Please enter your AI API key.';
    }
    if (genAiProvider === 'CUSTOM' && !genBaseUrl.trim()) {
      return 'Please enter the base URL for your custom provider.';
    }
    if (!genModel.trim()) {
      return 'Please enter the AI model name.';
    }
    return null;
  };

  const handleQuickGenerate = async (event: FormEvent) => {
    event.preventDefault();
    if (!token || genBusy) {
      return;
    }
    const problem = validateQuickForm();
    if (problem) {
      setGenError(problem);
      return;
    }
    setGenBusy(true);
    setGenError(null);
    setCreatedGen(null);
    try {
      const created = await createGeneration(apiClient(), token, {
        name: genName.trim(),
        requirement: genRequirement.trim(),
        backend: genBackend as GenerationBackend,
        frontend: genFrontend as GenerationFrontend,
        database: genDatabase as GenerationDatabase,
        ...(genWantsDb
          ? {
              databaseConfig: {
                host: genDbHost.trim(),
                port: Number(genDbPort),
                name: genDbName.trim(),
                username: genDbUser.trim(),
                password: genDbPassword,
              },
            }
          : {}),
        aiConfig: genAiProvider === 'NONE'
          ? {
              provider: genAiProvider as GenerationAiProvider,
              model: 'template',
            }
          : {
              provider: genAiProvider as GenerationAiProvider,
              apiKey: genApiKey,
              baseUrl: genBaseUrl.trim() || undefined,
              model: genModel.trim(),
            },
      });
      setCreatedGen({ id: created.id, name: created.name });
      // Secrets are single-use: clear them the moment the run is accepted.
      setGenApiKey('');
      setGenDbPassword('');
    } catch (err) {
      setGenError(err instanceof ApiError ? err.message : 'Could not start generation.');
    } finally {
      setGenBusy(false);
    }
  };

  const handleUpload = async (event: FormEvent) => {
    event.preventDefault();
    if (!token || !file || !zipName.trim()) {
      return;
    }
    // Client-side validation before upload
    if (limits) {
      if (!file.name.toLowerCase().endsWith('.zip')) {
        setError('Only .zip files are accepted.');
        return;
      }
      if (file.size > limits.maxZipBytes) {
        setError(`File size ${formatFileSize(file.size)} exceeds the maximum allowed size of ${limits.maxZipBytesHuman}.`);
        return;
      }
    }
    setBusy(true);
    setUploadProgress(0);
    setError(null);
    try {
      // Use XMLHttpRequest for progress tracking
      await uploadZipWithProgress(apiClient(), token, { file, name: zipName.trim() }, setUploadProgress);
      setZipName('');
      setFile(null);
      setUploadProgress(100);
      await reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Upload failed.');
    } finally {
      setBusy(false);
      setUploadProgress(0);
    }
  };

  const uploadZipWithProgress = (
    client: ReturnType<typeof apiClient>,
    token: string,
    input: { file: File; name: string; description?: string; language?: string },
    onProgress: (progress: number) => void
  ): Promise<ProjectResponse> => {
    return new Promise((resolve, reject) => {
      const form = new FormData();
      form.append('file', input.file);
      form.append('name', input.name);
      if (input.description) {
        form.append('description', input.description);
      }
      if (input.language) {
        form.append('language', input.language);
      }

      const xhr = new XMLHttpRequest();
      xhr.open('POST', client.buildUrl('/projects/import/zip'));
      xhr.setRequestHeader('Authorization', `Bearer ${token}`);

      xhr.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable) {
          onProgress(Math.round((event.loaded / event.total) * 100));
        }
      });

      xhr.addEventListener('load', () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            const response = JSON.parse(xhr.responseText);
            resolve(response);
          } catch {
            reject(new ApiError(xhr.status, 'parse_error', 'Invalid response from server'));
          }
        } else {
          let message = `Request failed with status ${xhr.status}.`;
          let code = `HTTP_${xhr.status}`;
          try {
            const errorBody = JSON.parse(xhr.responseText);
            message = errorBody.error?.message ?? message;
            code = errorBody.error?.code ?? code;
          } catch {
            // Use default message
          }
          reject(new ApiError(xhr.status, code, message));
        }
      });

      xhr.addEventListener('error', () => {
        reject(new ApiError(0, 'network_error', 'Network error occurred'));
      });

      xhr.addEventListener('abort', () => {
        reject(new ApiError(0, 'aborted', 'Upload aborted'));
      });

      xhr.send(form);
    });
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes >= 1024 * 1024 * 1024) {
      return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    }
    if (bytes >= 1024 * 1024) {
      return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    }
    if (bytes >= 1024) {
      return `${(bytes / 1024).toFixed(1)} KB`;
    }
    return `${bytes} bytes`;
  };

  const handleGitHubImport = async (event: FormEvent) => {
    event.preventDefault();
    if (!token || !ghName.trim() || !ghUrl.trim()) {
      return;
    }
    setGhBusy(true);
    setGhError(null);
    try {
      await importGitHub(apiClient(), token, {
        name: ghName.trim(),
        url: ghUrl.trim(),
        description: ghDescription.trim() || undefined,
        language: ghLanguage.trim() || undefined,
      });
      setGhName('');
      setGhUrl('');
      setGhDescription('');
      setGhLanguage('');
      await reload();
    } catch (err) {
      setGhError(err instanceof ApiError ? err.message : 'GitHub import failed.');
    } finally {
      setGhBusy(false);
    }
  };

  const addPasteFile = () => {
    setPasteFiles([...pasteFiles, { path: '', content: '' }]);
  };

  const removePasteFile = (index: number) => {
    if (pasteFiles.length <= 1) return;
    setPasteFiles(pasteFiles.filter((_, i) => i !== index));
  };

  const updatePasteFile = (index: number, field: 'path' | 'content', value: string) => {
    setPasteFiles(pasteFiles.map((f, i) => (i === index ? { ...f, [field]: value } : f)));
  };

  const handlePasteImport = async (event: FormEvent) => {
    event.preventDefault();
    if (!token || !pasteName.trim() || pasteFiles.some(f => !f.path.trim() || !f.content.trim())) {
      return;
    }
    setPasteBusy(true);
    setPasteError(null);
    try {
      await importPaste(apiClient(), token, {
        name: pasteName.trim(),
        description: pasteDescription.trim() || undefined,
        language: pasteLanguage.trim() || undefined,
        files: pasteFiles.map(f => ({ path: f.path.trim(), content: f.content })),
      });
      setPasteName('');
      setPasteDescription('');
      setPasteLanguage('');
      setPasteFiles([{ path: '', content: '' }]);
      await reload();
    } catch (err) {
      setPasteError(err instanceof ApiError ? err.message : 'Paste import failed.');
    } finally {
      setPasteBusy(false);
    }
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title="Projects"
        description="Generate a new application with AI, or bring an existing codebase into VeriReview for analysis and review."
        actions={
          <Link to="/generate" className={primaryButtonClass}>
            Generate project
          </Link>
        }
      />
      {error && <ErrorAlert message={error} onRetry={() => void reload()} />}

      <Card
        title="1 · Generate a new project"
        subtitle="Describe your task and tech stack, add database and AI credentials, then follow Planner → Coding → Verify → Review progress."
      >
        {createdGen ? (
          <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3">
            <p className="text-sm font-semibold text-emerald-900">
              Generation started for “{createdGen.name}”.
            </p>
            <p className="mt-1 text-sm leading-relaxed text-emerald-800">
              Watch the Planner, Coding, Verify, and Review agents work through the real
              pipeline — findings, fix approval, and the verified download all live there.
            </p>
            <Link
              to={`/generate?genId=${encodeURIComponent(createdGen.id)}`}
              className={`${primaryButtonClass} mt-3 inline-flex`}
            >
              View generation progress
            </Link>
          </div>
        ) : (
          <form onSubmit={handleQuickGenerate} aria-label="Quick generation form" className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Project name
                </p>
                <input
                  aria-label="Quick project name"
                  value={genName}
                  onChange={(event) => setGenName(event.target.value)}
                  placeholder="e.g. todo-api"
                  maxLength={200}
                  autoComplete="off"
                  className={inputClass}
                />
              </div>
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Backend
                  </p>
                  <select
                    aria-label="Quick backend stack"
                    value={genBackend}
                    onChange={(event) => setGenBackend(event.target.value as GenerationBackend | '')}
                    className={selectClass}
                  >
                    <option value="">Select…</option>
                    <option value="JAVA_SPRING_BOOT">Java Spring Boot</option>
                    <option value="PYTHON_FASTAPI">Python FastAPI</option>
                    <option value="NODEJS">Node.js</option>
                  </select>
                </div>
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Frontend
                  </p>
                  <select
                    aria-label="Quick frontend stack"
                    value={genFrontend}
                    onChange={(event) => setGenFrontend(event.target.value as GenerationFrontend | '')}
                    className={selectClass}
                  >
                    <option value="">Select…</option>
                    <option value="REACT_TYPESCRIPT">React + TS</option>
                    <option value="NONE">None</option>
                  </select>
                </div>
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Database
                  </p>
                  <select
                    aria-label="Quick database"
                    value={genDatabase}
                    onChange={(event) => {
                      const next = event.target.value as GenerationDatabase | '';
                      setGenDatabase(next);
                      if (next !== '' && next !== 'NONE' && !genDbPort) {
                        setGenDbPort(DB_DEFAULT_PORTS[next] ?? '');
                      }
                    }}
                    className={selectClass}
                  >
                    <option value="">Select…</option>
                    <option value="POSTGRESQL">PostgreSQL</option>
                    <option value="MYSQL">MySQL</option>
                    <option value="MONGODB">MongoDB</option>
                    <option value="NONE">None</option>
                  </select>
                </div>
              </div>
            </div>
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Task / project description
              </p>
              <textarea
                aria-label="Quick project task"
                value={genRequirement}
                onChange={(event) => setGenRequirement(event.target.value)}
                placeholder="Explain your task or project along with your tech stack: what should it do, which endpoints and data model do you need, which frontend, backend, and database should it use…"
                rows={4}
                maxLength={20000}
                className={inputClass}
              />
              <p className="mt-1 text-right text-xs tabular-nums text-slate-400">
                {genRequirement.trim().length}/20 minimum
              </p>
            </div>
            {genWantsDb && (
              <div className="grid gap-3 rounded-xl border border-indigo-100 bg-indigo-50/40 p-3 sm:grid-cols-3">
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    DB host
                  </p>
                  <input
                    aria-label="Quick database host"
                    value={genDbHost}
                    onChange={(event) => setGenDbHost(event.target.value)}
                    placeholder="e.g. localhost"
                    maxLength={500}
                    autoComplete="off"
                    className={inputClass}
                  />
                </div>
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    DB port
                  </p>
                  <input
                    aria-label="Quick database port"
                    value={genDbPort}
                    onChange={(event) => setGenDbPort(event.target.value)}
                    placeholder="e.g. 5432"
                    inputMode="numeric"
                    autoComplete="off"
                    className={inputClass}
                  />
                </div>
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    DB name
                  </p>
                  <input
                    aria-label="Quick database name"
                    value={genDbName}
                    onChange={(event) => setGenDbName(event.target.value)}
                    placeholder="e.g. todos"
                    maxLength={200}
                    autoComplete="off"
                    className={inputClass}
                  />
                </div>
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    DB username
                  </p>
                  <input
                    aria-label="Quick database username"
                    value={genDbUser}
                    onChange={(event) => setGenDbUser(event.target.value)}
                    placeholder="e.g. app"
                    maxLength={200}
                    autoComplete="off"
                    className={inputClass}
                  />
                </div>
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    DB password
                  </p>
                  <input
                    aria-label="Quick database password"
                    type="password"
                    value={genDbPassword}
                    onChange={(event) => setGenDbPassword(event.target.value)}
                    placeholder="Sent once — never stored"
                    maxLength={500}
                    autoComplete="new-password"
                    className={inputClass}
                  />
                </div>
                <div className="flex items-end">
                  <p className="text-xs leading-relaxed text-slate-500">
                    Credentials travel in the request body only and are cleared after submit.
                  </p>
                </div>
              </div>
            )}
            <div className="grid gap-3 rounded-xl border border-indigo-100 bg-indigo-50/40 p-3 sm:grid-cols-4">
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  AI provider
                </p>
                <select
                  aria-label="Quick AI provider"
                  value={genAiProvider}
                  onChange={(event) => setGenAiProvider(event.target.value as GenerationAiProvider | '')}
                  className={selectClass}
                >
                  <option value="">Select…</option>
                  <option value="OPENROUTER">OpenRouter</option>
                  <option value="CUSTOM">Custom</option>
                  <option value="NONE">None — template</option>
                </select>
              </div>
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  AI model
                </p>
                <input
                  aria-label="Quick AI model"
                  value={genModel}
                  onChange={(event) => setGenModel(event.target.value)}
                  placeholder={genAiProvider === 'NONE' ? 'template (automatic)' : 'e.g. openai/gpt-4o-mini'}
                  maxLength={200}
                  autoComplete="off"
                  disabled={genAiProvider === 'NONE'}
                  className={inputClass}
                />
              </div>
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  AI API key
                </p>
                <input
                  aria-label="Quick AI API key"
                  type="password"
                  value={genApiKey}
                  onChange={(event) => setGenApiKey(event.target.value)}
                  placeholder={genAiProvider === 'NONE' ? 'Not required' : 'Sent once — never stored'}
                  maxLength={2000}
                  autoComplete="off"
                  disabled={genAiProvider === 'NONE'}
                  className={inputClass}
                />
              </div>
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Base URL {genAiProvider === 'CUSTOM' ? '' : '(custom only)'}
                </p>
                <input
                  aria-label="Quick AI base URL"
                  value={genBaseUrl}
                  onChange={(event) => setGenBaseUrl(event.target.value)}
                  placeholder="https://…"
                  maxLength={500}
                  inputMode="url"
                  autoComplete="off"
                  disabled={genAiProvider !== 'CUSTOM'}
                  className={inputClass}
                />
              </div>
            </div>
            {genError && (
              <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                {genError}
              </p>
            )}
            <div className="flex flex-wrap items-center gap-2">
              <button type="submit" disabled={genBusy} className={primaryButtonClass}>
                {genBusy ? 'Starting…' : 'Generate project'}
              </button>
              <Link to="/generate" className="text-sm font-semibold text-indigo-700 hover:text-indigo-900">
                Need drafts or fine-tuning? Open the full wizard →
              </Link>
            </div>
            <div className="flex flex-wrap gap-2 text-xs font-semibold text-indigo-700">
              <span className="rounded-full bg-indigo-50 px-2.5 py-1 ring-1 ring-indigo-100">Plan</span>
              <span className="rounded-full bg-indigo-50 px-2.5 py-1 ring-1 ring-indigo-100">Generate</span>
              <span className="rounded-full bg-indigo-50 px-2.5 py-1 ring-1 ring-indigo-100">Build &amp; Test</span>
              <span className="rounded-full bg-indigo-50 px-2.5 py-1 ring-1 ring-indigo-100">Verify</span>
              <span className="rounded-full bg-indigo-50 px-2.5 py-1 ring-1 ring-indigo-100">Review</span>
            </div>
          </form>
        )}
      </Card>

      <Card
        title="2 · Upload an existing codebase"
        subtitle={limits
          ? `Import a codebase snapshot for sandboxed analysis. Maximum ${limits.maxZipBytesHuman} and ${limits.maxFiles.toLocaleString()} files.`
          : 'Import a codebase snapshot for sandboxed analysis. Loading limits…'}
      >
        <form onSubmit={handleUpload} aria-label="Upload ZIP form" className="space-y-3">
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Project name
            </p>
            <input
              aria-label="Upload project name"
              value={zipName}
              onChange={(event) => setZipName(event.target.value)}
              placeholder="e.g. billing-service"
              maxLength={200}
              className={inputClass}
              disabled={busy}
            />
          </div>
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              ZIP file
            </p>
            <input
              aria-label="ZIP file"
              type="file"
              accept=".zip"
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              className="w-full text-sm text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-indigo-50 file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-indigo-700 file:transition-colors hover:file:bg-indigo-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
              disabled={busy}
            />
            {file && (
              <p className="mt-1 truncate text-xs text-slate-500">
                Selected: {file.name} · {formatFileSize(file.size)}
              </p>
            )}
            <p className="mt-1 text-xs text-slate-500">
              Tip: exclude node_modules, .git, build outputs before zipping.
            </p>
          </div>
          {busy && uploadProgress > 0 && (
            <Progress value={uploadProgress} className="h-2" />
          )}
          {error && (
            <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
              {error}
            </p>
          )}
          <button
            type="submit"
            disabled={busy || !file || !zipName.trim()}
            className={primaryButtonClass}
          >
            {busy ? `Uploading… ${uploadProgress}%` : 'Upload and import'}
          </button>
        </form>
      </Card>

      <Card
        title="3 · Import from GitHub"
        subtitle={limits
          ? `Shallow-clone a public GitHub repository (github.com only). Maximum ${limits.maxFiles.toLocaleString()} files and ${limits.maxTotalUncompressedBytesHuman} total.`
          : 'Shallow-clone a public GitHub repository (github.com only). Loading limits…'}
      >
        <form onSubmit={handleGitHubImport} aria-label="GitHub import form" className="space-y-3">
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Project name
            </p>
            <input
              aria-label="GitHub project name"
              value={ghName}
              onChange={(event) => setGhName(event.target.value)}
              placeholder="e.g. awesome-project"
              maxLength={200}
              className={inputClass}
            />
          </div>
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              GitHub repository URL
            </p>
            <input
              aria-label="GitHub URL"
              value={ghUrl}
              onChange={(event) => setGhUrl(event.target.value)}
              placeholder="https://github.com/owner/repo"
              maxLength={500}
              inputMode="url"
              autoComplete="off"
              className={inputClass}
            />
            <p className="mt-1 text-xs text-slate-500">
              Public repositories only. Cloned with depth=1 (latest commit only).
            </p>
          </div>
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Description (optional)
            </p>
            <textarea
              aria-label="GitHub import description"
              value={ghDescription}
              onChange={(event) => setGhDescription(event.target.value)}
              placeholder="Brief description of the codebase"
              rows={2}
              maxLength={5000}
              className={textareaClass}
            />
          </div>
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Primary language (optional)
            </p>
            <input
              aria-label="GitHub import language"
              value={ghLanguage}
              onChange={(event) => setGhLanguage(event.target.value)}
              placeholder="e.g. java, python, typescript"
              maxLength={100}
              className={inputClass}
            />
          </div>
          {ghError && (
            <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
              {ghError}
            </p>
          )}
          <button
            type="submit"
            disabled={ghBusy || !ghName.trim() || !ghUrl.trim()}
            className={primaryButtonClass}
          >
            {ghBusy ? 'Importing…' : 'Import from GitHub'}
          </button>
        </form>
      </Card>

      <Card
        title="4 · Paste code files"
        subtitle={limits
          ? `Manually add files by pasting their path and content. Maximum ${limits.maxFiles.toLocaleString()} files, ${limits.maxSingleFileBytesHuman} per file, ${limits.maxTotalUncompressedBytesHuman} total.`
          : 'Manually add files by pasting their path and content. Loading limits…'}
      >
        <form onSubmit={handlePasteImport} aria-label="Paste import form" className="space-y-3">
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Project name
            </p>
            <input
              aria-label="Paste project name"
              value={pasteName}
              onChange={(event) => setPasteName(event.target.value)}
              placeholder="e.g. my-pasted-code"
              maxLength={200}
              className={inputClass}
            />
          </div>
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Description (optional)
            </p>
            <textarea
              aria-label="Paste import description"
              value={pasteDescription}
              onChange={(event) => setPasteDescription(event.target.value)}
              placeholder="Brief description of the codebase"
              rows={2}
              maxLength={5000}
              className={textareaClass}
            />
          </div>
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Primary language (optional)
            </p>
            <input
              aria-label="Paste import language"
              value={pasteLanguage}
              onChange={(event) => setPasteLanguage(event.target.value)}
              placeholder="e.g. java, python, typescript"
              maxLength={100}
              className={inputClass}
            />
          </div>
          <div>
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
              Files
            </p>
            <div className="space-y-2">
              {pasteFiles.map((file, index) => (
                <div key={index} className="flex flex-col sm:flex-row gap-2">
                  <input
                    aria-label={`File ${index + 1} path`}
                    value={file.path}
                    onChange={(event) => updatePasteFile(index, 'path', event.target.value)}
                    placeholder={`File path (e.g. src/main/java/App.java)`}
                    maxLength={500}
                    className={inputClass}
                  />
                  <textarea
                    aria-label={`File ${index + 1} content`}
                    value={file.content}
                    onChange={(event) => updatePasteFile(index, 'content', event.target.value)}
                    placeholder={`File content`}
                    rows={4}
                    maxLength={1000000}
                    className={textareaClass}
                  />
                  {pasteFiles.length > 1 && (
                    <button
                      type="button"
                      onClick={() => removePasteFile(index)}
                      aria-label={`Remove file ${index + 1}`}
                      className="self-start text-sm text-red-600 hover:text-red-800"
                    >
                      Remove
                    </button>
                  )}
                </div>
              ))}
            </div>
            <button
              type="button"
              onClick={addPasteFile}
              className="text-sm font-semibold text-indigo-700 hover:text-indigo-900"
            >
              + Add another file
            </button>
          </div>
          {pasteError && (
            <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
              {pasteError}
            </p>
          )}
          <button
            type="submit"
            disabled={pasteBusy || !pasteName.trim() || pasteFiles.some(f => !f.path.trim() || !f.content.trim())}
            className={primaryButtonClass}
          >
            {pasteBusy ? 'Importing…' : 'Import pasted files'}
          </button>
        </form>
      </Card>

      <Card
        title={`Your projects (${total})`}
        subtitle="Status, finding summary, and last analysis — select a project to open its workspace."
      >
        {loading ? (
          <>
            <LoadingState label="Loading…" />
            <SkeletonList rows={4} />
          </>
        ) : projects.length === 0 ? (
          <EmptyState
            title="No projects yet."
            body="Generate a new project above, or upload an existing codebase."
          />
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2">
            {projects.map(({ project, latest, findings }) => {
              const critHigh = findings.filter(
                (finding) => finding.severity === 'CRITICAL' || finding.severity === 'HIGH',
              ).length;
              return (
                <li
                  key={project.id}
                  className="flex flex-col rounded-xl border border-indigo-100 bg-white p-4 shadow-sm shadow-indigo-100/50 transition-colors hover:border-indigo-200"
                >
                  <div className="flex items-start gap-3">
                    <ProjectAvatar name={project.name} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-bold text-indigo-950">{project.name}</p>
                      <p className="mt-0.5 truncate text-xs text-slate-500">
                        {project.language ?? project.sourceType} · {project.fileCount} files
                      </p>
                    </div>
                    {latest ? (
                      <Badge tone={reviewStatusTone(latest.status)}>{latest.status}</Badge>
                    ) : (
                      <Badge tone="gray">NEVER ANALYZED</Badge>
                    )}
                  </div>
                  <p className="mt-3 text-xs leading-relaxed text-slate-600">
                    {latest ? (
                      <>
                        {latest.findingCount} findings
                        {findings.length > 0 && ` · ${critHigh} crit/high`} · last run{' '}
                        {formatDate(latest.createdAt)}
                      </>
                    ) : (
                      'No analysis yet — open the project and run your first analysis.'
                    )}
                  </p>
                  <div className="mt-3 flex items-center gap-2 border-t border-indigo-50 pt-3">
                    <Link
                      to={`/projects/${project.id}`}
                      aria-label={`Open project ${project.name}`}
                      className={primaryButtonClass}
                    >
                      Open
                    </Link>
                    {latest && (
                      <Link
                        to={`/review?project=${project.id}`}
                        aria-label={`Review findings for ${project.name}`}
                        className="rounded-lg px-3 py-2 text-sm font-semibold text-indigo-700 transition-colors hover:bg-indigo-50 hover:text-indigo-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                      >
                        Review findings
                      </Link>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>
    </div>
  );
}
