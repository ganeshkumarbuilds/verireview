import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import {
  applyRestructure,
  createGeneration,
  downloadGeneration,
  getGeneration,
  getGenerationFile,
  isTerminalGeneration,
  listGenerationFiles,
  listRestructures,
  previewGenerationPlan,
  proposeRestructure,
  revertRestructure,
  sendGenerationToReview,
} from '../../api/generations';
import type {
  CreateGenerationInput,
  ExecutionView,
  GenerationAuth,
  GenerationBackend,
  GenerationDatabase,
  GenerationFileContentView,
  GenerationFileView,
  GenerationFrontend,
  GenerationIntegration,
  GenerationResponse,
  PlanPreviewResponse,
  RestructurePreset,
  RestructureView,
} from '../../api/generationTypes';
import { apiClient, useAuth } from '../../auth/AuthContext';
import {
  Badge,
  ErrorAlert,
  LoadingState,
  inputClass,
  primaryButtonClass,
  secondaryButtonClass,
  selectClass,
  textareaClass,
} from '../../components/ui';
import { FeaturePage } from '../../components/FeaturePage';

const POLL_MS = 3000;
const DEFAULT_MODEL = 'openai/gpt-4o-mini';
const MIN_IDEA = 20;
const STEPS = ['Describe', 'Stack', 'Integrations', 'Review plan', 'Generate'] as const;

const BACKEND_LABELS: Record<GenerationBackend, string> = {
  JAVA_SPRING_BOOT: 'Spring Boot',
  NODEJS: 'Node / Express',
  PYTHON_FASTAPI: 'FastAPI',
  NONE: 'None',
};

const FRONTEND_LABELS: Record<GenerationFrontend, string> = {
  REACT_TYPESCRIPT: 'React',
  NEXTJS: 'Next.js',
  VUE: 'Vue',
  NONE: 'None',
};

const DATABASE_LABELS: Record<GenerationDatabase, string> = {
  POSTGRESQL: 'PostgreSQL',
  MYSQL: 'MySQL',
  MONGODB: 'MongoDB',
  SQLITE: 'SQLite',
  NONE: 'None',
};

const AUTH_LABELS: Record<GenerationAuth, string> = {
  JWT: 'JWT',
  OAUTH_GOOGLE: 'OAuth / Google',
  NONE: 'None',
};

const INTEGRATIONS: { value: GenerationIntegration; label: string; note: string }[] = [
  { value: 'STRIPE', label: 'Stripe payments', note: 'Checkout + webhook adapter, keys via env.' },
  { value: 'RAZORPAY', label: 'Razorpay payments', note: 'Orders + webhook adapter, keys via env.' },
  { value: 'EMAIL', label: 'Email', note: 'Transactional sender abstraction, key via env.' },
  { value: 'FILE_STORAGE_S3', label: 'File storage (S3)', note: 'S3-compatible upload adapter.' },
  { value: 'AI_LLM', label: 'AI / LLM', note: 'LLM call adapter, key read from env.' },
  { value: 'MAPS', label: 'Maps', note: 'Geocoding/map adapter, key via env.' },
  { value: 'ANALYTICS', label: 'Analytics', note: 'Event hooks with a write key from env.' },
  { value: 'DOCKER_CI', label: 'Docker / CI', note: 'Dockerfile + GitHub Actions build/test.' },
];

const PRESETS: { value: RestructurePreset; label: string; note: string }[] = [
  { value: 'MVC', label: 'MVC', note: 'controllers · services · repositories · models' },
  { value: 'LAYERED', label: 'Layered', note: 'presentation · domain · infrastructure' },
  { value: 'FEATURE_FOLDERS', label: 'Feature folders', note: 'one folder per feature' },
  { value: 'CUSTOM', label: 'Describe my own', note: 'e.g. services/: UserService.java' },
];

const DB_DEFAULTS: Record<GenerationDatabase, { port: string; user: string }> = {
  POSTGRESQL: { port: '5432', user: 'postgres' },
  MYSQL: { port: '3306', user: 'root' },
  MONGODB: { port: '27017', user: 'admin' },
  SQLITE: { port: '', user: '' },
  NONE: { port: '', user: '' },
};

type StageState = 'waiting' | 'running' | 'done' | 'failed' | 'not_verified';

function slugify(value: string): string {
  return (
    value
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 40) || 'generated-project'
  );
}

function stepStateFromAgent(step: string | undefined): StageState {
  switch (step) {
    case 'COMPLETED':
      return 'done';
    case 'FAILED':
      return 'failed';
    case 'RUNNING':
    case 'PENDING':
      return 'running';
    case 'SKIPPED':
      return 'not_verified';
    default:
      return 'waiting';
  }
}

function buildState(state: StageState) {
  const tone =
    state === 'done'
      ? 'green'
      : state === 'failed'
        ? 'red'
        : state === 'running'
          ? 'blue'
          : state === 'not_verified'
            ? 'amber'
            : 'gray';
  const label =
    state === 'done'
      ? 'Done'
      : state === 'failed'
        ? 'Failed'
        : state === 'running'
          ? 'Running'
          : state === 'not_verified'
            ? 'Not verified'
            : 'Waiting';
  return { tone: tone as 'green' | 'red' | 'blue' | 'amber' | 'gray', label };
}

function latestAgent(generation: GenerationResponse | null, type: string) {
  if (!generation) return undefined;
  return [...generation.agentWorkflow]
    .filter((step) => step.agentType === type)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
}

function pipelineStages(generation: GenerationResponse | null): {
  key: string;
  label: string;
  state: StageState;
  detail: string;
}[] {
  const execution: ExecutionView | null = generation?.execution ?? null;
  const buildState: StageState = execution
    ? execution.notVerified ||
      execution.buildStatus === 'SKIPPED' ||
      execution.testStatus === 'SKIPPED'
      ? 'not_verified'
      : execution.buildStatus === 'SUCCESS' && execution.testStatus === 'SUCCESS'
        ? 'done'
        : execution.buildStatus === 'FAILURE' || execution.buildStatus === 'TIMEOUT'
          ? 'failed'
          : 'running'
    : generation?.status === 'BUILDING' || generation?.status === 'TESTING'
      ? 'running'
      : 'waiting';

  const verified = latestAgent(generation, 'VERIFIED');
  const verifyState = verified
    ? stepStateFromAgent(verified.status)
    : generation?.status === 'VERIFYING' || generation?.status === 'REVERIFYING'
      ? 'running'
      : 'waiting';

  const planning = latestAgent(generation, 'PLANNING');
  const coding = latestAgent(generation, 'CODING');
  const review = latestAgent(generation, 'REVIEW');

  return [
    {
      key: 'plan',
      label: 'Plan',
      state: planning ? stepStateFromAgent(planning.status) : generation?.status === 'PLANNING' ? 'running' : 'waiting',
      detail: planning?.error ?? 'Planner Agent turns the idea into a file plan.',
    },
    {
      key: 'generate',
      label: 'Generate',
      state: coding ? stepStateFromAgent(coding.status) : generation?.status === 'CODING' ? 'running' : 'waiting',
      detail: coding?.error ?? 'Coding Agent writes the planned files.',
    },
    {
      key: 'build',
      label: 'Build & Test',
      state: buildState,
      detail:
        buildState === 'not_verified'
          ? 'Not verified: the Docker sandbox is disabled, so nothing was built or tested.'
          : execution?.failureReason ??
            (execution ? `Sandbox command: ${execution.command}` : 'Runs in the isolated Docker sandbox.'),
    },
    {
      key: 'verify',
      label: 'Verify',
      state: verifyState,
      detail:
        verifyState === 'not_verified'
          ? 'Not verified: no build/test evidence exists to verify.'
          : verified?.error ??
            (generation?.verification
              ? `${generation.verification.verdict} · ${generation.verification.buildStatus}`
              : 'Verdict comes from build + test evidence only.'),
    },
    {
      key: 'review',
      label: 'Review',
      state: review ? stepStateFromAgent(review.status) : generation?.status === 'REVIEWING' ? 'running' : 'waiting',
      detail: review?.error ?? 'Review Agent reports deterministic and AI findings.',
    },
  ];
}

function settled(generation: GenerationResponse | null): boolean {
  if (!generation) return true;
  return isTerminalGeneration(generation.status) || generation.status === 'REVIEWED';
}

/** Honest status colour: NOT_VERIFIED is amber, never green. */
function generationTone(
  status: string | undefined,
): 'gray' | 'blue' | 'green' | 'red' | 'amber' {
  switch (status) {
    case 'REVIEWED':
    case 'COMPLETED':
      return 'green';
    case 'FAILED':
      return 'red';
    case 'NOT_VERIFIED':
      return 'amber';
    case 'RUNNING':
    case 'QUEUED':
    case 'REVIEWING':
    case 'GENERATING':
      return 'blue';
    default:
      return 'gray';
  }
}

export function GeneratePage() {
  const { token } = useAuth();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const [step, setStep] = useState(1);
  const [idea, setIdea] = useState('');
  const [name, setName] = useState('');
  const [backend, setBackend] = useState<GenerationBackend>('JAVA_SPRING_BOOT');
  const [frontend, setFrontend] = useState<GenerationFrontend>('REACT_TYPESCRIPT');
  const [database, setDatabase] = useState<GenerationDatabase>('POSTGRESQL');
  const [auth, setAuth] = useState<GenerationAuth>('NONE');
  const [integrations, setIntegrations] = useState<GenerationIntegration[]>([]);

  const [dbHost, setDbHost] = useState('localhost');
  const [dbPort, setDbPort] = useState('5432');
  const [dbName, setDbName] = useState('app');
  const [dbUser, setDbUser] = useState('postgres');
  const [dbPassword, setDbPassword] = useState('');

  const [aiProvider, setAiProvider] = useState<'OPENROUTER' | 'CUSTOM' | 'NONE'>('OPENROUTER');
  const [aiModel, setAiModel] = useState(DEFAULT_MODEL);
  const [aiBaseUrl, setAiBaseUrl] = useState('');
  const [aiKey, setAiKey] = useState('');

  const [planPreview, setPlanPreview] = useState<PlanPreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewTick, setPreviewTick] = useState(0);

  const [generation, setGeneration] = useState<GenerationResponse | null>(null);
  const [creating, setCreating] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [files, setFiles] = useState<GenerationFileView[]>([]);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<GenerationFileContentView | null>(null);
  const [fileLoading, setFileLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const [restructures, setRestructures] = useState<RestructureView[]>([]);
  const [preset, setPreset] = useState<RestructurePreset>('FEATURE_FOLDERS');
  const [restructureDescription, setRestructureDescription] = useState('');
  const [restructureBusy, setRestructureBusy] = useState(false);
  const [restructureError, setRestructureError] = useState<string | null>(null);

  // Deep-link: /generate?genId=<id> opens the pipeline for an existing run.
  useEffect(() => {
    const genId = searchParams.get('genId');
    if (!token || !genId) return;
    let cancelled = false;
    getGeneration(apiClient(), token, genId)
      .then((loaded) => {
        if (cancelled) return;
        setGeneration(loaded);
        setIdea(loaded.requirement);
        setName(loaded.name);
        setBackend(loaded.backend);
        setFrontend(loaded.frontend);
        setDatabase(loaded.database);
        setAuth(loaded.auth);
        setIntegrations(loaded.integrations ?? []);
        setStep(5);
      })
      .catch(() => {
        if (!cancelled) setError('That generation could not be loaded.');
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, searchParams]);

  // Poll real backend state while a run is in flight.
  useEffect(() => {
    if (!token || !generation || settled(generation)) return;
    const id = generation.id;
    const timer = window.setInterval(() => {
      getGeneration(apiClient(), token, id)
        .then(setGeneration)
        .catch(() => {
          /* a transient poll failure is not a run failure */
        });
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [token, generation]);

  // Step 4 preview (stack-aware scaffold).
  useEffect(() => {
    if (!token || step !== 4 || idea.trim().length < MIN_IDEA) return;
    let cancelled = false;
    setPreviewLoading(true);
    setPreviewError(null);
    previewGenerationPlan(apiClient(), token, {
      requirement: idea.trim(),
      backend,
      frontend,
      database,
      auth,
      integrations,
    })
      .then((response) => {
        if (!cancelled) setPlanPreview(response);
      })
      .catch((e) => {
        if (!cancelled) setPreviewError(messageOf(e));
      })
      .finally(() => {
        if (!cancelled) setPreviewLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token, step, previewTick, idea, backend, frontend, database, auth, integrations]);

  const loadFiles = useCallback(
    async (generationId: string) => {
      if (!token) return;
      try {
        setFiles(await listGenerationFiles(apiClient(), token, generationId));
        setFilesError(null);
      } catch (e) {
        setFilesError(messageOf(e));
      }
      try {
        setRestructures(await listRestructures(apiClient(), token, generationId));
      } catch {
        setRestructures([]);
      }
    },
    [token],
  );

  useEffect(() => {
    if (generation?.artifact && token) {
      void loadFiles(generation.id);
    }
  }, [generation?.artifact?.id, generation?.id, token, loadFiles]);

  async function sendToReview() {
    if (!token || !generation || sending) return;
    if (generation.projectId) {
      navigate(`/review?project=${encodeURIComponent(generation.projectId)}`);
      return;
    }
    setSending(true);
    setError(null);
    try {
      const updated = await sendGenerationToReview(apiClient(), token, generation.id);
      setGeneration(updated);
      if (updated.projectId) {
        navigate(`/review?project=${encodeURIComponent(updated.projectId)}`);
      }
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setSending(false);
    }
  }

  function next() {
    setError(null);
    if (step === 1) {
      if (idea.trim().length < MIN_IDEA) {
        setError(`Please describe your idea in at least ${MIN_IDEA} characters.`);
        return;
      }
      setStep(2);
      return;
    }
    if (step === 2) {
      if (backend === 'NONE' && frontend === 'NONE') {
        setError('Choose at least a frontend or a backend.');
        return;
      }
      if (backend === 'NONE' && auth !== 'NONE') {
        setError('Authentication needs a backend. Pick a backend or choose None.');
        return;
      }
      if (database !== 'NONE') {
        const defaults = DB_DEFAULTS[database];
        setDbPort((value) => value || defaults.port);
        setDbUser((value) => value || defaults.user);
      }
      setStep(3);
      return;
    }
    if (step === 3) {
      setStep(4);
      setPreviewTick((value) => value + 1);
    }
  }

  function toggleIntegration(value: GenerationIntegration) {
    setIntegrations((current) =>
      current.includes(value) ? current.filter((item) => item !== value) : [...current, value],
    );
  }

  function buildInput(): CreateGenerationInput {
    const title = name.trim() || `${slugify(idea)}-${Date.now().toString(36)}`;
    return {
      name: title,
      requirement: idea.trim(),
      description: '',
      backend,
      frontend,
      database,
      auth,
      integrations,
      databaseConfig:
        database === 'NONE'
          ? undefined
          : {
              host: dbHost.trim() || 'localhost',
              port: Number(dbPort) || 5432,
              name: dbName.trim() || 'app',
              username: dbUser.trim() || 'app',
              password: dbPassword,
              sslMode: 'prefer',
            },
      aiConfig:
        aiProvider === 'NONE'
          ? { provider: 'NONE', model: 'template' }
          : {
              provider: aiProvider,
              model: aiModel.trim() || DEFAULT_MODEL,
              apiKey: aiKey,
              ...(aiProvider === 'CUSTOM' ? { baseUrl: aiBaseUrl.trim() } : {}),
            },
      draft: false,
    };
  }

  async function generate() {
    if (!token || creating) return;
    if (backend === 'NONE' && frontend === 'NONE') {
      setError('Choose at least a frontend or a backend.');
      return;
    }
    if (aiProvider !== 'NONE' && !aiKey.trim()) {
      setError('An AI API key is required, or choose the template provider (None).');
      return;
    }
    if (aiProvider === 'NONE' && (backend !== 'JAVA_SPRING_BOOT' || !['REACT_TYPESCRIPT', 'NONE'].includes(frontend))) {
      setError('The template provider currently supports Spring Boot with React or no frontend.');
      return;
    }
    setCreating(true);
    setError(null);
    try {
      const created = await createGeneration(apiClient(), token, buildInput());
      setGeneration(created);
      setStep(5);
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setCreating(false);
    }
  }

  async function download() {
    if (!token || !generation || downloading) return;
    setDownloading(true);
    try {
      const blob = await downloadGeneration(apiClient(), token, generation.id);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `${generation.name}.zip`;
      link.rel = 'noopener';
      // Some browsers ignore a click on a detached anchor and cancel the
      // download if the object URL is revoked too early.
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setDownloading(false);
    }
  }

  async function openFile(path: string) {
    if (!token || !generation) return;
    setFileLoading(true);
    setFilesError(null);
    try {
      setSelectedFile(await getGenerationFile(apiClient(), token, generation.id, path));
    } catch (e) {
      setSelectedFile(null);
      setFilesError(messageOf(e));
    } finally {
      setFileLoading(false);
    }
  }

  async function propose() {
    if (!token || !generation || restructureBusy) return;
    setRestructureBusy(true);
    setRestructureError(null);
    try {
      const proposal = await proposeRestructure(
        apiClient(),
        token,
        generation.id,
        preset,
        preset === 'CUSTOM' ? restructureDescription : undefined,
      );
      setRestructures((current) => [proposal, ...current]);
    } catch (e) {
      setRestructureError(messageOf(e));
    } finally {
      setRestructureBusy(false);
    }
  }

  async function applyProposal(proposal: RestructureView) {
    if (!token || !generation || restructureBusy) return;
    setRestructureBusy(true);
    setRestructureError(null);
    try {
      const applied = await applyRestructure(apiClient(), token, generation.id, proposal.id);
      setRestructures((current) =>
        current.map((item) => (item.id === applied.id ? applied : item)),
      );
      setSelectedFile(null);
      await loadFiles(generation.id);
    } catch (e) {
      setRestructureError(messageOf(e));
    } finally {
      setRestructureBusy(false);
    }
  }

  async function revert() {
    if (!token || !generation || restructureBusy) return;
    setRestructureBusy(true);
    setRestructureError(null);
    try {
      const restored = await revertRestructure(apiClient(), token, generation.id);
      setRestructures((current) =>
        current.map((item) => (item.id === restored.id ? restored : item)),
      );
      setSelectedFile(null);
      await loadFiles(generation.id);
    } catch (e) {
      setRestructureError(messageOf(e));
    } finally {
      setRestructureBusy(false);
    }
  }

  function reset() {
    setGeneration(null);
    setFiles([]);
    setFilesError(null);
    setSelectedFile(null);
    setRestructures([]);
    setStep(1);
    setError(null);
    if (searchParams.has('genId')) {
      const next = new URLSearchParams(searchParams);
      next.delete('genId');
      setSearchParams(next, { replace: true });
    }
  }

  const stages = pipelineStages(generation);
  const latestProposal = restructures[0];
  const applied = restructures.find((item) => item.status === 'APPLIED');

  return (
    <FeaturePage
      title="Generate"
      description="Describe the idea, choose the stack and integrations, review the plan, then watch the real pipeline."
      steps={STEPS}
      current={step - 1}
      actions={
        <Link to="/projects" className={secondaryButtonClass}>
          Projects
        </Link>
      }
    >
      {error && <ErrorAlert message={error} />}

      {step === 1 && (
        <Panel className="space-y-5">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">Describe your project</h2>
            <p className="mt-1 text-sm text-slate-600">
              One or two sentences are enough. You will choose the stack next.
            </p>
          </div>
          <div>
            <label htmlFor="idea" className="mb-1 block text-sm font-medium text-slate-700">
              Project idea
            </label>
            <textarea
              id="idea"
              className={`${textareaClass} min-h-32`}
              value={idea}
              onChange={(event) => setIdea(event.target.value)}
              placeholder="A task tracker where teams create projects, assign tasks, and comment."
            />
            <p className="mt-1 text-xs text-slate-500">
              {idea.trim().length}/{MIN_IDEA} characters minimum
            </p>
          </div>
          <div>
            <label htmlFor="name" className="mb-1 block text-sm font-medium text-slate-700">
              Project title (optional)
            </label>
            <input
              id="name"
              className={inputClass}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="task-tracker"
            />
          </div>
          <div className="flex justify-end">
            <button type="button" className={primaryButtonClass} onClick={next}>
              Choose stack
            </button>
          </div>
        </Panel>
      )}

      {step === 2 && (
        <Panel className="space-y-5">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">Choose your stack</h2>
            <p className="mt-1 text-sm text-slate-600">
              Explicit choices only. Nothing is inferred from the description.
            </p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Select label="Frontend" value={frontend} onChange={(value) => setFrontend(value as GenerationFrontend)}>
              {Object.entries(FRONTEND_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </Select>
            <Select label="Backend" value={backend} onChange={(value) => setBackend(value as GenerationBackend)}>
              {Object.entries(BACKEND_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </Select>
            <Select
              label="Database"
              value={database}
              onChange={(value) => {
                setDatabase(value as GenerationDatabase);
                const defaults = DB_DEFAULTS[value as GenerationDatabase];
                setDbPort(defaults.port);
                setDbUser(defaults.user);
              }}
            >
              {Object.entries(DATABASE_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </Select>
            <Select label="Authentication" value={auth} onChange={(value) => setAuth(value as GenerationAuth)}>
              {Object.entries(AUTH_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </Select>
          </div>
          {(backend === 'NONE' && frontend === 'NONE') && (
            <p className="text-sm text-amber-700">Pick at least a frontend or a backend.</p>
          )}
          <div className="flex justify-between">
            <button type="button" className={secondaryButtonClass} onClick={() => setStep(1)}>Back</button>
            <button type="button" className={primaryButtonClass} onClick={next}>Choose integrations</button>
          </div>
        </Panel>
      )}

      {step === 3 && (
        <Panel className="space-y-5">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">Integrations</h2>
            <p className="mt-1 text-sm text-slate-600">
              Optional. Each selection adds a thin adapter and an environment placeholder — never a credential.
            </p>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            {INTEGRATIONS.map((item) => (
              <label
                key={item.value}
                className={`flex cursor-pointer gap-3 rounded-xl border p-3 transition-colors ${
                  integrations.includes(item.value)
                    ? 'border-indigo-300 bg-indigo-50'
                    : 'border-slate-200 bg-white hover:border-slate-300'
                }`}
              >
                <input
                  type="checkbox"
                  className="mt-1 h-4 w-4"
                  checked={integrations.includes(item.value)}
                  onChange={() => toggleIntegration(item.value)}
                  aria-label={item.label}
                />
                <span>
                  <span className="block text-sm font-semibold text-slate-900">{item.label}</span>
                  <span className="block text-xs text-slate-600">{item.note}</span>
                </span>
              </label>
            ))}
          </div>
          <div className="flex justify-between">
            <button type="button" className={secondaryButtonClass} onClick={() => setStep(2)}>Back</button>
            <button type="button" className={primaryButtonClass} onClick={next}>Review plan</button>
          </div>
        </Panel>
      )}

      {step === 4 && (
        <div className="space-y-6">
          <Panel className="space-y-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="text-lg font-semibold text-slate-900">Review the plan</h2>
                <p className="mt-1 text-sm text-slate-600">
                  Stack-aware scaffold preview. The Planner Agent produces the final file plan after you generate.
                </p>
              </div>
              <button type="button" className={secondaryButtonClass} onClick={() => setPreviewTick((value) => value + 1)}>
                Re-plan
              </button>
            </div>

            {previewLoading && <LoadingState label="Building the plan preview…" />}
            {previewError && <ErrorAlert message={previewError} />}

            {planPreview && !previewLoading && (
              <div className="space-y-4">
                <div className="rounded-xl bg-slate-50 p-3 text-sm text-slate-700">
                  <p className="font-semibold text-slate-900">Architecture</p>
                  <p className="mt-1">{planPreview.architecture}</p>
                  <p className="mt-2 text-xs text-slate-500">{planPreview.notes}</p>
                </div>
                <div className="grid gap-4 lg:grid-cols-2">
                  <div>
                    <h3 className="text-sm font-semibold text-slate-900">Folders ({planPreview.directories.length})</h3>
                    <ul className="mt-2 max-h-56 space-y-1 overflow-auto rounded-xl border border-slate-200 p-3 text-sm text-slate-700">
                      {planPreview.directories.map((directory) => (
                        <li key={directory} className="font-mono text-xs">{directory}/</li>
                      ))}
                    </ul>
                  </div>
                  <div>
                    <h3 className="text-sm font-semibold text-slate-900">Files ({planPreview.files.length})</h3>
                    <ul className="mt-2 max-h-56 space-y-1 overflow-auto rounded-xl border border-slate-200 p-3 text-sm">
                      {planPreview.files.map((file) => (
                        <li key={file.path} className="flex flex-col">
                          <span className="font-mono text-xs text-slate-900">{file.path}</span>
                          <span className="text-xs text-slate-500">{file.purpose}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </div>
                {planPreview.dependencies.length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {planPreview.dependencies.map((dependency) => (
                      <Badge key={dependency} tone="gray">{dependency}</Badge>
                    ))}
                  </div>
                )}
                <div className="flex flex-wrap gap-2">
                  <button type="button" className={secondaryButtonClass} onClick={() => setStep(2)}>
                    Edit stack
                  </button>
                  <button type="button" className={secondaryButtonClass} onClick={() => setStep(3)}>
                    Edit integrations
                  </button>
                </div>
              </div>
            )}
          </Panel>

          <Panel className="space-y-4">
            <h2 className="text-lg font-semibold text-slate-900">Generation engine</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              <Select
                label="AI provider"
                value={aiProvider}
                onChange={(value) => setAiProvider(value as 'OPENROUTER' | 'CUSTOM' | 'NONE')}
              >
                <option value="OPENROUTER">OpenRouter</option>
                <option value="CUSTOM">Custom (OpenAI-compatible)</option>
                <option value="NONE">None — deterministic templates</option>
              </Select>
              <div>
                <label htmlFor="ai-model" className="mb-1 block text-sm font-medium text-slate-700">AI model</label>
                <input
                  id="ai-model"
                  className={inputClass}
                  value={aiModel}
                  onChange={(event) => setAiModel(event.target.value)}
                />
              </div>
              {aiProvider === 'CUSTOM' && (
                <div className="sm:col-span-2">
                  <label htmlFor="ai-base-url" className="mb-1 block text-sm font-medium text-slate-700">Base URL</label>
                  <input
                    id="ai-base-url"
                    className={inputClass}
                    value={aiBaseUrl}
                    onChange={(event) => setAiBaseUrl(event.target.value)}
                    placeholder="https://api.example.com/v1"
                  />
                </div>
              )}
              {aiProvider !== 'NONE' && (
                <div className="sm:col-span-2">
                  <label htmlFor="ai-key" className="mb-1 block text-sm font-medium text-slate-700">AI API key</label>
                  <input
                    id="ai-key"
                    type="password"
                    autoComplete="off"
                    className={inputClass}
                    value={aiKey}
                    onChange={(event) => setAiKey(event.target.value)}
                    placeholder="Sent once, held in memory, never stored"
                  />
                </div>
              )}
            </div>
            {database !== 'NONE' && (
              <details className="rounded-xl border border-slate-200 p-3">
                <summary className="cursor-pointer text-sm font-semibold text-slate-900">Database connection</summary>
                <div className="mt-3 grid gap-3 sm:grid-cols-2">
                  <Field label="DB host" htmlFor="db-host" value={dbHost} onChange={setDbHost} />
                  <Field label="DB port" htmlFor="db-port" value={dbPort} onChange={setDbPort} />
                  <Field label="DB name" htmlFor="db-name" value={dbName} onChange={setDbName} />
                  <Field label="DB user" htmlFor="db-user" value={dbUser} onChange={setDbUser} />
                  <div className="sm:col-span-2">
                    <label htmlFor="db-password" className="mb-1 block text-sm font-medium text-slate-700">DB password</label>
                    <input
                      id="db-password"
                      type="password"
                      autoComplete="off"
                      className={inputClass}
                      value={dbPassword}
                      onChange={(event) => setDbPassword(event.target.value)}
                      placeholder="Optional; never stored"
                    />
                  </div>
                </div>
              </details>
            )}
            <div className="flex flex-wrap justify-between gap-3">
              <button type="button" className={secondaryButtonClass} onClick={() => setStep(3)}>Back</button>
              <button type="button" className={primaryButtonClass} onClick={() => void generate()} disabled={creating}>
                {creating ? 'Starting…' : 'Generate project'}
              </button>
            </div>
          </Panel>
        </div>
      )}

      {step === 5 && (
        <div className="space-y-6">
          <Panel className="space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-lg font-semibold text-slate-900">Generation pipeline</h2>
                <p className="mt-1 text-sm text-slate-600">
                  Every stage is derived from the backend's agent execution and build evidence rows.
                </p>
              </div>
              <Badge tone={generationTone(generation?.status)}>
                {generation?.status ?? 'STARTING'}
              </Badge>
            </div>

            <ol className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              {stages.map((stage, index) => {
                const display = buildState(stage.state);
                return (
                  <li key={stage.key} className="rounded-xl border border-slate-200 p-3">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-semibold text-slate-900">{index + 1}. {stage.label}</span>
                      <Badge tone={display.tone}>{display.label}</Badge>
                    </div>
                    <p className="mt-2 text-xs leading-5 text-slate-600">{stage.detail}</p>
                  </li>
                );
              })}
            </ol>

            {generation?.execution?.notVerified && (
              <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
                <strong>Not verified.</strong> The Docker sandbox is disabled, so Build &amp; Test did not run.
                This run can still be reviewed, but the download stays locked until a real verification passes.
              </div>
            )}
            {generation?.status === 'FAILED' && (
              <ErrorAlert message={generation.error ?? 'Generation failed.'} />
            )}
            {generation && !settled(generation) && (
              <p className="text-xs text-slate-500">Polling the backend every {POLL_MS / 1000}s…</p>
            )}
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                className={primaryButtonClass}
                onClick={() => void sendToReview()}
                disabled={
                  sending ||
                  !generation ||
                  (!generation.projectId &&
                    generation.status !== 'REVIEWED' &&
                    generation.status !== 'COMPLETED')
                }
                title={
                  generation?.projectId ||
                  generation?.status === 'REVIEWED' ||
                  generation?.status === 'COMPLETED'
                    ? 'Open this project in Review'
                    : 'Available after the review stage completes'
                }
              >
                {sending ? 'Sending…' : 'Send to Review'}
              </button>
              <button
                type="button"
                className={secondaryButtonClass}
                onClick={() => void download()}
                disabled={!generation?.downloadReady || downloading}
                title={generation?.downloadReady ? 'Download the generated ZIP' : 'Available after verification'}
              >
                {downloading ? 'Preparing…' : 'Download ZIP'}
              </button>
              <button type="button" className={secondaryButtonClass} onClick={reset}>Start another</button>
            </div>
          </Panel>

          {generation?.artifact && (
            <Panel className="space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-lg font-semibold text-slate-900">Generated project</h2>
                  <p className="mt-1 text-sm text-slate-600">
                    {generation.artifact.fileCount} files · {files.length} browsable
                  </p>
                </div>
                <Badge tone="gray">iteration {generation.artifact.iteration}</Badge>
              </div>
              {filesError && (
                <ErrorAlert
                  message={filesError}
                  onRetry={() => void loadFiles(generation.id)}
                />
              )}
              <div className="grid gap-4 lg:grid-cols-2">
                <div className="max-h-96 overflow-auto rounded-xl border border-slate-200">
                  {files.length === 0 ? (
                    <p className="p-4 text-sm text-slate-500">No files listed yet.</p>
                  ) : (
                    <ul className="divide-y divide-slate-100">
                      {files.map((file) => (
                        <li key={file.path}>
                          <button
                            type="button"
                            onClick={() => void openFile(file.path)}
                            className={`flex w-full items-center justify-between gap-3 px-3 py-2 text-left text-xs hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-600 ${
                              selectedFile?.path === file.path ? 'bg-indigo-50' : ''
                            }`}
                          >
                            <span className="truncate font-mono text-slate-800">{file.path}</span>
                            <span className="shrink-0 text-slate-400">{formatBytes(file.sizeBytes)}</span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
                <div className="min-h-40 rounded-xl bg-slate-950 p-3 text-xs text-slate-100">
                  {fileLoading ? (
                    <p className="text-slate-300">Loading…</p>
                  ) : selectedFile ? (
                    selectedFile.binary ? (
                      <p className="text-amber-300">Binary file — not shown. Download the ZIP to inspect it.</p>
                    ) : (
                      <div className="space-y-2">
                        <p className="font-mono text-[11px] text-slate-400">{selectedFile.path}</p>
                        <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words">{selectedFile.content}</pre>
                        {selectedFile.truncated && <p className="text-amber-300">Preview truncated at 256 KB.</p>}
                      </div>
                    )
                  ) : (
                    <p className="text-slate-400">Select a file to preview its text.</p>
                  )}
                </div>
              </div>
            </Panel>
          )}

          {generation?.artifact && (
            <Panel className="space-y-4">
              <div>
                <h2 className="text-lg font-semibold text-slate-900">Restructure my project</h2>
                <p className="mt-1 text-sm text-slate-600">
                  Propose a new tree first. Nothing moves until you approve it, and the original is kept so Revert works.
                </p>
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <label htmlFor="restructure-preset" className="mb-1 block text-sm font-medium text-slate-700">Preset</label>
                  <select
                    id="restructure-preset"
                    className={`${selectClass} w-full`}
                    value={preset}
                    onChange={(event) => setPreset(event.target.value as RestructurePreset)}
                  >
                    {PRESETS.map((item) => (
                      <option key={item.value} value={item.value}>{item.label} — {item.note}</option>
                    ))}
                  </select>
                </div>
                {preset === 'CUSTOM' && (
                  <div>
                    <label htmlFor="restructure-description" className="mb-1 block text-sm font-medium text-slate-700">
                      Describe the target structure
                    </label>
                    <textarea
                      id="restructure-description"
                      className={`${textareaClass} min-h-20`}
                      value={restructureDescription}
                      onChange={(event) => setRestructureDescription(event.target.value)}
                      placeholder={'services/: UserService.java\ncontrollers/: UserController.java'}
                    />
                  </div>
                )}
              </div>
              {restructureError && <ErrorAlert message={restructureError} />}
              <div className="flex flex-wrap gap-2">
                <button
                  type="button"
                  className={secondaryButtonClass}
                  onClick={() => void propose()}
                  disabled={
                    restructureBusy || (preset === 'CUSTOM' && !restructureDescription.trim())
                  }
                >
                  {restructureBusy ? 'Working…' : 'Propose new structure'}
                </button>
                {applied && (
                  <button type="button" className={secondaryButtonClass} onClick={() => void revert()} disabled={restructureBusy}>
                    Revert to original
                  </button>
                )}
              </div>

              {latestProposal && (
                <div className="rounded-xl border border-slate-200 p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-sm font-semibold text-slate-900">
                      {latestProposal.preset} proposal
                    </span>
                    <Badge
                      tone={
                        latestProposal.status === 'APPLIED'
                          ? 'green'
                          : latestProposal.status === 'REVERTED'
                            ? 'gray'
                            : 'amber'
                      }
                    >
                      {latestProposal.status}
                    </Badge>
                  </div>
                  <p className="mt-1 text-xs text-slate-600">{latestProposal.notes}</p>
                  <ul className="mt-2 max-h-48 space-y-1 overflow-auto text-xs">
                    {latestProposal.moves.map((move) => (
                      <li key={`${move.from}-${move.to}`} className="break-all font-mono text-slate-700">
                        {move.from} → {move.to}
                      </li>
                    ))}
                  </ul>
                  {latestProposal.status === 'PROPOSED' && (
                    <button
                      type="button"
                      className={`${primaryButtonClass} mt-3`}
                      onClick={() => void applyProposal(latestProposal)}
                      disabled={restructureBusy}
                    >
                      Approve &amp; apply
                    </button>
                  )}
                  {latestProposal.status === 'APPLIED' && (
                    <p className="mt-2 text-xs text-amber-700">
                      Structure applied. Re-run verification from Verify after reviewing the moved files.
                    </p>
                  )}
                </div>
              )}
            </Panel>
          )}
        </div>
      )}
    </FeaturePage>
  );
}

/** Local panel so wizard sections can stack their own children without the
 *  shared Card header contract. */
function Panel({
  className = '',
  children,
}: {
  className?: string;
  children: ReactNode;
}) {
  return (
    <section
      className={`rounded-2xl border border-indigo-100 bg-white p-5 text-sm text-slate-600 shadow-sm shadow-indigo-100 ${className}`}
    >
      {children}
    </section>
  );
}

function Select({
  label,
  value,
  onChange,
  children,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  children: ReactNode;
}) {
  const id = `select-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
  return (
    <div>
      <label className="mb-1 block text-sm font-medium text-slate-700" htmlFor={id}>
        {label}
      </label>
      <select
        id={id}
        aria-label={label}
        className={`${selectClass} w-full`}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        {children}
      </select>
    </div>
  );
}

function Field({
  label,
  htmlFor,
  value,
  onChange,
}: {
  label: string;
  htmlFor: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <label htmlFor={htmlFor} className="mb-1 block text-sm font-medium text-slate-700">{label}</label>
      <input
        id={htmlFor}
        className={inputClass}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'Something went wrong. Please try again.';
}
