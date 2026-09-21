import { useEffect, useMemo, useState } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import {
  createGeneration,
  createGenerationFixRequests,
  getGeneration,
  isTerminalGeneration,
  startGeneration as startSavedGeneration,
  updateGeneration,
  downloadGeneration,
} from '../api/generations';
import type {
  CreateGenerationInput,
  GenerationAiProvider,
  GenerationBackend,
  GenerationDatabase,
  GenerationFrontend,
  GenerationResponse,
} from '../api/generationTypes';
import { apiClient, useAuth } from '../auth/AuthContext';
import {
  Badge,
  Card,
  LoadingState,
  PageHeader,
  inputClass,
  primaryButtonClass,
  secondaryButtonClass,
  selectClass,
} from '../components/ui';
import { WorkspaceCrumb } from '../components/workflow';
import {
  getPipelineStageIconClass,
  getPipelineStageLabelClass,
} from '../components/ui';

const POLL_MS = 3000;
const DEFAULT_MODEL = 'openai/gpt-4o-mini';

const BACKEND_LABELS: Record<GenerationBackend, string> = {
  JAVA_SPRING_BOOT: 'Java Spring Boot',
  PYTHON_FASTAPI: 'Python FastAPI',
  NODEJS: 'Node.js',
};

const FRONTEND_LABELS: Record<GenerationFrontend, string> = {
  REACT_TYPESCRIPT: 'React + TypeScript',
  NONE: 'No frontend',
};

const DATABASE_INFO: Record<
  GenerationDatabase,
  { label: string; port: string; user: string }
> = {
  POSTGRESQL: { label: 'PostgreSQL', port: '5432', user: 'postgres' },
  MYSQL: { label: 'MySQL', port: '3306', user: 'root' },
  MONGODB: { label: 'MongoDB', port: '27017', user: 'admin' },
  NONE: { label: 'No database', port: '', user: '' },
};

const PIPELINE_STAGES = [
  { key: 'planning', label: 'Planner Agent', statuses: ['PLANNING'] },
  { key: 'coding', label: 'Coding Agent', statuses: ['CODING', 'GENERATING'] },
  { key: 'build', label: 'Build & Test', statuses: ['BUILDING', 'TESTING'] },
  { key: 'verified', label: 'Verified Agent', statuses: ['VERIFYING', 'REVERIFYING', 'VERIFIED'] },
  { key: 'review', label: 'Review Agent', statuses: ['REVIEWING', 'REVIEWED'] },
];

const STAGE_NUMBERS = ['①', '②', '③', '④', '⑤'];

const STATUS_ORDER = [
  'QUEUED', 'PLANNING', 'CODING', 'BUILDING', 'TESTING',
  'VERIFYING', 'VERIFIED', 'REVIEWING', 'REVIEWED', 'COMPLETED',
];

const PLACEHOLDER =
  'Explain your idea along with the tech stack. Example: "A task tracker where users sign up, create projects and assign tasks. Use React + TypeScript for the frontend, Spring Boot for the backend and PostgreSQL for the database."';

/**
 * The user never picks the stack. It is read from the description; when the
 * description does not name a technology a sensible default is used. The
 * detected stack is shown live under the textarea so nothing is hidden.
 */
function detectStack(text: string): {
  backend: GenerationBackend;
  frontend: GenerationFrontend;
  database: GenerationDatabase;
} {
  const t = text.toLowerCase();

  let backend: GenerationBackend = 'JAVA_SPRING_BOOT';
  if (/fastapi|python|django|flask/.test(t)) {
    backend = 'PYTHON_FASTAPI';
  } else if (/node|express|nestjs|nest\.js/.test(t)) {
    backend = 'NODEJS';
  }

  let frontend: GenerationFrontend = 'REACT_TYPESCRIPT';
  if (/(no|without)\s+(a\s+)?(frontend|front-end|ui)|api[\s-]only|backend[\s-]only|rest api only/.test(t)) {
    frontend = 'NONE';
  }

  let database: GenerationDatabase = 'NONE';
  if (/(no|without)\s+(a\s+)?(database|db)|in-memory/.test(t)) {
    database = 'NONE';
  } else if (/postgres/.test(t)) {
    database = 'POSTGRESQL';
  } else if (/mysql|mariadb/.test(t)) {
    database = 'MYSQL';
  } else if (/mongo/.test(t)) {
    database = 'MONGODB';
  } else if (/database|\bdb\b|sql|persist|store data|crud/.test(t)) {
    database = 'POSTGRESQL';
  }

  return { backend, frontend, database };
}

function slugify(value: string): string {
  const slug = value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return slug || 'app_db';
}

function statusTone(status: string): 'gray' | 'blue' | 'green' | 'red' {
  switch (status) {
    case 'COMPLETED':
      return 'green';
    case 'FAILED':
      return 'red';
    case 'CANCELLED':
      return 'gray';
    default:
      return 'blue';
  }
}

function Chip({ children }: { children: string }) {
  return (
    <span className="rounded-full bg-indigo-50 px-3 py-1 text-xs font-semibold text-indigo-700 ring-1 ring-inset ring-indigo-600/20">
      {children}
    </span>
  );
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: 'slate' | 'emerald' | 'amber';
}) {
  const color =
    tone === 'emerald' ? 'text-emerald-600' : tone === 'amber' ? 'text-amber-600' : 'text-slate-800';
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 text-center shadow-sm">
      <p className={`text-3xl font-bold tabular-nums ${color}`}>{value}</p>
      <p className="mt-1 text-xs font-semibold uppercase tracking-wider text-slate-500">{label}</p>
    </div>
  );
}

/**
 * Generate Project page. The user supplies a title and one free-text
 * description (idea + tech stack). The stack is detected from that text.
 * Database password and AI API key are optional. Secrets live in component
 * state only — never in URLs, localStorage, logs, or generated code.
 * Progress, findings and download availability come from real backend state.
 */
export function GeneratePage() {
  const { token } = useAuth();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [dbPassword, setDbPassword] = useState('');
  const [aiKey, setAiKey] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [aiProvider, setAiProvider] = useState<GenerationAiProvider>('OPENROUTER');
  const [aiModel, setAiModel] = useState(DEFAULT_MODEL);
  const [aiBaseUrl, setAiBaseUrl] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [generationId, setGenerationId] = useState<string | null>(null);
  const [generation, setGeneration] = useState<GenerationResponse | null>(null);

  const [draftRequirement, setDraftRequirement] = useState<string | null>(null);
  const [taskFeedback, setTaskFeedback] = useState<{ kind: 'saved' | 'error'; text: string } | null>(null);
  const [savingTask, setSavingTask] = useState(false);
  const [startPassword, setStartPassword] = useState('');
  const [startApiKey, setStartApiKey] = useState('');
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);

  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [fixing, setFixing] = useState(false);
  const [fixError, setFixError] = useState<string | null>(null);
  const [fixResult, setFixResult] = useState<{ created: number; skippedOpen: number } | null>(null);
  const [searchParams] = useSearchParams();

  const stack = useMemo(() => detectStack(description), [description]);
  const wantsDb = stack.database !== 'NONE';
  const dbInfo = DATABASE_INFO[stack.database];

  function validate(): string | null {
    if (!name.trim()) {
      return 'Please enter a project title.';
    }
    if (name.trim().length > 200) {
      return 'Project title must be 200 characters or fewer.';
    }
    if (description.trim().length < 20) {
      return 'Please describe your idea in at least 20 characters.';
    }
    if (description.length > 20000) {
      return 'Description must be 20000 characters or fewer.';
    }
    return null;
  }

  const buildInput = (forDraft: boolean): CreateGenerationInput => {
    const input: CreateGenerationInput = {
      name: name.trim(),
      requirement: description.trim(),
      backend: stack.backend,
      frontend: stack.frontend,
      database: stack.database,
      aiConfig: {
        // Skipped key → NONE provider (template fallback) per GenerationAiInput.
        provider: !forDraft && !aiKey.trim() ? 'NONE' : aiProvider,
        apiKey: forDraft ? undefined : aiKey.trim() || undefined,
        baseUrl: aiProvider === 'CUSTOM' ? aiBaseUrl.trim() || undefined : undefined,
        model: aiModel.trim() || DEFAULT_MODEL,
      },
    };
    if (forDraft) {
      input.draft = true;
    }
    if (wantsDb) {
      input.databaseConfig = {
        host: 'localhost',
        port: Number(dbInfo.port),
        name: slugify(name),
        username: dbInfo.user,
        password: forDraft ? '' : dbPassword,
      };
    }
    return input;
  };

  const submit = async (draft: boolean) => {
    if (!token || submitting) {
      return;
    }
    const problem = validate();
    if (problem) {
      setFormError(problem);
      return;
    }
    if (aiProvider === 'CUSTOM' && !aiBaseUrl.trim()) {
      setFormError('Please enter the base URL for your custom provider.');
      return;
    }
    if (!draft && !aiKey.trim() && stack.backend !== 'JAVA_SPRING_BOOT') {
      setFormError(
        'An AI API key is required for this stack. Skipping the key only works for Java Spring Boot projects.',
      );
      return;
    }
    setFormError(null);
    setSubmitting(true);
    setSubmitError(null);
    try {
      const created = await createGeneration(apiClient(), token, buildInput(draft));
      setGenerationId(created.id);
      setGeneration(created);
      if (draft) {
        setDraftRequirement(null);
        setTaskFeedback(null);
      }
    } catch (err) {
      setSubmitError(
        err instanceof ApiError
          ? err.message
          : draft
            ? 'Could not save the draft.'
            : 'Could not start generation.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  const saveTask = async () => {
    if (!token || !generation || savingTask || draftRequirement === null) {
      return;
    }
    const next = draftRequirement.trim();
    if (next.length < 20) {
      setTaskFeedback({ kind: 'error', text: 'Please describe the idea in at least 20 characters.' });
      return;
    }
    if (next.length > 20000) {
      setTaskFeedback({ kind: 'error', text: 'Description must be 20000 characters or fewer.' });
      return;
    }
    setSavingTask(true);
    setTaskFeedback(null);
    try {
      const updated = await updateGeneration(apiClient(), token, generation.id, {
        requirement: next,
      });
      setGeneration(updated);
      setDraftRequirement(null);
      setTaskFeedback({ kind: 'saved', text: 'Saved.' });
    } catch (err) {
      setTaskFeedback({
        kind: 'error',
        text: err instanceof ApiError ? err.message : 'Could not save.',
      });
    } finally {
      setSavingTask(false);
    }
  };

  const startDraft = async () => {
    if (!token || !generation || starting) {
      return;
    }
    setStarting(true);
    setStartError(null);
    try {
      const started = await startSavedGeneration(apiClient(), token, generation.id, {
        password: generation.database !== 'NONE' ? startPassword : undefined,
        apiKey: startApiKey,
      });
      setGeneration(started);
      setStartPassword('');
      setStartApiKey('');
    } catch (err) {
      setStartError(
        err instanceof ApiError ? err.message : 'Could not start generation.',
      );
    } finally {
      setStarting(false);
    }
  };

  const handleDownload = async () => {
    if (!token || !generation || downloading) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      const blob = await downloadGeneration(apiClient(), token, generation.id);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${generation.name}.zip`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      setDownloadError(err instanceof ApiError ? err.message : 'Download failed.');
    } finally {
      setDownloading(false);
    }
  };

  /**
   * User-approved entry to the fix loop. Creates a FixRequest per open
   * finding through the backend approval gate — no source code is changed
   * here. Patches are proposed and applied per finding through the existing
   * fix/patch flow, followed by rebuild + reverify + rereview.
   */
  const handleFixIssues = async () => {
    if (!token || !generation || fixing) return;
    setFixing(true);
    setFixError(null);
    setFixResult(null);
    try {
      const result = await createGenerationFixRequests(apiClient(), token, generation.id);
      setFixResult({ created: result.created.length, skippedOpen: result.skippedOpen });
      setGeneration(await getGeneration(apiClient(), token, generation.id));
    } catch (err) {
      setFixError(err instanceof ApiError ? err.message : 'Could not create fix requests.');
    } finally {
      setFixing(false);
    }
  };

  // Deep-link: /generate?genId=<id> loads an existing generation.
  useEffect(() => {
    if (!token || generationId) {
      return undefined;
    }
    const linked = searchParams.get('genId');
    if (!linked) {
      return undefined;
    }
    let cancelled = false;
    void (async () => {
      try {
        const existing = await getGeneration(apiClient(), token, linked);
        if (!cancelled) {
          setGenerationId(existing.id);
          setGeneration(existing);
        }
      } catch {
        // Stay on the form: an unknown id must not blank the page.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, generationId, searchParams]);

  // Poll only on identity/status transitions (depending on the `generation`
  // object itself would re-fire on every poll and loop forever).
  const generationStatus = generation?.status ?? null;
  useEffect(() => {
    if (!token || !generationId) {
      return undefined;
    }
    if (
      generationStatus === 'DRAFT' ||
      (generationStatus !== null && isTerminalGeneration(generationStatus))
    ) {
      return undefined;
    }
    let cancelled = false;
    const poll = async () => {
      try {
        const fresh = await getGeneration(apiClient(), token, generationId);
        if (!cancelled) {
          setGeneration(fresh);
        }
      } catch {
        // Keep polling: a transient read failure must not kill the run view.
      }
    };
    void poll();
    const timer = window.setInterval(() => void poll(), POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [token, generationId, generationStatus]);

  const running = generationId !== null;
  const isDraft = generation?.status === 'DRAFT';
  const terminal = generation !== null && isTerminalGeneration(generation.status);
  const failed = generation?.status === 'FAILED';
  const statusIndex = STATUS_ORDER.indexOf(generation?.status ?? '');
  const stats = generation?.workflowStats;
  const openFindings = stats?.openFindings ?? 0;
  const reviewDone =
    generation?.status === 'REVIEWED' ||
    generation?.status === 'COMPLETED' ||
    generation?.status === 'VERIFIED';

  return (
    <div className="space-y-6">
      <WorkspaceCrumb items={[{ label: 'Generate project' }]} />
      <PageHeader
        title="Generate a verified project"
        description="Describe your idea and tech stack. Planner, Coding, Verified and Review agents build it, check every file, and report real issue counts."
      />

      {!running && (
        <Card title="Your project" subtitle="Just a title and a description — the stack is detected for you.">
          <div className="space-y-5">
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Project title
              </p>
              <input
                aria-label="Project title"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="e.g. Task Tracker"
                maxLength={200}
                className={inputClass}
              />
            </div>

            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Description
              </p>
              <textarea
                aria-label="Project description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                placeholder={PLACEHOLDER}
                rows={8}
                maxLength={20000}
                className={inputClass}
              />
              <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2" aria-label="Detected stack">
                  <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                    Detected
                  </span>
                  <Chip>{BACKEND_LABELS[stack.backend]}</Chip>
                  <Chip>{FRONTEND_LABELS[stack.frontend]}</Chip>
                  <Chip>{dbInfo.label}</Chip>
                </div>
                <p className="text-xs tabular-nums text-slate-400">
                  {description.trim().length}/20 minimum
                </p>
              </div>
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              {wantsDb && (
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Database password <span className="font-normal normal-case">(optional)</span>
                  </p>
                  <input
                    aria-label="Database password"
                    type="password"
                    value={dbPassword}
                    onChange={(event) => setDbPassword(event.target.value)}
                    placeholder="Leave blank to skip"
                    maxLength={500}
                    autoComplete="new-password"
                    className={inputClass}
                  />
                  <p className="mt-1 text-xs text-slate-400">
                    Used for this run only, never stored.
                  </p>
                </div>
              )}
              <div className={wantsDb ? '' : 'sm:col-span-2'}>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  AI API key <span className="font-normal normal-case">(optional)</span>
                </p>
                <input
                  aria-label="AI API key"
                  type="password"
                  value={aiKey}
                  onChange={(event) => setAiKey(event.target.value)}
                  placeholder="Leave blank to skip"
                  maxLength={2000}
                  autoComplete="off"
                  className={inputClass}
                />
                <p className="mt-1 text-xs text-slate-400">
                  Sent once with the request, never stored.
                </p>
              </div>
            </div>

            <div>
              <button
                type="button"
                onClick={() => setShowAdvanced((v) => !v)}
                aria-expanded={showAdvanced}
                className="text-sm font-semibold text-indigo-700 hover:text-indigo-900"
              >
                {showAdvanced ? '▾ Hide AI settings' : '▸ AI settings (provider & model)'}
              </button>
              {showAdvanced && (
                <div className="mt-3 grid gap-3 sm:grid-cols-2">
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Provider
                    </p>
                    <select
                      aria-label="AI provider"
                      value={aiProvider}
                      onChange={(event) => setAiProvider(event.target.value as GenerationAiProvider)}
                      className={selectClass}
                    >
                      <option value="OPENROUTER">OpenRouter</option>
                      <option value="CUSTOM">Custom (OpenAI-compatible)</option>
                    </select>
                  </div>
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Model
                    </p>
                    <input
                      aria-label="AI model"
                      value={aiModel}
                      onChange={(event) => setAiModel(event.target.value)}
                      placeholder={DEFAULT_MODEL}
                      maxLength={200}
                      autoComplete="off"
                      className={inputClass}
                    />
                  </div>
                  {aiProvider === 'CUSTOM' && (
                    <div className="sm:col-span-2">
                      <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                        Base URL
                      </p>
                      <input
                        aria-label="AI base URL"
                        value={aiBaseUrl}
                        onChange={(event) => setAiBaseUrl(event.target.value)}
                        placeholder="e.g. https://my-gateway.example.com/v1"
                        maxLength={500}
                        inputMode="url"
                        autoComplete="off"
                        className={inputClass}
                      />
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm leading-relaxed text-slate-600">
              <strong>Quality gate:</strong> the Review agent checks every generated file. If it
              finds bugs, download stays locked until you approve fixes and the project is
              re-verified.
            </div>

            {(formError || submitError) && (
              <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                {formError ?? submitError}
              </p>
            )}

            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => void submit(true)}
                disabled={submitting}
                className={secondaryButtonClass}
              >
                {submitting ? 'Saving…' : 'Save draft'}
              </button>
              <button
                type="button"
                onClick={() => void submit(false)}
                disabled={submitting}
                className={primaryButtonClass}
              >
                {submitting ? 'Starting…' : 'Generate project'}
              </button>
            </div>
          </div>
        </Card>
      )}

      {running && generation && isDraft && (
        <Card
          title="Draft saved"
          subtitle="Nothing has been generated yet. Edit the description, then start."
          actions={<Badge tone={statusTone(generation.status)}>{generation.status}</Badge>}
        >
          <div className="space-y-4">
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Description
              </p>
              <textarea
                aria-label="Draft task"
                value={draftRequirement ?? generation.requirement}
                onChange={(event) => setDraftRequirement(event.target.value)}
                rows={6}
                maxLength={20000}
                className={inputClass}
              />
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={() => void saveTask()}
                  disabled={
                    savingTask ||
                    draftRequirement === null ||
                    draftRequirement.trim() === generation.requirement
                  }
                  className={secondaryButtonClass}
                >
                  {savingTask ? 'Saving…' : 'Save changes'}
                </button>
                {taskFeedback && (
                  <p
                    role={taskFeedback.kind === 'error' ? 'alert' : 'status'}
                    className={`text-sm ${
                      taskFeedback.kind === 'error' ? 'text-red-600' : 'text-emerald-700'
                    }`}
                  >
                    {taskFeedback.text}
                  </p>
                )}
              </div>
            </div>
            <div className="rounded-xl border border-indigo-100 bg-indigo-50/40 p-4">
              <p className="mb-3 text-sm leading-relaxed text-slate-600">
                Drafts hold no secrets. Supply them below (both optional) — used for this
                run only and never stored.
              </p>
              <div className="grid gap-3 sm:grid-cols-2">
                {generation.database !== 'NONE' && (
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Database password
                    </p>
                    <input
                      aria-label="Start database password"
                      type="password"
                      value={startPassword}
                      onChange={(event) => setStartPassword(event.target.value)}
                      placeholder="Leave blank to skip"
                      autoComplete="new-password"
                      className={inputClass}
                    />
                  </div>
                )}
                <div>
                  <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                    AI API key
                  </p>
                  <input
                    aria-label="Start AI API key"
                    type="password"
                    value={startApiKey}
                    onChange={(event) => setStartApiKey(event.target.value)}
                    placeholder="Leave blank to skip"
                    autoComplete="off"
                    className={inputClass}
                  />
                </div>
              </div>
              {startError && (
                <p role="alert" className="mt-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                  {startError}
                </p>
              )}
              <button
                type="button"
                onClick={() => void startDraft()}
                disabled={starting}
                className={`${primaryButtonClass} mt-3`}
              >
                {starting ? 'Starting…' : 'Generate project'}
              </button>
            </div>
          </div>
        </Card>
      )}

      {running && generation && !isDraft && (
        <Card
          title={generation.name}
          subtitle="Live status from the backend — nothing here is estimated."
          actions={<Badge tone={statusTone(generation.status)}>{generation.status}</Badge>}
        >
          <div aria-label="Generation agent workflow" className="mb-4 grid gap-2 sm:grid-cols-5">
            {PIPELINE_STAGES.map((stage, stageIndex) => {
              const persistedStep = generation.agentWorkflow?.find(
                (item) =>
                  stage.statuses.includes(item.agentType) ||
                  item.agentType.toLowerCase().includes(stage.key),
              );
              const currentStage = stage.statuses.includes(generation.status);
              const completedStage =
                persistedStep?.status === 'COMPLETED' ||
                (!persistedStep && !currentStage && statusIndex > stageIndex);
              const failedStage =
                persistedStep?.status === 'FAILED' || (failed && currentStage);

              let animationState: 'waiting' | 'active' | 'completed' | 'failed' = 'waiting';
              if (failedStage) animationState = 'failed';
              else if (completedStage) animationState = 'completed';
              else if (currentStage) animationState = 'active';

              return (
                <div
                  key={stage.key}
                  className={`rounded-xl border p-3 transition-all duration-500 ease-out ${
                    animationState === 'active'
                      ? 'border-indigo-400 bg-indigo-50 ring-1 ring-indigo-300 shadow-sm'
                      : animationState === 'completed'
                        ? 'border-emerald-200 bg-emerald-50'
                        : animationState === 'failed'
                          ? 'border-red-200 bg-red-50'
                          : 'border-slate-200 bg-slate-50'
                  }`}
                >
                  <div className="flex items-start gap-3">
                    <span className={getPipelineStageIconClass(animationState)}>
                      {animationState === 'completed'
                        ? '✓'
                        : animationState === 'failed'
                          ? '✕'
                          : STAGE_NUMBERS[stageIndex]}
                    </span>
                    <span className={`min-w-0 flex-1 ${getPipelineStageLabelClass(animationState)}`}>
                      <p className="text-xs font-bold text-slate-800">{stage.label}</p>
                      <p
                        className={`mt-1 text-xs font-semibold ${
                          animationState === 'active'
                            ? 'text-indigo-700'
                            : animationState === 'completed'
                              ? 'text-emerald-700'
                              : animationState === 'failed'
                                ? 'text-red-700'
                                : 'text-slate-400'
                        }`}
                      >
                        {animationState === 'active'
                          ? 'Working'
                          : animationState === 'completed'
                            ? 'Completed'
                            : animationState === 'failed'
                              ? 'Failed'
                              : 'Waiting'}
                      </p>
                    </span>
                  </div>
                </div>
              );
            })}
          </div>

          {!terminal && (
            <LoadingState label={`${generation.status}… polling for real backend state.`} />
          )}

          <div className="space-y-4">
            {generation.verification && (
              <div className="rounded-xl border border-indigo-100 bg-indigo-50/40 p-4">
                <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">
                  Verification
                </h4>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <div>
                    <p className="text-xs font-semibold text-slate-500">Verdict</p>
                    <Badge
                      tone={
                        generation.verification.verdict === 'VERIFIED'
                          ? 'green'
                          : generation.verification.verdict === 'REJECTED'
                            ? 'red'
                            : 'amber'
                      }
                    >
                      {generation.verification.verdict}
                    </Badge>
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-slate-500">Build</p>
                    <Badge
                      tone={
                        generation.verification.buildStatus === 'SUCCESS'
                          ? 'green'
                          : generation.verification.buildStatus === 'FAILURE'
                            ? 'red'
                            : 'blue'
                      }
                    >
                      {generation.verification.buildStatus}
                    </Badge>
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-slate-500">Tests</p>
                    <p className="font-mono text-sm text-slate-800">
                      {generation.verification.testsPassed}/{generation.verification.testsTotal} passed
                      {generation.verification.testsFailed > 0 &&
                        ` · ${generation.verification.testsFailed} failed`}
                      {generation.verification.testsSkipped > 0 &&
                        ` · ${generation.verification.testsSkipped} skipped`}
                    </p>
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-slate-500">Duration</p>
                    <p className="font-mono text-sm text-slate-800">
                      {generation.verification.durationMs
                        ? `${generation.verification.durationMs} ms`
                        : '—'}
                    </p>
                  </div>
                </div>
              </div>
            )}

            {generation.review && (
              <div className="rounded-xl border border-slate-100 bg-slate-50/40 p-4">
                <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">
                  Review
                </h4>
                <div className="grid gap-3 sm:grid-cols-3">
                  <div>
                    <p className="text-xs font-semibold text-slate-500">Status</p>
                    <Badge
                      tone={
                        generation.review.status === 'COMPLETED'
                          ? 'green'
                          : generation.review.status === 'FAILED'
                            ? 'red'
                            : 'amber'
                      }
                    >
                      {generation.review.status}
                    </Badge>
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-slate-500">Findings</p>
                    <p className="font-mono text-sm text-slate-800">{generation.review.findingCount}</p>
                  </div>
                  <div>
                    <p className="text-xs font-semibold text-slate-500">Error</p>
                    <p className="font-mono text-sm text-slate-800">{generation.review.error ?? '—'}</p>
                  </div>
                </div>
              </div>
            )}

            {stats && (
              <div>
                <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">
                  Issues
                </h4>
                <div className="grid gap-3 sm:grid-cols-3">
                  <Stat label="Found" value={stats.totalFindings} tone="slate" />
                  <Stat label="Fixed" value={stats.fixedFindings} tone="emerald" />
                  <Stat label="Remaining" value={stats.openFindings} tone="amber" />
                </div>
                <p className="mt-2 text-xs text-slate-400">
                  Counts come straight from the review record.
                </p>
              </div>
            )}

            {generation.agentWorkflow && generation.agentWorkflow.length > 0 && (
              <div>
                <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">
                  Agent pipeline
                </h4>
                <div className="space-y-1.5">
                  {generation.agentWorkflow.map((step, index) => (
                    <div
                      key={index}
                      className="flex items-center gap-3 rounded-lg border border-indigo-100 bg-white px-3 py-2"
                    >
                      <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-indigo-600 text-xs font-bold text-white">
                        {index + 1}
                      </span>
                      <span className="flex-1 font-mono text-sm font-semibold text-indigo-950">
                        {step.agentType}
                      </span>
                      <Badge
                        tone={
                          step.status === 'COMPLETED'
                            ? 'green'
                            : step.status === 'RUNNING'
                              ? 'blue'
                              : step.status === 'FAILED'
                                ? 'red'
                                : 'gray'
                        }
                      >
                        {step.status}
                      </Badge>
                      {step.durationMs && (
                        <span className="text-xs tabular-nums text-slate-500">{step.durationMs} ms</span>
                      )}
                      {step.error && <span className="ml-2 text-xs text-red-600">{step.error}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {reviewDone && (
              <div className="space-y-4">
                <div
                  className={`rounded-xl border p-4 ${
                    openFindings > 0
                      ? 'border-amber-200 bg-amber-50/60'
                      : 'border-emerald-200 bg-emerald-50/60'
                  }`}
                >
                  {openFindings > 0 ? (
                    <>
                      <p className="text-sm font-semibold text-amber-900">
                        {openFindings} issue{openFindings === 1 ? '' : 's'} must be fixed before you can download.
                      </p>
                      <p className="mt-1 text-sm leading-relaxed text-slate-600">
                        Approving fixes creates one fix request per open issue. The Coding agent
                        proposes patches, the project is rebuilt, and the Review agent re-verifies —
                        nothing changes without your approval.
                      </p>
                    </>
                  ) : generation.downloadReady ? (
                    <p className="text-sm font-semibold text-emerald-800">
                      All checks passed. Your project is ready for download.
                    </p>
                  ) : (
                    <p className="text-sm text-slate-600">
                      Verification and review finished. Download unlocks once every gate passes.
                    </p>
                  )}
                </div>

                {generation.status === 'REVIEWED' && openFindings > 0 && (
                  <div className="flex flex-wrap items-center gap-2">
                    <button
                      type="button"
                      onClick={() => void handleFixIssues()}
                      disabled={fixing}
                      className="rounded-lg bg-amber-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-amber-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
                    >
                      {fixing ? 'Requesting fixes…' : 'Approve & fix issues'}
                    </button>
                    {fixResult && (
                      <p role="status" className="text-sm text-emerald-700">
                        {fixResult.created} fix request(s) created
                        {fixResult.skippedOpen > 0 && ` · ${fixResult.skippedOpen} already open — skipped`}.
                      </p>
                    )}
                    {fixResult && generation.projectId && (
                      <Link
                        to={`/review?project=${generation.projectId}`}
                        className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
                      >
                        Review fix requests →
                      </Link>
                    )}
                  </div>
                )}
                {fixError && (
                  <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                    {fixError}
                  </p>
                )}

                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={handleDownload}
                    disabled={!generation.downloadReady || openFindings > 0 || downloading}
                    title={
                      generation.downloadReady && openFindings === 0
                        ? undefined
                        : 'Available once all issues are fixed and re-verified'
                    }
                    className={primaryButtonClass}
                  >
                    {downloading ? 'Downloading…' : 'Download ZIP'}
                  </button>
                  {(!generation.downloadReady || openFindings > 0) && (
                    <span className="text-sm text-slate-500">Download locked</span>
                  )}
                </div>
                {downloadError && (
                  <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                    {downloadError}
                  </p>
                )}
              </div>
            )}
          </div>

          {failed && (
            <div className="mt-4 space-y-3">
              <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                {generation.error ?? 'Generation failed.'}
              </p>
              <button
                type="button"
                onClick={() => {
                  setGenerationId(null);
                  setGeneration(null);
                  setSubmitError(null);
                }}
                className={secondaryButtonClass}
              >
                Back to form
              </button>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}