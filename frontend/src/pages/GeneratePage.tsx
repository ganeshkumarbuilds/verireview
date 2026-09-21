import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
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

const POLL_MS = 3000;
const MASKED_SECRET = '••••••••';

const BACKENDS: { value: GenerationBackend; label: string; tooling: string }[] = [
  { value: 'JAVA_SPRING_BOOT', label: 'Java Spring Boot', tooling: 'Maven build, Spring Boot layout' },
  { value: 'PYTHON_FASTAPI', label: 'Python FastAPI', tooling: 'pip requirements, uvicorn entrypoint' },
  { value: 'NODEJS', label: 'Node.js', tooling: 'npm package.json, node entrypoint' },
];

const FRONTENDS: { value: GenerationFrontend; label: string }[] = [
  { value: 'REACT_TYPESCRIPT', label: 'React + TypeScript' },
  { value: 'NONE', label: 'None' },
];

const DATABASES: { value: GenerationDatabase; label: string; port: string }[] = [
  { value: 'POSTGRESQL', label: 'PostgreSQL', port: '5432' },
  { value: 'MYSQL', label: 'MySQL', port: '3306' },
  { value: 'MONGODB', label: 'MongoDB', port: '27017' },
  { value: 'NONE', label: 'None', port: '' },
];

const AI_PROVIDERS: { value: GenerationAiProvider; label: string; hint: string }[] = [
  { value: 'OPENROUTER', label: 'OpenRouter', hint: 'OpenRouter chat-completions endpoint.' },
  { value: 'CUSTOM', label: 'Custom (OpenAI-compatible)', hint: 'Any OpenAI-compatible base URL.' },
];

const PIPELINE_STAGES = [
  { key: 'planning', label: 'Planner Agent', statuses: ['PLANNING'] },
  { key: 'coding', label: 'Coding Agent', statuses: ['CODING', 'GENERATING'] },
  { key: 'build', label: 'Build & Test', statuses: ['BUILDING', 'TESTING'] },
  { key: 'verified', label: 'Verified Agent', statuses: ['VERIFYING', 'REVERIFYING', 'VERIFIED'] },
  { key: 'review', label: 'Review Agent', statuses: ['REVIEWING', 'REVIEWED'] },
];

const STATUS_ORDER = [
  'QUEUED', 'PLANNING', 'CODING', 'BUILDING', 'TESTING',
  'VERIFYING', 'VERIFIED', 'REVIEWING', 'REVIEWED', 'COMPLETED',
];

/** Fixed four-step wizard: Requirement → Stack → AI configuration → Review. */
const STEPS = ['Requirement', 'Stack', 'AI configuration', 'Review'];

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

function backendLabel(value: string): string {
  return BACKENDS.find((b) => b.value === value)?.label ?? value;
}

function databaseLabel(value: string): string {
  return DATABASES.find((d) => d.value === value)?.label ?? value;
}

/**
 * Generate Project wizard: Requirement → Stack → AI configuration → Review.
 * Database configuration lives inside the Stack step and appears only when a
 * database is chosen. Secrets live in component state only — never in URLs,
 * localStorage, logs, or generated code. Submitting only sends the validated
 * configuration through the existing generation API; progress renders real
 * backend state polled from the API and is never invented.
 */
export function GeneratePage() {
  const { token } = useAuth();
  const [step, setStep] = useState(1);
  const [stepError, setStepError] = useState<string | null>(null);

  const [name, setName] = useState('');
  const [requirement, setRequirement] = useState('');
  const [description, setDescription] = useState('');

  const [backend, setBackend] = useState<GenerationBackend | ''>('');
  const [frontend, setFrontend] = useState<GenerationFrontend | ''>('');
  const [database, setDatabase] = useState<GenerationDatabase | ''>('');

  const [dbHost, setDbHost] = useState('');
  const [dbPort, setDbPort] = useState('');
  const [dbName, setDbName] = useState('');
  const [dbUser, setDbUser] = useState('');
  const [dbPassword, setDbPassword] = useState('');
  const [dbSsl, setDbSsl] = useState('');

  const [aiProvider, setAiProvider] = useState<GenerationAiProvider | ''>('');
  const [aiKey, setAiKey] = useState('');
  const [aiBaseUrl, setAiBaseUrl] = useState('');
  const [aiModel, setAiModel] = useState('');

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

  const wantsDb = database !== '' && database !== 'NONE';

  
  const portPlaceholder = DATABASES.find((d) => d.value === database)?.port ?? '';

  function validateStep(target: number): string | null {
    if (target === 1) {
      if (!name.trim()) {
        return 'Please enter a project name.';
      }
      if (name.trim().length > 200) {
        return 'Project name must be 200 characters or fewer.';
      }
      if (requirement.trim().length < 20) {
        return 'Please describe the requirement in at least 20 characters.';
      }
      if (description.length > 5000) {
        return 'Description must be 5000 characters or fewer.';
      }
      return null;
    }
    if (target === 2) {
      if (!backend) {
        return 'Please choose a backend stack.';
      }
      if (!frontend) {
        return 'Please choose a frontend option.';
      }
      if (!database) {
        return 'Please choose a database option.';
      }
      if (wantsDb) {
        if (!dbHost.trim()) {
          return 'Please enter the database host.';
        }
        const port = Number(dbPort);
        if (!dbPort.trim() || !Number.isInteger(port) || port < 1 || port > 65535) {
          return 'Please enter a valid database port (1–65535).';
        }
        if (!dbName.trim()) {
          return 'Please enter the database name.';
        }
        if (!dbUser.trim()) {
          return 'Please enter the database username.';
        }
        if (!dbPassword) {
          return 'Please enter the database password.';
        }
      }
      return null;
    }
    if (target === 3) {
      if (!aiProvider) {
        return 'Please choose an AI provider.';
      }
      if (!aiKey) {
        return 'Please enter your API key.';
      }
      if (aiProvider === 'CUSTOM' && !aiBaseUrl.trim()) {
        return 'Please enter the base URL for your custom provider.';
      }
      if (!aiModel.trim()) {
        return 'Please enter the model name.';
      }
      return null;
    }
    return null;
  }

  const allValid =
    validateStep(1) === null &&
    validateStep(2) === null &&
    validateStep(3) === null;

  const goNext = () => {
    const problem = validateStep(step);
    if (problem) {
      setStepError(problem);
      return;
    }
    setStepError(null);
    setStep((current) => Math.min(current + 1, STEPS.length));
  };

  const goBack = () => {
    setStepError(null);
    setStep((current) => Math.max(current - 1, 1));
  };

  const goEdit = () => {
    setStepError(null);
    setStep(1);
  };

  const buildInput = (forDraft: boolean): CreateGenerationInput => {
    const input: CreateGenerationInput = {
      name: name.trim(),
      requirement: requirement.trim(),
      description: description.trim() || undefined,
      backend: backend as GenerationBackend,
      frontend: frontend as GenerationFrontend,
      database: database as GenerationDatabase,
      aiConfig: {
        provider: aiProvider as GenerationAiProvider,
        apiKey: forDraft ? '' : aiKey,
        baseUrl: aiBaseUrl.trim() || undefined,
        model: aiModel.trim(),
      },
    };
    if (forDraft) {
      input.draft = true;
    }
    if (wantsDb) {
      input.databaseConfig = {
        host: dbHost.trim(),
        port: Number(dbPort),
        name: dbName.trim(),
        username: dbUser.trim(),
        password: forDraft ? '' : dbPassword,
        sslMode: dbSsl.trim() || undefined,
      };
    }
    return input;
  };

  const generateNow = async () => {
    if (!token || submitting || !allValid) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      const created = await createGeneration(apiClient(), token, buildInput(false));
      setGenerationId(created.id);
      setGeneration(created);
    } catch (err) {
      setSubmitError(
        err instanceof ApiError ? err.message : 'Could not start generation.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  const saveDraft = async () => {
    if (!token || submitting || !allValid) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      // Drafts persist configuration only: secrets are omitted from the
      // request entirely (the backend discards them even if sent).
      const created = await createGeneration(apiClient(), token, buildInput(true));
      setGenerationId(created.id);
      setGeneration(created);
      setDraftRequirement(null);
      setTaskFeedback(null);
    } catch (err) {
      setSubmitError(
        err instanceof ApiError ? err.message : 'Could not save the draft.',
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
      setTaskFeedback({ kind: 'error', text: 'Please describe the requirement in at least 20 characters.' });
      return;
    }
    if (next.length > 20000) {
      setTaskFeedback({ kind: 'error', text: 'Requirement must be 20000 characters or fewer.' });
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
      setTaskFeedback({ kind: 'saved', text: 'Task saved.' });
    } catch (err) {
      setTaskFeedback({
        kind: 'error',
        text: err instanceof ApiError ? err.message : 'Could not save the task.',
      });
    } finally {
      setSavingTask(false);
    }
  };

  const startDraft = async () => {
    if (!token || !generation || starting) {
      return;
    }
    const needsPassword = generation.database !== 'NONE';
    if (needsPassword && !startPassword) {
      setStartError('Please enter the database password.');
      return;
    }
    if (!startApiKey) {
      setStartError('Please enter your API key.');
      return;
    }
    setStarting(true);
    setStartError(null);
    try {
      const started = await startSavedGeneration(apiClient(), token, generation.id, {
        password: needsPassword ? startPassword : undefined,
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

  // Deep-link: /generate?genId=<id> loads an existing generation (e.g. after
  // creating it from the Projects quick form) instead of starting the wizard.
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
        // Stay on the wizard: an unknown id must not blank the page.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, generationId, searchParams]);

  // Poll only on generation identity/status transitions: depending on the
  // `generation` object itself would re-fire on every poll, because each
  // response parses to a new object — an infinite zero-delay request loop.
  // The status string is stable between transitions, preserving the 3s cadence.
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

  return (
    <div className="space-y-6">
      <WorkspaceCrumb items={[{ label: 'Generate project' }]} />
      <PageHeader
        title="Generate a verified project"
        description="Give VeriReview the requirement, stack, environment, and AI configuration. Planner, Coding, Verified, and Review agents work from real backend state."
      />

      {!running && (
        <ol aria-label="Generation steps" className="flex flex-wrap items-center gap-1.5">
          {STEPS.map((label, index) => {
            const number = index + 1;
            const active = number === step;
            const done = number < step;
            return (
              <li key={label} className="flex items-center gap-1.5">
                {index > 0 && (
                  <span aria-hidden="true" className="h-px w-3 bg-indigo-200" />
                )}
                <span
                  aria-current={active ? 'step' : undefined}
                  className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold ${
                    done
                      ? 'bg-indigo-600 text-white'
                      : active
                        ? 'bg-indigo-100 text-indigo-800 ring-1 ring-inset ring-indigo-600/30'
                        : 'bg-white text-slate-400 ring-1 ring-inset ring-slate-200'
                  }`}
                >
                  <span className="tabular-nums">{number}</span> {label}
                </span>
              </li>
            );
          })}
        </ol>
      )}

      {stepError && !running && (
        <p role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {stepError}
        </p>
      )}

      {!running && step === 1 && (
        <Card title="Step 1 — Requirement" subtitle="What should be built?">
          <div className="space-y-3">
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Project name
              </p>
              <input
                aria-label="Project name"
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder="e.g. todo-api"
                maxLength={200}
                className={inputClass}
              />
            </div>
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Requirement
              </p>
              <textarea
                aria-label="Requirement"
                value={requirement}
                onChange={(event) => setRequirement(event.target.value)}
                placeholder="Explain your task or project along with your tech stack: features, endpoints, data model, frontend, backend, database…"
                rows={6}
                maxLength={20000}
                className={inputClass}
              />
              <p className="mt-1 text-right text-xs tabular-nums text-slate-400">
                {requirement.trim().length}/20 minimum
              </p>
            </div>
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Description <span className="font-normal normal-case">(optional)</span>
              </p>
              <input
                aria-label="Project description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                placeholder="One-line summary"
                maxLength={5000}
                className={inputClass}
              />
            </div>
          </div>
        </Card>
      )}

      {!running && step === 2 && (
        <Card title="Step 2 — Technology stack" subtitle="Choose explicitly. Nothing is preselected.">
          <div className="space-y-5">
            <fieldset>
              <legend className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Backend
              </legend>
              <div className="grid gap-2 sm:grid-cols-3">
                {BACKENDS.map((option) => (
                  <label
                    key={option.value}
                    className={`cursor-pointer rounded-xl border px-4 py-3 transition-colors ${
                      backend === option.value
                        ? 'border-indigo-600 bg-indigo-50/60 ring-1 ring-indigo-600'
                        : 'border-indigo-100 bg-white hover:border-indigo-300'
                    }`}
                  >
                    <span className="flex items-center gap-2 text-sm font-semibold text-indigo-950">
                      <input
                        type="radio"
                        name="backend"
                        value={option.value}
                        checked={backend === option.value}
                        onChange={() => setBackend(option.value)}
                        className="accent-indigo-600"
                      />
                      {option.label}
                    </span>
                    <span className="mt-1 block text-xs text-slate-500">{option.tooling}</span>
                  </label>
                ))}
              </div>
            </fieldset>
            <fieldset>
              <legend className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Frontend
              </legend>
              <div className="grid gap-2 sm:grid-cols-2">
                {FRONTENDS.map((option) => (
                  <label
                    key={option.value}
                    className={`cursor-pointer rounded-xl border px-4 py-3 transition-colors ${
                      frontend === option.value
                        ? 'border-indigo-600 bg-indigo-50/60 ring-1 ring-indigo-600'
                        : 'border-indigo-100 bg-white hover:border-indigo-300'
                    }`}
                  >
                    <span className="flex items-center gap-2 text-sm font-semibold text-indigo-950">
                      <input
                        type="radio"
                        name="frontend"
                        value={option.value}
                        checked={frontend === option.value}
                        onChange={() => setFrontend(option.value)}
                        className="accent-indigo-600"
                      />
                      {option.label}
                    </span>
                    {option.value === 'REACT_TYPESCRIPT' && (
                      <span className="mt-1 block text-xs text-slate-500">
                        Vite + TypeScript tooling
                      </span>
                    )}
                  </label>
                ))}
              </div>
            </fieldset>
            <fieldset>
              <legend className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Database
              </legend>
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                {DATABASES.map((option) => (
                  <label
                    key={option.value}
                    className={`cursor-pointer rounded-xl border px-4 py-3 transition-colors ${
                      database === option.value
                        ? 'border-indigo-600 bg-indigo-50/60 ring-1 ring-indigo-600'
                        : 'border-indigo-100 bg-white hover:border-indigo-300'
                    }`}
                  >
                    <span className="flex items-center gap-2 text-sm font-semibold text-indigo-950">
                      <input
                        type="radio"
                        name="database"
                        value={option.value}
                        checked={database === option.value}
                        onChange={() => setDatabase(option.value)}
                        className="accent-indigo-600"
                      />
                      {option.label}
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
            {wantsDb && (
              <fieldset>
                <legend className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Database configuration ({databaseLabel(database)})
                </legend>
                <p className="mb-2 text-xs leading-relaxed text-slate-500">
                  Manual configuration. Credentials are never stored or shown again.
                </p>
                <div className="grid gap-3 sm:grid-cols-2">
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Host
                    </p>
                    <input
                      aria-label="Database host"
                      value={dbHost}
                      onChange={(event) => setDbHost(event.target.value)}
                      placeholder="e.g. localhost"
                      maxLength={500}
                      autoComplete="off"
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Port
                    </p>
                    <input
                      aria-label="Database port"
                      value={dbPort}
                      onChange={(event) => setDbPort(event.target.value)}
                      placeholder={portPlaceholder || 'e.g. 5432'}
                      inputMode="numeric"
                      autoComplete="off"
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Database name
                    </p>
                    <input
                      aria-label="Database name"
                      value={dbName}
                      onChange={(event) => setDbName(event.target.value)}
                      placeholder="e.g. todos"
                      maxLength={200}
                      autoComplete="off"
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Username
                    </p>
                    <input
                      aria-label="Database username"
                      value={dbUser}
                      onChange={(event) => setDbUser(event.target.value)}
                      placeholder="e.g. app"
                      maxLength={200}
                      autoComplete="off"
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      Password
                    </p>
                    <input
                      aria-label="Database password"
                      type="password"
                      value={dbPassword}
                      onChange={(event) => setDbPassword(event.target.value)}
                      placeholder="Stored in memory only for this run"
                      maxLength={500}
                      autoComplete="new-password"
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                      SSL mode <span className="font-normal normal-case">(optional)</span>
                    </p>
                    <select
                      aria-label="Database SSL mode"
                      value={dbSsl}
                      onChange={(event) => setDbSsl(event.target.value)}
                      className={selectClass}
                    >
                      <option value="">Not specified</option>
                      <option value="disable">disable</option>
                      <option value="allow">allow</option>
                      <option value="prefer">prefer</option>
                      <option value="require">require</option>
                      <option value="verify-ca">verify-ca</option>
                      <option value="verify-full">verify-full</option>
                    </select>
                  </div>
                </div>
              </fieldset>
            )}

          </div>
        </Card>
      )}

      {!running && step === 3 && (
        <Card
          title="Step 3 — AI configuration"
          subtitle="Your key is sent once over the API call and never stored. Supply your own credentials — there is no default key."
        >
          <div className="space-y-4">
            <fieldset>
              <legend className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Provider
              </legend>
              <div className="grid gap-2 sm:grid-cols-2">
                {AI_PROVIDERS.map((option) => (
                  <label
                    key={option.value}
                    className={`cursor-pointer rounded-xl border px-4 py-3 transition-colors ${
                      aiProvider === option.value
                        ? 'border-indigo-600 bg-indigo-50/60 ring-1 ring-indigo-600'
                        : 'border-indigo-100 bg-white hover:border-indigo-300'
                    }`}
                  >
                    <span className="flex items-center gap-2 text-sm font-semibold text-indigo-950">
                      <input
                        type="radio"
                        name="ai-provider"
                        value={option.value}
                        checked={aiProvider === option.value}
                        onChange={() => setAiProvider(option.value)}
                        className="accent-indigo-600"
                      />
                      {option.label}
                    </span>
                    <span className="mt-1 block text-xs text-slate-500">{option.hint}</span>
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  API key
                </p>
                <input
                  aria-label="AI API key"
                  type="password"
                  value={aiKey}
                  onChange={(event) => setAiKey(event.target.value)}
                  placeholder="Never stored — used once for this run"
                  maxLength={2000}
                  autoComplete="off"
                  className={inputClass}
                />
              </div>
              <div>
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Model
                </p>
                <input
                  aria-label="AI model"
                  value={aiModel}
                  onChange={(event) => setAiModel(event.target.value)}
                  placeholder="e.g. openai/gpt-4o-mini"
                  maxLength={200}
                  autoComplete="off"
                  className={inputClass}
                />
              </div>
            </div>
            {aiProvider === 'CUSTOM' && (
              <div>
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
        </Card>
      )}

      {!running && step === STEPS.length && (
        <Card title="Review" subtitle="Read-only summary. Secrets stay masked.">
          <dl className="space-y-3 text-sm">
            <div className="rounded-xl border border-indigo-100 bg-indigo-50/40 p-3">
              <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                Requirement
              </dt>
              <dd className="mt-1 font-semibold text-indigo-950">{name.trim()}</dd>
              <dd className="mt-1 whitespace-pre-wrap leading-relaxed text-slate-700">
                {requirement.trim()}
              </dd>
              {description.trim() && (
                <dd className="mt-1 text-slate-500">{description.trim()}</dd>
              )}
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="rounded-xl border border-indigo-100 p-3">
                <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Backend
                </dt>
                <dd className="mt-1 font-medium text-slate-800">{backendLabel(backend)}</dd>
              </div>
              <div className="rounded-xl border border-indigo-100 p-3">
                <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Frontend
                </dt>
                <dd className="mt-1 font-medium text-slate-800">
                  {frontend === 'REACT_TYPESCRIPT' ? 'React + TypeScript' : 'None'}
                </dd>
              </div>
              <div className="rounded-xl border border-indigo-100 p-3">
                <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Database
                </dt>
                <dd className="mt-1 font-medium text-slate-800">{databaseLabel(database)}</dd>
              </div>
            </div>
            {wantsDb && (
              <div className="rounded-xl border border-indigo-100 p-3">
                <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Database configuration
                </dt>
                <dd className="mt-1 font-mono text-xs text-slate-700">
                  {dbHost.trim()}:{dbPort.trim()}/{dbName.trim()} as {dbUser.trim()}
                  {dbSsl.trim() ? ` · sslmode=${dbSsl.trim()}` : ''}
                </dd>
                <dd className="mt-1 font-mono text-xs text-slate-500">
                  password: {MASKED_SECRET}
                </dd>
              </div>
            )}
            <div className="rounded-xl border border-indigo-100 p-3">
              <dt className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                AI configuration
              </dt>
              <dd className="mt-1 text-slate-700">
                {aiProvider === 'OPENROUTER' ? 'OpenRouter' : 'Custom'} · {aiModel.trim()}
                {aiBaseUrl.trim() ? ` · ${aiBaseUrl.trim()}` : ''}
              </dd>
              <dd className="mt-1 font-mono text-xs text-slate-500">
                api key: {MASKED_SECRET}
              </dd>
            </div>
          </dl>
          <div className="mt-4 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm leading-relaxed text-slate-600">
            <strong>Quality gate:</strong> file creation alone does not make a project ready. Build, verification, and review must complete successfully before the generated project is treated as verified.
          </div>
          {submitError && (
            <p role="alert" className="mt-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
              {submitError}
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => void saveDraft()}
              disabled={submitting || !allValid}
              className={secondaryButtonClass}
            >
              {submitting ? 'Saving…' : 'Save Draft'}
            </button>
            <button
              type="button"
              onClick={goBack}
              className={secondaryButtonClass}
            >
              Back
            </button>
            <button
              type="button"
              onClick={goEdit}
              className={secondaryButtonClass}
            >
              Edit
            </button>
            <button
              type="button"
              onClick={() => void generateNow()}
              disabled={submitting || !allValid}
              className={primaryButtonClass}
            >
              {submitting ? 'Starting…' : 'Start Generation'}
            </button>
          </div>
          {!allValid && (
            <p className="mt-2 text-sm text-slate-500">
              Complete every step to enable saving or generation.
            </p>
          )}
        </Card>
      )}

      {!running && step < STEPS.length && (
        <div className="flex flex-wrap justify-between gap-2">
          <button
            type="button"
            onClick={goBack}
            disabled={step === 1}
            className={secondaryButtonClass}
          >
            Back
          </button>
          <button type="button" onClick={goNext} className={primaryButtonClass}>
            Continue
          </button>
        </div>
      )}

      {running && generation && isDraft && (
        <Card
          title="Draft saved"
          subtitle="Configuration persisted. The generation pipeline is ready for the next phase — nothing has been generated yet."
          actions={<Badge tone={statusTone(generation.status)}>{generation.status}</Badge>}
        >
          <div className="space-y-4">
            <div className="grid gap-2 sm:grid-cols-5">
              {PIPELINE_STAGES.map((stage) => (
                <div key={stage.key} className="rounded-xl border border-slate-200 bg-slate-50 p-3">
                  <p className="text-xs font-semibold text-slate-700">{stage.label}</p>
                  <p className="mt-1 text-xs text-slate-400">Waiting</p>
                </div>
              ))}
            </div>
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Task / requirements
              </p>
              <textarea
                aria-label="Draft task"
                value={draftRequirement ?? generation.requirement}
                onChange={(event) => setDraftRequirement(event.target.value)}
                rows={5}
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
                  {savingTask ? 'Saving…' : 'Save task'}
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
              <p className="mb-1 text-xs font-bold uppercase tracking-wider text-indigo-500">
                Start generation
              </p>
              <p className="mb-3 text-sm leading-relaxed text-slate-600">
                Drafts hold no secrets server-side. Supply them once to start —
                they are used for this run only and never stored.
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
                {starting ? 'Starting…' : 'Start Generation'}
              </button>
            </div>
          </div>
        </Card>
      )}

      {running && generation && !isDraft && (
        <Card
          title="Generation"
          subtitle={`Real backend state for “${generation.name}”.`}
          actions={<Badge tone={statusTone(generation.status)}>{generation.status}</Badge>}
        >
          {generation.status === 'READY' && (
            <div className="mb-4 rounded-xl border border-indigo-200 bg-indigo-50/60 px-4 py-3">
              <p className="text-sm font-semibold text-indigo-950">Generation ready.</p>
              <p className="mt-0.5 text-sm leading-relaxed text-slate-600">
                Your configuration was accepted and is queued for execution. Live
                backend status appears below — no progress is shown until the
                backend reports it.
              </p>
            </div>
          )}
          <div aria-label="Generation agent workflow" className="mb-4 grid gap-2 sm:grid-cols-5">
            {PIPELINE_STAGES.map((stage) => {
              const stageIndex = PIPELINE_STAGES.findIndex((item) => item.key === stage.key);
              const persistedStep = generation.agentWorkflow?.find((item) =>
                stage.statuses.includes(item.agentType) || item.agentType.toLowerCase().includes(stage.key)
              );
              const currentStage = stage.statuses.includes(generation.status);
              const completedStage = persistedStep?.status === 'COMPLETED'
                || (!persistedStep && !currentStage && statusIndex > stageIndex);
              const failedStage = persistedStep?.status === 'FAILED'
                || (failed && currentStage);
              return (
                <div
                  key={stage.key}
                  className={`rounded-xl border p-3 ${
                    currentStage
                      ? 'border-indigo-400 bg-indigo-50 ring-1 ring-indigo-300'
                      : completedStage
                        ? 'border-emerald-200 bg-emerald-50'
                        : failedStage
                          ? 'border-red-200 bg-red-50'
                          : 'border-slate-200 bg-slate-50'
                  }`}
                >
                  <p className="text-xs font-bold text-slate-800">{stage.label}</p>
                  <p className={`mt-1 text-xs font-semibold ${
                    currentStage ? 'text-indigo-700' : completedStage ? 'text-emerald-700' : failedStage ? 'text-red-700' : 'text-slate-400'
                  }`}>
                    {currentStage ? 'Working' : completedStage ? 'Completed' : failedStage ? 'Failed' : 'Waiting'}
                  </p>
                </div>
              );
            })}
          </div>
          <div className="mb-4 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3">
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-500">Current operation</p>
            <p className="mt-1 text-sm font-semibold text-slate-800">{generation.status}</p>
            <p className="mt-1 text-xs text-slate-500">This status is read directly from the generation record.</p>
          </div>
          {!terminal && (
            <LoadingState label={`${generation.status}… polling for real backend state.`} />
          )}
          
          {/* Detailed Pipeline Progress */}
          {(generation.workflowStats || generation.verification || generation.review || generation.agentWorkflow) && (
            <div className="space-y-4">
              {/* Agent Workflow Timeline */}
              {generation.agentWorkflow && generation.agentWorkflow.length > 0 && (
                <div>
                  <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">Agent Pipeline</h4>
                  <div className="space-y-1.5">
                    {generation.agentWorkflow.map((step, index) => (
                      <div key={index} className="flex items-center gap-3 rounded-lg border border-indigo-100 bg-white px-3 py-2">
                        <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white bg-indigo-600">
                          {index + 1}
                        </span>
                        <span className="flex-1 font-mono text-sm font-semibold text-indigo-950">{step.agentType}</span>
                        <Badge tone={step.status === 'COMPLETED' ? 'green' : step.status === 'RUNNING' ? 'blue' : step.status === 'FAILED' ? 'red' : 'gray'}>
                          {step.status}
                        </Badge>
                        {step.durationMs && (
                          <span className="text-xs tabular-nums text-slate-500">{step.durationMs} ms</span>
                        )}
                        {step.error && (
                          <span className="text-xs text-red-600 ml-2">{step.error}</span>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Verification Results */}
              {generation.verification && (
                <div className="rounded-xl border border-indigo-100 bg-indigo-50/40 p-4">
                  <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">Verification</h4>
                  <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                    <div>
                      <p className="text-xs font-semibold text-slate-500">Verdict</p>
                      <Badge tone={generation.verification.verdict === 'VERIFIED' ? 'green' : generation.verification.verdict === 'REJECTED' ? 'red' : 'amber'}>
                        {generation.verification.verdict}
                      </Badge>
                    </div>
                    <div>
                      <p className="text-xs font-semibold text-slate-500">Build</p>
                      <Badge tone={generation.verification.buildStatus === 'SUCCESS' ? 'green' : generation.verification.buildStatus === 'FAILURE' ? 'red' : 'blue'}>
                        {generation.verification.buildStatus}
                      </Badge>
                    </div>
                    <div>
                      <p className="text-xs font-semibold text-slate-500">Tests</p>
                      <p className="font-mono text-sm text-slate-800">
                        {generation.verification.testsPassed}/{generation.verification.testsTotal} passed
                        {generation.verification.testsFailed > 0 && ` · ${generation.verification.testsFailed} failed`}
                        {generation.verification.testsSkipped > 0 && ` · ${generation.verification.testsSkipped} skipped`}
                      </p>
                    </div>
                    <div>
                      <p className="text-xs font-semibold text-slate-500">Duration</p>
                      <p className="font-mono text-sm text-slate-800">
                        {generation.verification.durationMs ? `${generation.verification.durationMs} ms` : '—'}
                      </p>
                    </div>
                  </div>
                </div>
              )}

              {/* Review Results */}
              {generation.review && (
                <div className="rounded-xl border border-slate-100 bg-slate-50/40 p-4">
                  <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">Review</h4>
                  <div className="grid gap-2 sm:grid-cols-3">
                    <div>
                      <p className="text-xs font-semibold text-slate-500">Status</p>
                      <Badge tone={generation.review.status === 'COMPLETED' ? 'green' : generation.review.status === 'FAILED' ? 'red' : 'amber'}>
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

              {/* Workflow Stats */}
              {generation.workflowStats && (
                <div className="rounded-xl border border-amber-100 bg-amber-50/40 p-4">
                  <h4 className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">Findings Summary</h4>
                  <p className="mb-2 text-xs text-slate-500">
                    Found {generation.workflowStats.totalFindings} · Fixed {generation.workflowStats.fixedFindings} · Open {generation.workflowStats.openFindings} — counts come from the backend review, never invented.
                  </p>
                  <div className="grid gap-2 sm:grid-cols-3 lg:grid-cols-6">
                    <div className="rounded-lg bg-white p-2 text-center">
                      <p className="text-2xl font-bold text-slate-800">{generation.workflowStats.totalFindings}</p>
                      <p className="text-xs text-slate-500">Total</p>
                    </div>
                    <div className="rounded-lg bg-white p-2 text-center">
                      <p className="text-2xl font-bold text-red-600">{generation.workflowStats.bugCount}</p>
                      <p className="text-xs text-slate-500">Bugs</p>
                    </div>
                    <div className="rounded-lg bg-white p-2 text-center">
                      <p className="text-2xl font-bold text-amber-600">{generation.workflowStats.issueCount}</p>
                      <p className="text-xs text-slate-500">Issues</p>
                    </div>
                    <div className="rounded-lg bg-white p-2 text-center">
                      <p className="text-2xl font-bold text-blue-600">{generation.workflowStats.errorCount}</p>
                      <p className="text-xs text-slate-500">Errors</p>
                    </div>
                    <div className="rounded-lg bg-white p-2 text-center">
                      <p className="text-2xl font-bold text-emerald-600">{generation.workflowStats.fixedFindings}</p>
                      <p className="text-xs text-slate-500">Fixed</p>
                    </div>
                    <div className="rounded-lg bg-white p-2 text-center">
                      <p className="text-2xl font-bold text-red-600">{generation.workflowStats.openFindings}</p>
                      <p className="text-xs text-slate-500">Open</p>
                    </div>
                  </div>
                </div>
              )}

              {/* Download / Fix Actions */}
              {(generation.status === 'REVIEWED' || generation.status === 'COMPLETED' || generation.status === 'VERIFIED') && (
                <div className="space-y-4">
                  <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                    <p className="text-sm leading-relaxed text-slate-600">
                      Review complete. {generation.workflowStats && generation.workflowStats.openFindings > 0
                        ? `${generation.workflowStats.openFindings} unresolved finding(s) must be fixed before download.`
                        : generation.downloadReady
                        ? 'All checks passed. Project is ready for download.'
                        : 'Verification and review completed. Download available if all gates pass.'
                      }
                    </p>
                    {generation.workflowStats && generation.workflowStats.openFindings > 0 && (
                      <p className="mt-2 text-sm text-amber-700">
                        ⚠ {generation.workflowStats.openFindings} open finding(s). Approving fixes
                        creates one FixRequest per open finding — code only changes through the
                        existing patch + rebuild flow, never silently.
                      </p>
                    )}
                  </div>

                  {generation.status === 'REVIEWED' && generation.workflowStats && generation.workflowStats.openFindings > 0 && (
                    <div className="flex flex-wrap items-center gap-2">
                      <button
                        type="button"
                        onClick={() => void handleFixIssues()}
                        disabled={fixing}
                        className="rounded-lg bg-amber-600 px-4 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-amber-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber-600 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
                      >
                        {fixing ? 'Requesting fixes…' : 'Fix issues & optimize'}
                      </button>
                      {fixResult && (
                        <p role="status" className="text-sm text-emerald-700">
                          {fixResult.created} fix request(s) created
                          {fixResult.skippedOpen > 0 && ` · ${fixResult.skippedOpen} already open — skipped`}.
                          Propose and apply patches per finding, then rebuild to reverify.
                        </p>
                      )}
                    </div>
                  )}
                  {fixError && (
                    <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                      {fixError}
                    </p>
                  )}
                  
                  <div className="flex flex-wrap gap-2">
                    {generation.downloadReady && (
                      <button
                        type="button"
                        onClick={handleDownload}
                        disabled={downloading}
                        className={primaryButtonClass}
                      >
                        {downloading ? 'Downloading…' : 'Download ZIP'}
                      </button>
                    )}
                    {!generation.downloadReady && (!generation.workflowStats || generation.workflowStats.openFindings === 0) && (
                      <span className="flex items-center px-3 py-2 text-sm text-slate-500">
                        Download unavailable — verification or review not yet complete
                      </span>
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
          )}
          {failed && (
            <div className="space-y-3">
              <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                {generation.error ?? 'Generation failed.'}
              </p>
              <button
                type="button"
                onClick={() => {
                  setGenerationId(null);
                  setGeneration(null);
                  setSubmitError(null);
                  setStep(STEPS.length);
                }}
                className={secondaryButtonClass}
              >
                Back to review
              </button>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
