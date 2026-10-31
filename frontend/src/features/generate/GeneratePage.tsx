import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import {
  CheckCircle2,
  Circle,
  CircleAlert,
  CircleX,
  Clock3,
  Code2,
  Eye,
  FileCode2,
  LoaderCircle,
  ShieldCheck,
} from 'lucide-react';
import { ApiError } from '../../api/client';
import {
  applyRestructure,
  approveGenerationFix,
  createGeneration,
  downloadGeneration,
  getGeneration,
  getGenerationFile,
  getGenerationQuestions,
  getGenerationWizardCatalog,
  validateGenerationWizardStack,
  isTerminalGeneration,
  listGenerationFiles,
  listRestructures,
  previewWizardPlan,
  previewGenerationFix,
  proposeRestructure,
  revertRestructure,
  revertGenerationFixRound,
  retryGenerationStage,
  sendGenerationToReview,
  streamGenerationUpdates,
} from '../../api/generations';
import type {
  CreateGenerationInput,
  ExecutionView,
  GenerationAnswer,
  GenerationAuth,
  GenerationBackend,
  GenerationDatabase,
  GenerationFileContentView,
  GenerationFileView,
  GenerationFixPreviewResponse,
  GenerationFindingView,
  GenerationFrontend,
  GenerationIntegration,
  GenerationQuestion,
  GenerationQuestionsInput,
  GenerationResponse,
  GenerationWizardCatalog,
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
import {
  clearGenerateDrafts,
  GENERATE_DRAFT_VERSION,
  readGenerateDraft,
  writeGenerateDraft,
  type GenerateDraft,
} from './draftStorage';

const POLL_MS = 3000;
const MIN_IDEA = 20;
const STEPS = ['Project', 'Engine', 'Stack', 'Questions', 'Review plan'] as const;

const BACKEND_LABELS: Record<GenerationBackend, string> = {
  JAVA_SPRING_BOOT: 'Spring Boot',
  NODEJS: 'Node / Express',
  PYTHON_FASTAPI: 'FastAPI',
  OTHER: 'Other (type your own)',
  NONE: 'None',
};

const FRONTEND_LABELS: Record<GenerationFrontend, string> = {
  REACT_TYPESCRIPT: 'React',
  NEXTJS: 'Next.js',
  VUE: 'Vue',
  OTHER: 'Other (type your own)',
  NONE: 'None',
};

const INTEGRATION_VALUES: GenerationIntegration[] = [
  'STRIPE', 'RAZORPAY', 'EMAIL', 'FILE_STORAGE_S3',
  'AI_LLM', 'MAPS', 'ANALYTICS', 'DOCKER_CI',
];

const ENGINE_FALLBACK: GenerationWizardCatalog = {
  engines: [
    {
      id: 'TEMPLATE',
      label: 'Template',
      description: 'Fast, no AI key, Spring Boot + React only.',
      supportedBackends: ['JAVA_SPRING_BOOT'],
      supportedFrontends: ['REACT_TYPESCRIPT', 'NONE'],
    },
    {
      id: 'AI',
      label: 'AI',
      description: 'Any stack, needs your AI key.',
      supportedBackends: ['JAVA_SPRING_BOOT', 'PYTHON_FASTAPI', 'NODEJS', 'OTHER', 'NONE'],
      supportedFrontends: ['REACT_TYPESCRIPT', 'NEXTJS', 'VUE', 'OTHER', 'NONE'],
    },
  ],
};

const PRESETS: { value: RestructurePreset; label: string; note: string }[] = [
  { value: 'MVC', label: 'MVC', note: 'controllers · services · repositories · models' },
  { value: 'LAYERED', label: 'Layered', note: 'presentation · domain · infrastructure' },
  { value: 'FEATURE_FOLDERS', label: 'Feature folders', note: 'one folder per feature' },
  { value: 'CUSTOM', label: 'Describe my own', note: 'e.g. services/: UserService.java' },
];

type StageState = 'waiting' | 'running' | 'done' | 'failed' | 'skipped';

function stepStateFromAgent(step: string | undefined): StageState {
  switch (step) {
    case 'COMPLETED':
      return 'done';
    case 'FAILED':
      return 'failed';
    case 'RUNNING':
      return 'running';
    case 'PENDING':
      return 'waiting';
    case 'SKIPPED':
      return 'skipped';
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
          : state === 'skipped'
            ? 'amber'
            : 'gray';
  const label =
    state === 'done'
      ? 'Done'
      : state === 'failed'
        ? 'Failed'
        : state === 'running'
          ? 'Running'
          : state === 'skipped'
            ? 'Skipped'
            : 'Waiting';
  return { tone: tone as 'green' | 'red' | 'blue' | 'amber' | 'gray', label };
}

function stageIcon(state: StageState) {
  if (state === 'running') return LoaderCircle;
  if (state === 'done') return CheckCircle2;
  if (state === 'failed') return CircleX;
  if (state === 'skipped') return CircleAlert;
  return Circle;
}

function elapsedLabel(startedAt: string | null, finishedAt: string | null, now: number): string {
  if (!startedAt) return 'Not started';
  const end = finishedAt ? Date.parse(finishedAt) : now;
  const start = Date.parse(startedAt);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return 'Elapsed unavailable';
  const seconds = Math.max(0, Math.floor((end - start) / 1000));
  return `${Math.floor(seconds / 60)}m ${String(seconds % 60).padStart(2, '0')}s`;
}

function codeLanguage(path: string): string {
  const extension = path.split('.').pop()?.toLowerCase();
  if (extension === 'tsx' || extension === 'ts') return 'TypeScript';
  if (extension === 'jsx' || extension === 'js') return 'JavaScript';
  if (extension === 'html') return 'HTML';
  if (extension === 'css') return 'CSS';
  if (extension === 'java') return 'Java';
  if (extension === 'py') return 'Python';
  return 'Text';
}

function highlightedLine(line: string, key: string) {
  const parts = line.split(/(\/\/.*|#.*|"[^"]*"|'[^']*'|`[^`]*`|\b(?:const|let|var|function|return|class|public|private|import|from|def|if|else|new)\b)/g);
  return (
    <span key={key}>
      {parts.map((part, index) => {
        const isComment = part.startsWith('//') || part.startsWith('#');
        const isString = /^["'`]/.test(part);
        const isKeyword = /^(const|let|var|function|return|class|public|private|import|from|def|if|else|new)$/.test(part);
        return (
          <span key={`${key}-${index}`} className={isComment ? 'text-slate-500' : isString ? 'text-emerald-300' : isKeyword ? 'text-violet-300' : undefined}>
            {part}
          </span>
        );
      })}
    </span>
  );
}

function latestAgent(generation: GenerationResponse | null, type: string) {
  if (!generation) return undefined;
  return [...(generation.agentWorkflow ?? [])]
    .filter((step) => step.agentType === type)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
}

function pipelineStages(generation: GenerationResponse | null): {
  key: string;
  label: string;
  role: string;
  state: StageState;
  detail: string;
  startedAt: string | null;
  finishedAt: string | null;
  activityLog: string | null;
  executionId: string | null;
  roundNumber: number;
}[] {
  const execution: ExecutionView | null = generation?.execution ?? null;
  const verified = latestAgent(generation, 'VERIFIED');
  const verifyState: StageState = execution?.notVerified ||
    execution?.buildStatus === 'SKIPPED' ||
    execution?.testStatus === 'SKIPPED'
    ? 'skipped'
    : execution?.buildStatus === 'FAILURE' ||
        execution?.buildStatus === 'TIMEOUT' ||
        execution?.testStatus === 'FAILURE'
      ? 'failed'
      : verified
        ? stepStateFromAgent(verified.status)
        : generation?.status === 'BUILDING' ||
            generation?.status === 'TESTING' ||
            generation?.status === 'VERIFYING' ||
            generation?.status === 'REVERIFYING'
          ? 'running'
          : 'waiting';

  const planning = latestAgent(generation, 'PLANNING');
  const coding = latestAgent(generation, 'CODING');
  const review = latestAgent(generation, 'REVIEW');
  const rating = latestAgent(generation, 'RATING');

  return [
    {
      key: 'plan',
      label: 'Planning',
      role: 'Turns your answers into a project plan.',
      state: planning ? stepStateFromAgent(planning.status) : generation?.status === 'PLANNING' ? 'running' : 'waiting',
      detail: planning?.error ?? 'Planner Agent turns the idea into a file plan.',
      startedAt: planning?.createdAt ?? null,
      finishedAt: planning?.finishedAt ?? null,
      activityLog: planning?.activityLog ?? null,
      executionId: planning?.id ?? null,
      roundNumber: planning?.roundNumber ?? generation?.iteration ?? 1,
    },
    {
      key: 'coding',
      label: 'Coding',
      role: 'Writes the approved project files.',
      state: coding ? stepStateFromAgent(coding.status) : generation?.status === 'CODING' ? 'running' : 'waiting',
      detail: coding?.error ?? 'Coding Agent writes the planned files.',
      startedAt: coding?.createdAt ?? null,
      finishedAt: coding?.finishedAt ?? null,
      activityLog: coding?.activityLog ?? null,
      executionId: coding?.id ?? null,
      roundNumber: coding?.roundNumber ?? generation?.iteration ?? 1,
    },
    {
      key: 'review',
      label: 'Reviewing',
      role: 'Finds logic, security, and quality issues.',
      state: review ? stepStateFromAgent(review.status) : generation?.status === 'REVIEWING' ? 'running' : 'waiting',
      detail: review?.error ?? 'Review Agent reports deterministic and AI findings.',
      startedAt: review?.createdAt ?? null,
      finishedAt: review?.finishedAt ?? null,
      activityLog: review?.activityLog ?? null,
      executionId: review?.id ?? null,
      roundNumber: review?.roundNumber ?? generation?.iteration ?? 1,
    },
    {
      key: 'verify',
      label: 'Verifying',
      role: 'Runs available syntax, build, and test checks.',
      state: verifyState,
      detail:
        verifyState === 'skipped'
          ? 'Build and tests not run: sandbox unavailable.'
          : verified?.error ??
            execution?.failureReason ??
            (generation?.verification
              ? `${generation.verification.verdict} · ${generation.verification.buildStatus}`
              : 'Waiting for syntax, build, and test evidence.'),
      startedAt: verified?.createdAt ?? execution?.createdAt ?? null,
      finishedAt: verified?.finishedAt ?? null,
      activityLog: verified?.activityLog ?? execution?.command ?? null,
      executionId: verified?.id ?? null,
      roundNumber: verified?.roundNumber ?? generation?.iteration ?? 1,
    },
    {
      key: 'rating',
      label: 'Rating',
      role: 'Scores the latest persisted findings.',
      state: rating ? stepStateFromAgent(rating.status) : 'waiting',
      detail: rating?.error ?? (generation?.rating ? `Overall score: ${generation.rating.overall}/100.` : 'Waiting for review findings.'),
      startedAt: rating?.createdAt ?? null,
      finishedAt: rating?.finishedAt ?? null,
      activityLog: rating?.activityLog ?? null,
      executionId: rating?.id ?? null,
      roundNumber: rating?.roundNumber ?? generation?.iteration ?? 1,
    },
  ];
}

function settled(generation: GenerationResponse | null): boolean {
  if (!generation) return true;
  const rating = latestAgent(generation, 'RATING');
  return isTerminalGeneration(generation.status) ||
    (generation.status === 'REVIEWED' &&
      (rating?.status === 'COMPLETED' || rating?.status === 'FAILED'));
}

function findingsByFile(
  findings: GenerationFindingView[],
): [string, GenerationFindingView[]][] {
  const grouped = new Map<string, GenerationFindingView[]>();
  for (const finding of findings) {
    const file = finding.file || 'Project-wide';
    grouped.set(file, [...(grouped.get(file) ?? []), finding]);
  }
  const severityRank: Record<string, number> = {
    CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3, INFO: 4,
  };
  return [...grouped.entries()].map(([file, items]) => [
    file,
    [...items].sort((a, b) =>
      (severityRank[a.severity] ?? 5) - (severityRank[b.severity] ?? 5)),
  ]);
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
    case 'AWAITING_FIX_APPROVAL':
      return 'amber';
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
  const { token, user } = useAuth();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const [step, setStep] = useState(1);
  const [draftCandidate, setDraftCandidate] = useState<GenerateDraft | null>(null);
  const [draftResolved, setDraftResolved] = useState(false);
  const [name, setName] = useState('');
  const [idea, setIdea] = useState('');
  const [extraInfo, setExtraInfo] = useState('');
  const [generationEngine, setGenerationEngine] = useState<'TEMPLATE' | 'AI' | ''>('');
  const [wizardCatalog, setWizardCatalog] = useState<GenerationWizardCatalog | null>(null);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [wizardChangeNotice, setWizardChangeNotice] = useState<string | null>(null);
  const [backend, setBackend] = useState<GenerationBackend | ''>('');
  const [frontend, setFrontend] = useState<GenerationFrontend | ''>('');
  const [languageRuntime, setLanguageRuntime] = useState('');
  const [backendCustom, setBackendCustom] = useState('');
  const [frontendCustom, setFrontendCustom] = useState('');
  const [stackSuggestionsOpen, setStackSuggestionsOpen] = useState(false);

  const [questions, setQuestions] = useState<GenerationQuestion[]>([]);
  const [answers, setAnswers] = useState<Record<string, { value: string; skipped: boolean }>>({});
  const [questionsLoading, setQuestionsLoading] = useState(false);
  const [questionsError, setQuestionsError] = useState<string | null>(null);
  const [questionErrors, setQuestionErrors] = useState<string[]>([]);
  const questionControlRefs = useRef<Record<string, HTMLElement | null>>({});
  const skipNextDraftSave = useRef(false);

  const [dbHost, setDbHost] = useState('');
  const [dbPort, setDbPort] = useState('');
  const [dbName, setDbName] = useState('');
  const [dbUser, setDbUser] = useState('');
  const [dbPassword, setDbPassword] = useState('');

  const [planPreview, setPlanPreview] = useState<PlanPreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewTick, setPreviewTick] = useState(0);

  const [generation, setGeneration] = useState<GenerationResponse | null>(null);
  const [sseConnected, setSseConnected] = useState(false);
  const [creating, setCreating] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [files, setFiles] = useState<GenerationFileView[]>([]);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [selectedFile, setSelectedFile] = useState<GenerationFileContentView | null>(null);
  const [previewTab, setPreviewTab] = useState<'code' | 'live'>('code');
  const [clock, setClock] = useState(() => Date.now());
  const [tabVisible, setTabVisible] = useState(() => document.visibilityState === 'visible');
  const [fileLoading, setFileLoading] = useState(false);
  const [downloading, setDownloading] = useState(false);

  const [restructures, setRestructures] = useState<RestructureView[]>([]);
  const [preset, setPreset] = useState<RestructurePreset>('FEATURE_FOLDERS');
  const [restructureDescription, setRestructureDescription] = useState('');
  const [restructureBusy, setRestructureBusy] = useState(false);
  const [restructureError, setRestructureError] = useState<string | null>(null);
  const [selectedFindingIds, setSelectedFindingIds] = useState<string[]>([]);
  const [fixPreview, setFixPreview] = useState<GenerationFixPreviewResponse | null>(null);
  const [fixDialogOpen, setFixDialogOpen] = useState(false);
  const [fixApiKey, setFixApiKey] = useState('');
  const [fixBusy, setFixBusy] = useState(false);
  const [fixError, setFixError] = useState<string | null>(null);
  const [retryExecutionId, setRetryExecutionId] = useState<string | null>(null);
  const [retryApiKey, setRetryApiKey] = useState('');
  const [retryBusy, setRetryBusy] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);
  const [revertRoundNumber, setRevertRoundNumber] = useState<number | null>(null);
  const fixDialogRef = useRef<HTMLElement>(null);
  const retryDialogRef = useRef<HTMLElement>(null);
  const revertDialogRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const timer = window.setInterval(() => setClock(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const onVisibilityChange = () => setTabVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => document.removeEventListener('visibilitychange', onVisibilityChange);
  }, []);

  useEffect(() => {
    if (step !== 4 || questionErrors.length === 0) return;
    const firstId = questionErrors[0];
    const control = questionControlRefs.current[firstId]
      ?? document.getElementById('db-host');
    window.setTimeout(() => control?.focus(), 0);
  }, [step, questionErrors]);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    setCatalogLoading(true);
    getGenerationWizardCatalog(apiClient(), token)
      .then((catalog) => {
        if (!cancelled) {
          setWizardCatalog(catalog);
          setCatalogError(null);
        }
      })
      .catch(() => {
        if (!cancelled) setCatalogError('Generation options could not be loaded. Retry to continue.');
      })
      .finally(() => {
        if (!cancelled) setCatalogLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  useEffect(() => {
    if (!user?.id) {
      setDraftCandidate(null);
      setDraftResolved(false);
      return;
    }
    try {
      setDraftCandidate(readGenerateDraft(user.id));
    } catch {
      setDraftCandidate(null);
    } finally {
      setDraftResolved(true);
    }
  }, [user?.id]);

  useEffect(() => {
    if (!user?.id || !draftResolved || draftCandidate) return;
    if (skipNextDraftSave.current) {
      skipNextDraftSave.current = false;
      return;
    }
    const safeAnswers = Object.fromEntries(
      Object.entries(answers).filter(([id]) => ![
        'generation.apiKey', 'database.connection',
      ].includes(id)),
    );
    if (!name.trim() && !idea.trim() && !extraInfo.trim()
        && !generationEngine && !backend && !frontend && !languageRuntime.trim()
        && !backendCustom.trim() && !frontendCustom.trim()
        && Object.keys(safeAnswers).length === 0) {
      return;
    }
    writeGenerateDraft({
      version: GENERATE_DRAFT_VERSION,
      userId: user.id,
      name, idea, extraInfo, generationEngine, backend, frontend, languageRuntime,
      backendCustom, frontendCustom, answers: safeAnswers,
    });
  }, [
    user?.id, draftResolved, draftCandidate,
    name, idea, extraInfo, generationEngine, backend, frontend, languageRuntime,
    backendCustom, frontendCustom, answers,
  ]);

  const unresolvedFindingIds = (generation?.findings ?? [])
    .filter((finding) => ['OPEN', 'FIX_REQUESTED', 'FIX_PROPOSED'].includes(finding.status))
    .map((finding) => finding.id);
  const unresolvedFindingKey = unresolvedFindingIds.join('|');

  useEffect(() => {
    setSelectedFindingIds(unresolvedFindingKey ? unresolvedFindingKey.split('|') : []);
  }, [generation?.id, generation?.iteration, unresolvedFindingKey]);

  useEffect(() => {
    const dialog = fixDialogOpen
      ? fixDialogRef.current
      : retryExecutionId
        ? retryDialogRef.current
        : revertRoundNumber !== null
          ? revertDialogRef.current
          : null;
    if (!dialog) return;
    const focusable = dialog.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    focusable[0]?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        if (fixDialogOpen) {
          setFixDialogOpen(false);
          setFixPreview(null);
          setFixApiKey('');
        } else if (retryExecutionId) {
          setRetryExecutionId(null);
          setRetryApiKey('');
          setRetryError(null);
        } else {
          setRevertRoundNumber(null);
        }
        return;
      }
      if (event.key !== 'Tab' || focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    dialog.addEventListener('keydown', onKeyDown);
    return () => dialog.removeEventListener('keydown', onKeyDown);
  }, [fixDialogOpen, retryExecutionId, revertRoundNumber]);

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
        setExtraInfo(loaded.description ?? '');
        setBackend(loaded.backend);
        setFrontend(loaded.frontend);
        setLanguageRuntime(loaded.languageRuntime ?? '');
        setBackendCustom(loaded.backendCustom ?? '');
        setFrontendCustom(loaded.frontendCustom ?? '');
        setStep(6);
      })
      .catch(() => {
        if (!cancelled) setError('That generation could not be loaded.');
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, searchParams]);

  // Prefer backend change events. A 3-second poll is used only when the
  // stream is unavailable, and is paused while the tab is hidden.
  useEffect(() => {
    if (!token || !generation || settled(generation) || !tabVisible) return;
    const id = generation.id;
    const controller = new AbortController();
    setSseConnected(false);
    void streamGenerationUpdates(
      apiClient(),
      token,
      id,
      controller.signal,
      () => {
        setSseConnected(true);
        getGeneration(apiClient(), token, id).then(setGeneration).catch(() => {
          setSseConnected(false);
        });
      },
      () => {
        if (document.visibilityState === 'hidden') return;
        getGeneration(apiClient(), token, id).then(setGeneration).catch(() => {
          /* Polling remains available if a streamed refresh misses a transient failure. */
        });
      },
    )
      .then(() => {
        if (!controller.signal.aborted) setSseConnected(false);
      })
      .catch(() => {
        if (!controller.signal.aborted) setSseConnected(false);
      });
    return () => controller.abort();
  }, [token, generation?.id, settled(generation), tabVisible]);

  useEffect(() => {
    if (!token || !generation || settled(generation) || sseConnected || !tabVisible) return;
    const id = generation.id;
    const poll = () => {
      if (document.visibilityState === 'hidden') return;
      getGeneration(apiClient(), token, id)
        .then(setGeneration)
        .catch(() => {
          /* a transient poll failure is not a run failure */
        });
    };
    const timer = window.setInterval(poll, POLL_MS);
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') poll();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [token, generation?.id, settled(generation), sseConnected, tabVisible]);

  // Step 3: ask the backend for questions derived from steps 1–2. The
  // frontend never invents a question or a default answer.
  useEffect(() => {
    if (!token || step !== 4) return;
    let cancelled = false;
    setQuestionsLoading(true);
    setQuestionsError(null);
    getGenerationQuestions(apiClient(), token, buildQuestionsInput())
      .then((response) => {
        if (!cancelled) setQuestions(response);
      })
      .catch((e) => {
        if (!cancelled) setQuestionsError(messageOf(e));
      })
      .finally(() => {
        if (!cancelled) setQuestionsLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, step, name, idea, extraInfo, backend, frontend, languageRuntime, backendCustom, frontendCustom]);

  // Step 4: plan built only from the explicit answers. A 400 names missing ids.
  useEffect(() => {
    if (!token || step !== 5) return;
    let cancelled = false;
    setPreviewLoading(true);
    setPreviewError(null);
    setPlanPreview(null);
    previewWizardPlan(apiClient(), token, {
      project: buildQuestionsInput(),
      answers: answersPayload(),
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, step, previewTick, name, idea, extraInfo, backend, frontend, languageRuntime, backendCustom, frontendCustom, answers, dbHost, dbPort, dbName, dbUser]);

  const loadFiles = useCallback(
    async (generationId: string) => {
      if (!token) return;
      try {
        const listed = await listGenerationFiles(apiClient(), token, generationId);
        setFiles(Array.isArray(listed) ? listed : []);
        setFilesError(null);
      } catch (e) {
        setFilesError(messageOf(e));
      }
      try {
        const listed = await listRestructures(apiClient(), token, generationId);
        setRestructures(Array.isArray(listed) ? listed : []);
      } catch {
        setRestructures([]);
      }
    },
    [token],
  );

  useEffect(() => {
    if (generation && token && tabVisible && generation.status !== 'DRAFT') {
      void loadFiles(generation.id);
    }
  }, [generation?.artifact?.id, generation?.id, generation?.updatedAt, generation?.status, token, loadFiles, tabVisible]);

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

  async function next() {
    setError(null);
    setWizardChangeNotice(null);
    if (step === 1) {
      if (!name.trim()) {
        setError('Project name is required.');
        document.getElementById('name')?.focus();
        return;
      }
      if (name.length > 60 || /[\r\n]/.test(name)) {
        setError('Project name must be 60 characters or fewer and stay on one line.');
        document.getElementById('name')?.focus();
        return;
      }
      if (idea.trim().length < MIN_IDEA) {
        setError(`Please describe the task in at least ${MIN_IDEA} characters.`);
        return;
      }
      setStep(2);
      return;
    }
    if (step === 2) {
      if (!generationEngine) {
        setError('Choose a generation engine first.');
        document.querySelector<HTMLElement>('[role="radio"]')?.focus();
        return;
      }
      setStep(3);
      return;
    }
    if (step === 3) {
      if (!backend || !frontend) {
        setError('Choose a backend and a frontend. Use None when one side does not apply.');
        document.getElementById(!frontend ? 'select-frontend' : 'select-backend')?.focus();
        return;
      }
      if (backend === 'NONE' && frontend === 'NONE') {
        setError('Choose at least a frontend or a backend.');
        return;
      }
      if (!languageRuntime.trim()) {
        setError('Language/runtime is required. Type the version you intend to use.');
        document.getElementById('language-runtime')?.focus();
        return;
      }
      if (backend === 'OTHER' && !backendCustom.trim()) {
        setError('Describe the custom backend.');
        return;
      }
      if (frontend === 'OTHER' && !frontendCustom.trim()) {
        setError('Describe the custom frontend.');
        return;
      }
      if (!token) return;
      try {
        await validateGenerationWizardStack(apiClient(), token, {
          engine: generationEngine,
          backend: backend as GenerationBackend,
          frontend: frontend as GenerationFrontend,
        });
      } catch {
        setError(generationEngine === 'TEMPLATE'
          ? 'This stack is not available for Template. Switch to the AI engine.'
          : 'This stack is not available for the selected generation engine.');
        return;
      }
      setStep(4);
      return;
    }
    if (step === 4) {
      if (questionsLoading) {
        setError('Questions are still loading. Please wait, then try again.');
        return;
      }
      if (questionsError) {
        setError('Questions could not be loaded. Retry the request before continuing.');
        return;
      }
      if (questions.length === 0) {
        setError('No questions are available yet. Retry loading the questions before continuing.');
        return;
      }
      const missing = questions.filter((question) => {
        const answer = answers[question.id];
        const blank = !answer || (!answer.skipped && !answer.value.trim());
        return question.required && (blank || (answer?.skipped && !question.allowSkip));
      });
      const blockingQuestionIds = missing
        .filter((question) => questions.some((item) => item.id === question.id))
        .map((question) => question.id);
      const engine = answers['generation.engine']?.value;
      if (engine && engine !== 'TEMPLATE') {
        if (!answers['generation.model']?.value.trim()) {
          missing.push({ ...questions[0], id: 'generation.model', prompt: 'Which generation model?' });
        }
        if (!answers['generation.apiKey']?.value.trim()) {
          missing.push({ ...questions[0], id: 'generation.apiKey', prompt: 'Generation engine API key' });
        }
        if (engine === 'CUSTOM' && !answers['generation.baseUrl']?.value.trim()) {
          missing.push({ ...questions[0], id: 'generation.baseUrl', prompt: 'Custom base URL' });
        }
      }
      if (missing.length > 0) {
        setQuestionErrors(blockingQuestionIds);
        questionControlRefs.current[blockingQuestionIds[0]]?.focus();
        setError(`Answer these questions first: ${missing.map((item) => item.prompt).join('; ')}`);
        return;
      }
      setQuestionErrors([]);
      const databaseType = answers['database.type'];
      const connection = answers['database.connection'];
      if (databaseType && !databaseType.skipped && databaseType.value !== 'NONE'
          && !connection?.skipped) {
        const port = Number(dbPort);
        if (!dbHost.trim() || !dbPort.trim() || !dbName.trim() || !dbUser.trim()
            || !Number.isInteger(port) || port < 1 || port > 65535) {
          setQuestionErrors((current) => current.includes('database.connection')
            ? current
            : [...current, 'database.connection']);
          setError(
            'Enter database host, port, name and username, or choose Ask me later for the connection.',
          );
          return;
        }
      }
      setStep(5);
      setPreviewTick((value) => value + 1);
    }
  }

  function chooseEngine(engine: 'TEMPLATE' | 'AI') {
    setGenerationEngine(engine);
    setWizardChangeNotice(null);
    const supported = (wizardCatalog ?? ENGINE_FALLBACK).engines.find((item) => item.id === engine);
    if (backend && frontend && supported
        && (!supported.supportedBackends.includes(backend)
          || !supported.supportedFrontends.includes(frontend))) {
      setBackend('');
      setFrontend('');
      setBackendCustom('');
      setFrontendCustom('');
      setWizardChangeNotice(
        'The engine changed, so your previous backend and frontend were cleared because this engine does not support them.',
      );
    }
    if (engine === 'TEMPLATE') {
      setAnswers((current) => {
        const nextAnswers = { ...current };
        for (const id of ['generation.model', 'generation.apiKey', 'generation.baseUrl']) {
          delete nextAnswers[id];
        }
        return nextAnswers;
      });
    }
  }

  function supportsStack(value: GenerationBackend | GenerationFrontend, kind: 'backend' | 'frontend') {
    if (!generationEngine) return false;
    const engine = (wizardCatalog ?? ENGINE_FALLBACK).engines.find((item) => item.id === generationEngine);
    return kind === 'backend'
      ? Boolean(engine?.supportedBackends.includes(value as GenerationBackend))
      : Boolean(engine?.supportedFrontends.includes(value as GenerationFrontend));
  }

  function setAnswerValue(questionId: string, value: string) {
    setAnswers((current) => ({ ...current, [questionId]: { value, skipped: false } }));
    setQuestionErrors((current) => current.filter((id) => id !== questionId));
  }

  function skipAnswer(questionId: string) {
    const question = questions.find((item) => item.id === questionId);
    if (!question?.allowSkip) {
      setQuestionErrors((current) => current.includes(questionId) ? current : [...current, questionId]);
      return;
    }
    setAnswers((current) => ({ ...current, [questionId]: { value: '', skipped: true } }));
    setQuestionErrors((current) => current.filter((id) => id !== questionId));
  }

  function answersPayload(): GenerationAnswer[] {
    const engine = answers['generation.engine']?.value;
    return questions
      .filter((question) => !(
        engine === 'TEMPLATE'
        && (question.id === 'generation.model'
          || question.id === 'generation.apiKey'
          || question.id === 'generation.baseUrl')
      ))
      .map((question) => {
        if (question.id === 'database.connection') {
          const connection = answers[question.id];
          const text = databaseConnectionText();
          return {
            questionId: question.id,
            value: text,
            skipped: Boolean(connection?.skipped),
          };
        }
        const answer = answers[question.id];
        return {
          questionId: question.id,
          value: answer?.value ?? '',
          skipped: Boolean(answer?.skipped),
        };
      });
  }

  function databaseConnectionText(): string {
    const parts = [dbHost, dbPort, dbName, dbUser].map((part) => part.trim());
    if (parts.every((part) => !part)) {
      return '';
    }
    return `${parts[0]}:${parts[1]}/${parts[2]} user=${parts[3]}`;
  }

  function resolvedDatabase(): {
    database: GenerationDatabase;
    later: boolean;
    config?: CreateGenerationInput['databaseConfig'];
  } {
    const type = answers['database.type'];
    const connection = answers['database.connection'];
    if (!type || type.skipped) {
      return { database: 'NONE', later: true };
    }
    if (type.value === 'NONE') {
      return { database: 'NONE', later: false };
    }
    if (connection?.skipped || !databaseConnectionText()) {
      return { database: 'NONE', later: true };
    }
    return {
      database: type.value as GenerationDatabase,
      later: false,
      config: {
        host: dbHost.trim(),
        port: Number(dbPort),
        name: dbName.trim(),
        username: dbUser.trim(),
        password: dbPassword,
        sslMode: 'prefer',
      },
    };
  }

  function resolvedAuth(): GenerationAuth {
    const auth = answers['auth.method'];
    if (!auth || auth.skipped || !auth.value || auth.value === 'NONE') {
      return 'NONE';
    }
    return auth.value as GenerationAuth;
  }

  function resolvedIntegrations(): GenerationIntegration[] {
    const raw = answers.integrations?.value ?? '';
    return raw
      .split(',')
      .map((part) => part.trim().toUpperCase())
      .filter((part): part is GenerationIntegration =>
        INTEGRATION_VALUES.includes(part as GenerationIntegration));
  }

  function resolvedAiConfig(): CreateGenerationInput['aiConfig'] {
    const engine = answers['generation.engine']?.value;
    if (!engine || engine === 'TEMPLATE') {
      return { provider: 'NONE', model: 'template' };
    }
    return {
      provider: engine === 'CUSTOM' ? 'CUSTOM' : 'OPENROUTER',
      model: answers['generation.model']?.value.trim() ?? '',
      apiKey: answers['generation.apiKey']?.value ?? '',
      ...(engine === 'CUSTOM'
        ? { baseUrl: answers['generation.baseUrl']?.value.trim() ?? '' }
        : {}),
    };
  }

  function buildQuestionsInput(): GenerationQuestionsInput {
    return {
      name: name.trim(),
      requirement: idea.trim(),
      description: extraInfo.trim() || undefined,
      backend: backend as GenerationBackend,
      frontend: frontend as GenerationFrontend,
      languageRuntime: languageRuntime.trim() || undefined,
      backendCustom: backend === 'OTHER' ? backendCustom.trim() : undefined,
      frontendCustom: frontend === 'OTHER' ? frontendCustom.trim() : undefined,
    };
  }

  function buildInput(): CreateGenerationInput {
    const db = resolvedDatabase();
    return {
      name: name.trim(),
      requirement: idea.trim(),
      description: extraInfo.trim() || undefined,
      backend: backend as GenerationBackend,
      frontend: frontend as GenerationFrontend,
      database: db.database,
      auth: resolvedAuth(),
      integrations: resolvedIntegrations(),
      languageRuntime: languageRuntime.trim(),
      backendCustom: backend === 'OTHER' ? backendCustom.trim() : undefined,
      frontendCustom: frontend === 'OTHER' ? frontendCustom.trim() : undefined,
      answers: answersPayload(),
      databaseConfigureLater: db.later,
      databaseConfig: db.config,
      aiConfig: resolvedAiConfig(),
      approvedPlan: planPreview ?? undefined,
      draft: false,
    };
  }

  async function generate() {
    if (!token || creating) return;
    if (!planPreview) {
      setError('Review the plan before approving generation.');
      return;
    }
    if (!backend || !frontend) {
      setError('Choose a backend and a frontend.');
      return;
    }
    setCreating(true);
    setError(null);
    try {
      const created = await createGeneration(apiClient(), token, buildInput());
      setGeneration(created);
      setStep(6);
      // Secrets were sent once. Drop them from component state after dispatch.
      setAnswers((current) => {
        const next = { ...current };
        for (const [id, answer] of Object.entries(next)) {
          if (id === 'generation.apiKey') {
            next[id] = { ...answer, value: '' };
          }
        }
        return next;
      });
      setDbPassword('');
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

  async function openFixDialog() {
    if (!token || !generation || selectedFindingIds.length === 0 || fixBusy) return;
    setFixBusy(true);
    setFixError(null);
    try {
      const preview = await previewGenerationFix(
        apiClient(), token, generation.id, selectedFindingIds,
      );
      setFixPreview(preview);
      setFixDialogOpen(true);
    } catch (e) {
      setFixError(messageOf(e));
    } finally {
      setFixBusy(false);
    }
  }

  async function approveFixes() {
    if (!token || !generation || !fixPreview || fixBusy) return;
    setFixBusy(true);
    setFixError(null);
    try {
      const updated = await approveGenerationFix(
        apiClient(), token, generation.id, selectedFindingIds, fixApiKey,
      );
      setGeneration(updated);
      setFixDialogOpen(false);
      setFixApiKey('');
      setFixPreview(null);
    } catch (e) {
      setFixError(messageOf(e));
    } finally {
      setFixBusy(false);
    }
  }

  async function revertFixRound(round: number) {
    if (!token || !generation || fixBusy) return;
    setFixBusy(true);
    setFixError(null);
    try {
      const updated = await revertGenerationFixRound(apiClient(), token, generation.id, round);
      setGeneration(updated);
      setSelectedFile(null);
      await loadFiles(generation.id);
    } catch (e) {
      setFixError(messageOf(e));
    } finally {
      setFixBusy(false);
    }
  }

  async function retryStage() {
    if (!token || !generation || !retryExecutionId || retryBusy) return;
    setRetryBusy(true);
    setRetryError(null);
    try {
      const updated = await retryGenerationStage(
        apiClient(), token, generation.id, retryExecutionId, retryApiKey,
      );
      setGeneration(updated);
      setRetryExecutionId(null);
      setRetryApiKey('');
    } catch (e) {
      setRetryError(messageOf(e));
    } finally {
      setRetryBusy(false);
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
    setName('');
    setIdea('');
    setExtraInfo('');
    setBackend('');
    setFrontend('');
    setLanguageRuntime('');
    setBackendCustom('');
    setFrontendCustom('');
    setAnswers({});
    setQuestions([]);
    setPlanPreview(null);
    setDbHost('');
    setDbPort('');
    setDbName('');
    setDbUser('');
    setDbPassword('');
    setDraftCandidate(null);
    setDraftResolved(true);
    if (searchParams.has('genId')) {
      const next = new URLSearchParams(searchParams);
      next.delete('genId');
      setSearchParams(next, { replace: true });
    }
  }

  function applyDraft(draft: GenerateDraft): void {
    setName(draft.name);
    setIdea(draft.idea);
    setExtraInfo(draft.extraInfo);
    setGenerationEngine(draft.generationEngine);
    setBackend(draft.backend as GenerationBackend | '');
    setFrontend(draft.frontend as GenerationFrontend | '');
    setLanguageRuntime(draft.languageRuntime);
    setBackendCustom(draft.backendCustom);
    setFrontendCustom(draft.frontendCustom);
    setAnswers(draft.answers);
    setDraftCandidate(null);
    setDraftResolved(true);
  }

  function startOver(): void {
    if (!window.confirm('Start over and clear this saved wizard draft?')) return;
    clearGenerateDrafts();
    skipNextDraftSave.current = true;
    reset();
  }

  const stages = pipelineStages(generation);
  const latestProposal = restructures[0];
  const applied = restructures.find((item) => item.status === 'APPLIED');

  return (
    <FeaturePage
      title="Generate"
      description="Choose the project and stack, answer the backend's questions, review the plan, then approve generation."
      steps={STEPS}
      current={step >= 6 ? STEPS.length - 1 : step - 1}
      actions={
        <div className="flex flex-wrap justify-end gap-2">
          {step <= 5 && (
            <button type="button" className={secondaryButtonClass} onClick={startOver}>
              Start over
            </button>
          )}
          <Link to="/projects" className={secondaryButtonClass}>
            Projects
          </Link>
        </div>
      }
    >
      {error && (
        <div>
          <ErrorAlert
            message={error}
            onRetry={step === 3 ? () => void next() : undefined}
          />
          {error.includes('Switch to the AI engine') && (
            <button type="button" className={`${secondaryButtonClass} mt-2`}
              onClick={() => { chooseEngine('AI'); setError(null); }}>
              Switch to the AI engine
            </button>
          )}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="min-w-0 transition-opacity duration-200 motion-reduce:transition-none">
      {draftCandidate && step === 1 && (
        <Panel className="mb-5 space-y-3 border-indigo-200 bg-indigo-50">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">Continue where you left off?</h2>
            <p className="mt-1 text-sm text-slate-700">
              A saved wizard draft is available for this account.
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <button type="button" className={primaryButtonClass} onClick={() => applyDraft(draftCandidate)}>
              Continue where you left off
            </button>
            <button type="button" className={secondaryButtonClass} onClick={startOver}>
              Start fresh
            </button>
          </div>
        </Panel>
      )}
      {step === 1 && (
        <Panel className="space-y-5">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">Project</h2>
            <p className="mt-1 text-sm text-slate-600">
              Tell us what to build. Every field is yours to fill; nothing is inferred.
            </p>
          </div>
          <div>
            <label htmlFor="name" className="mb-1 block text-sm font-medium text-slate-700">
              Project name <span className="text-red-600">*</span>
            </label>
            <input
              id="name"
              className={inputClass}
              aria-invalid={name.length > 60 || /[\r\n]/.test(name)}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="task-tracker"
              required
            />
            {(name.length > 60 || /[\r\n]/.test(name)) && (
              <p className="mt-1 text-sm text-red-700">
                Project name must be 60 characters or fewer and stay on one line.
              </p>
            )}
          </div>
          <div>
            <label htmlFor="idea" className="mb-1 block text-sm font-medium text-slate-700">
              Task description <span className="text-red-600">*</span>
            </label>
            <textarea
              id="idea"
              className={`${textareaClass} min-h-32`}
              value={idea}
              onChange={(event) => setIdea(event.target.value)}
              placeholder="A task tracker where teams create projects, assign tasks, and comment."
              required
            />
            <p className="mt-1 text-xs text-slate-500">
              {idea.trim().length}/{MIN_IDEA} characters minimum
            </p>
          </div>
          <div>
            <label htmlFor="extra-info" className="mb-1 block text-sm font-medium text-slate-700">
              Extra information (optional)
            </label>
            <textarea
              id="extra-info"
              className={`${textareaClass} min-h-24`}
              value={extraInfo}
              onChange={(event) => setExtraInfo(event.target.value)}
              placeholder="Constraints, must-have screens, data you already have, or anything the plan should respect."
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
            <h2 className="text-lg font-semibold text-slate-900">Generation engine</h2>
            <p className="mt-1 text-sm text-slate-600">
              Choose the engine first. Your choice controls which stack options are available.
            </p>
          </div>
          {catalogLoading && <LoadingState label="Loading generation options…" />}
          {catalogError && <ErrorAlert message={catalogError} />}
          <div className="grid gap-4 sm:grid-cols-2" role="radiogroup" aria-label="Generation engine">
            {(wizardCatalog ?? ENGINE_FALLBACK).engines.map((engine) => (
              <button
                key={engine.id}
                type="button"
                role="radio"
                aria-checked={generationEngine === engine.id}
                className={`rounded-xl border p-4 text-left transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-600 ${
                  generationEngine === engine.id
                    ? 'border-indigo-600 bg-indigo-50 ring-2 ring-indigo-200'
                    : 'border-slate-200 bg-white hover:border-indigo-300'
                }`}
                onClick={() => chooseEngine(engine.id)}
              >
                <span className="block font-semibold text-slate-900">{engine.label}</span>
                <span className="mt-1 block text-sm text-slate-600">{engine.description}</span>
              </button>
            ))}
          </div>
          {wizardChangeNotice && (
            <p role="status" className="rounded-lg bg-amber-50 p-3 text-sm text-amber-800">
              {wizardChangeNotice}
            </p>
          )}
          <div className="flex justify-between">
            <button type="button" className={secondaryButtonClass} onClick={() => setStep(1)}>Back</button>
            <button type="button" className={primaryButtonClass} onClick={next}
            >Choose stack</button>
          </div>
        </Panel>
      )}

      {step === 3 && (
        <Panel className="space-y-5">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">Stack</h2>
            <p className="mt-1 text-sm text-slate-600">
              Choose each part explicitly. Database, auth, AI, deployment, and
              integrations are asked on the next step.
            </p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Select label="Frontend" value={frontend} onChange={(value) => setFrontend(value as GenerationFrontend)}>
              <option value="">Select frontend…</option>
              {Object.entries(FRONTEND_LABELS).map(([value, label]) => (
                <option key={value} value={value} disabled={!supportsStack(value as GenerationFrontend, 'frontend')}>
                  {label}{!supportsStack(value as GenerationFrontend, 'frontend') ? ' — unavailable for this engine' : ''}
                </option>
              ))}
            </Select>
            <Select label="Backend" value={backend} onChange={(value) => setBackend(value as GenerationBackend)}>
              <option value="">Select backend…</option>
              {Object.entries(BACKEND_LABELS).map(([value, label]) => (
                <option key={value} value={value} disabled={!supportsStack(value as GenerationBackend, 'backend')}>
                  {label}{!supportsStack(value as GenerationBackend, 'backend') ? ' — unavailable for this engine' : ''}
                </option>
              ))}
            </Select>
            {frontend === 'OTHER' && (
              <div>
                <label htmlFor="frontend-custom" className="mb-1 block text-sm font-medium text-slate-700">
                  Custom frontend <span className="text-red-600">*</span>
                </label>
                <input
                  id="frontend-custom"
                  className={inputClass}
                  value={frontendCustom}
                  onChange={(event) => setFrontendCustom(event.target.value)}
                  placeholder="e.g. SvelteKit"
                />
              </div>
            )}
            {backend === 'OTHER' && (
              <div>
                <label htmlFor="backend-custom" className="mb-1 block text-sm font-medium text-slate-700">
                  Custom backend <span className="text-red-600">*</span>
                </label>
                <input
                  id="backend-custom"
                  className={inputClass}
                  value={backendCustom}
                  onChange={(event) => setBackendCustom(event.target.value)}
                  placeholder="e.g. Go + Fiber"
                />
              </div>
            )}
            <div className="sm:col-span-2">
              <label htmlFor="language-runtime" className="mb-1 block text-sm font-medium text-slate-700">
                Language / runtime <span className="text-red-600">*</span>
              </label>
              <input
                id="language-runtime"
                className={inputClass}
                value={languageRuntime}
                onChange={(event) => setLanguageRuntime(event.target.value)}
                placeholder="e.g. Java 21, Node 20, Python 3.12"
                required
              />
            </div>
          </div>
          <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="font-semibold text-slate-900">Need ideas?</span>
              <button
                type="button"
                className={secondaryButtonClass}
                onClick={() => setStackSuggestionsOpen((open) => !open)}
              >
                {stackSuggestionsOpen ? 'Hide suggestions' : 'Suggest options'}
              </button>
            </div>
            {stackSuggestionsOpen && (
              <p className="mt-2 text-xs text-slate-600">
                Suggestions only — nothing is applied automatically. Common
                starting points: Spring Boot + React, FastAPI + Next.js,
                Node/Express + Vue. Pick one above or type your own.
              </p>
            )}
          </div>
          {backend === 'NONE' && frontend === 'NONE' && (
            <p className="text-sm text-amber-700">Pick at least a frontend or a backend.</p>
          )}
          <div className="flex justify-between">
            <button type="button" className={secondaryButtonClass} onClick={() => setStep(2)}>Back</button>
            <button type="button" className={primaryButtonClass} onClick={next}>Answer questions</button>
          </div>
        </Panel>
      )}

      {step === 4 && (
        <div className="space-y-5">
          <Panel className="space-y-3">
            <h2 className="text-lg font-semibold text-slate-900">Questions</h2>
            <p className="text-sm text-slate-600">
              These questions come from the backend based on your stack. There
              are no preselected answers. Required questions block the plan;
              optional questions can be skipped explicitly.
            </p>
            {questionsLoading && <LoadingState label="Preparing questions…" />}
            {questionsError && <ErrorAlert message={questionsError} />}
            {questionErrors.length > 0 && (
              <p role="status" aria-live="assertive" className="text-sm font-semibold text-red-700">
                {questionErrors.length} question{questionErrors.length === 1 ? '' : 's'} need
                {questionErrors.length === 1 ? 's' : ''} an answer
              </p>
            )}
          </Panel>

          {!questionsLoading && !questionsError && questions.map((question) => {
            const answer = answers[question.id];
            const skipped = Boolean(answer?.skipped);
            const engine = answers['generation.engine']?.value;
            const hiddenByTemplate = engine === 'TEMPLATE'
              && (question.id === 'generation.model'
                || question.id === 'generation.apiKey'
                || question.id === 'generation.baseUrl');
            if (hiddenByTemplate) {
              return null;
            }
            const dbType = answers['database.type']?.value;
            const dbQuestionDisabled = question.id === 'database.connection'
              && (!dbType || dbType === 'NONE'
                || answers['database.type']?.skipped || skipped);
            return (
              <Panel key={question.id} className="space-y-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div>
                    <h3 className="text-sm font-semibold text-slate-900">
                      {question.prompt}{' '}
                      {question.required
                        ? <span className="text-red-600">*</span>
                        : <span className="text-xs font-normal text-slate-400">(optional)</span>}
                    </h3>
                    <p className="mt-1 text-xs text-slate-500">{question.help}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    {question.secret && <Badge tone="amber">secret — sent once</Badge>}
                    {question.allowSkip && (
                      <button
                        type="button"
                        className={secondaryButtonClass}
                        onClick={() => skipAnswer(question.id)}
                        aria-pressed={skipped}
                      >
                        {skipped ? 'Skipped — change' : 'Ask me later'}
                      </button>
                    )}
                  </div>
                </div>

                {question.id === 'database.connection' ? (
                  <div className="grid gap-3 sm:grid-cols-2">
                    {dbQuestionDisabled ? (
                      <p className="text-sm text-slate-500 sm:col-span-2">
                        {skipped
                          ? 'You chose Ask me later. No connection details will be stored.'
                          : 'No connection details needed because no database is selected.'}
                      </p>
                    ) : (
                      <>
                        <Field label="DB host" htmlFor="db-host" value={dbHost} onChange={setDbHost} />
                        <Field label="DB port" htmlFor="db-port" value={dbPort} onChange={setDbPort} />
                        <Field label="DB name" htmlFor="db-name" value={dbName} onChange={setDbName} />
                        <Field label="DB user" htmlFor="db-user" value={dbUser} onChange={setDbUser} />
                        <div className="sm:col-span-2">
                          <label htmlFor="db-password" className="mb-1 block text-sm font-medium text-slate-700">
                            DB password
                          </label>
                          <input
                            id="db-password"
                            type="password"
                            autoComplete="off"
                            className={inputClass}
                            value={dbPassword}
                            onChange={(event) => setDbPassword(event.target.value)}
                            placeholder="Optional; sent once and never stored"
                          />
                        </div>
                        <p className="text-xs text-slate-500 sm:col-span-2">
                          Fill host, port, name and username, or choose Ask me later
                          to leave the connection unconfigured.
                        </p>
                      </>
                    )}
                  </div>
                ) : question.type === 'CHOICE' ? (
                  <select
                    aria-label={question.prompt}
                    className={selectClass}
                    ref={(element) => { questionControlRefs.current[question.id] = element; }}
                    aria-invalid={questionErrors.includes(question.id)}
                    value={skipped ? '' : answer?.value ?? ''}
                    onChange={(event) => setAnswerValue(question.id, event.target.value)}
                    disabled={skipped}
                  >
                    <option value="">Select an answer…</option>
                    {question.options.map((option) => (
                      <option key={option.value} value={option.value}>{option.label}</option>
                    ))}
                  </select>
                ) : question.type === 'YES_NO' ? (
                  <div className="flex gap-2">
                    {['YES', 'NO'].map((value) => (
                      <button
                        key={value}
                        type="button"
                        ref={value === 'YES'
                          ? (element) => { questionControlRefs.current[question.id] = element; }
                          : undefined}
                        aria-pressed={!skipped && answer?.value === value}
                        aria-invalid={questionErrors.includes(question.id)}
                        className={!skipped && answer?.value === value
                          ? primaryButtonClass : secondaryButtonClass}
                        onClick={() => setAnswerValue(question.id, value)}
                      >
                        {value === 'YES' ? 'Yes' : 'No'}
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="space-y-1">
                    <input
                      aria-label={question.prompt}
                      type={question.secret ? 'password' : 'text'}
                      autoComplete="off"
                      className={inputClass}
                      ref={(element) => { questionControlRefs.current[question.id] = element; }}
                      aria-invalid={questionErrors.includes(question.id)}
                      value={skipped ? '' : answer?.value ?? ''}
                      onChange={(event) => setAnswerValue(question.id, event.target.value)}
                      disabled={skipped}
                      placeholder={question.secret
                        ? 'Sent once, held in memory, never stored'
                        : 'Type your answer'}
                    />
                    {question.id === 'integrations' && (
                      <p className="text-xs text-slate-500">
                        Known values: {INTEGRATION_VALUES.join(', ')}. Suggestions
                        only; type the ones you want or skip this question.
                      </p>
                    )}
                  </div>
                )}
                {questionErrors.includes(question.id) && (
                  <p className="text-sm font-medium text-red-700">
                    This question is required. Choose an answer or pick 'Ask me later'.
                    {!question.allowSkip && ' Ask me later is not available for this question.'}
                  </p>
                )}
                {skipped && (
                  <p className="text-xs font-medium text-amber-700">
                    You chose “ask me later”. The plan will say this is undecided;
                    it will not invent a value.
                  </p>
                )}
              </Panel>
            );
          })}

          <div className="flex justify-between">
            <button type="button" className={secondaryButtonClass} onClick={() => setStep(3)}>Back</button>
            <button
              type="button"
              className={primaryButtonClass}
              onClick={next}
            >
              Review plan
            </button>
          </div>
        </div>
      )}

      {step === 5 && (
        <div className="space-y-6">
          <Panel className="space-y-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2 className="text-lg font-semibold text-slate-900">Review the plan</h2>
                <p className="mt-1 text-sm text-slate-600">
                  This plan is built only from your answers. Nothing is generated
                  until you press Approve and generate.
                </p>
              </div>
              <button type="button" className={secondaryButtonClass} onClick={() => setPreviewTick((value) => value + 1)}>
                Re-plan
              </button>
            </div>

            {previewLoading && <LoadingState label="Building the plan…" />}
            {previewError && (
              <ErrorAlert
                message={previewError}
                onRetry={() => setPreviewTick((value) => value + 1)}
              />
            )}

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
                      {planPreview.files.map((file, index) => (
                        <li key={`${index}-${file.path}`} className="grid gap-2 border-b border-slate-100 py-2 last:border-0">
                          <label className="sr-only" htmlFor={`plan-file-path-${index}`}>Planned file path {index + 1}</label>
                          <input
                            id={`plan-file-path-${index}`}
                            className={inputClass}
                            aria-label={`Planned file path ${index + 1}`}
                            value={file.path}
                            onChange={(event) => setPlanPreview((current) => current
                              ? { ...current, files: current.files.map((item, itemIndex) =>
                                  itemIndex === index ? { ...item, path: event.target.value } : item) }
                              : current)}
                          />
                          <label className="sr-only" htmlFor={`plan-file-purpose-${index}`}>Purpose for planned file {index + 1}</label>
                          <input
                            id={`plan-file-purpose-${index}`}
                            className={inputClass}
                            aria-label={`Purpose for planned file ${index + 1}`}
                            value={file.purpose}
                            onChange={(event) => setPlanPreview((current) => current
                              ? { ...current, files: current.files.map((item, itemIndex) =>
                                  itemIndex === index ? { ...item, purpose: event.target.value } : item) }
                              : current)}
                          />
                          <button
                            type="button"
                            className="w-fit text-xs font-medium text-red-700 underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2"
                            aria-label={`Remove planned file ${file.path}`}
                            onClick={() => setPlanPreview((current) => current
                              ? { ...current, files: current.files.filter((_, itemIndex) => itemIndex !== index) }
                              : current)}
                          >
                            Remove file
                          </button>
                        </li>
                      ))}
                    </ul>
                    <button
                      type="button"
                      className={`${secondaryButtonClass} mt-2`}
                      onClick={() => setPlanPreview((current) => current
                        ? { ...current, files: [...current.files, { path: '', purpose: '' }] }
                        : current)}
                    >
                      Add planned file
                    </button>
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
                  <button type="button" className={secondaryButtonClass} onClick={() => setStep(3)}>
                    Edit stack
                  </button>
                  <button type="button" className={secondaryButtonClass} onClick={() => setStep(4)}>
                    Edit answers
                  </button>
                </div>
              </div>
            )}
          </Panel>

          {questions.length > 0 && (
            <Panel className="space-y-3">
              <h2 className="text-lg font-semibold text-slate-900">Your answers</h2>
              <ul className="space-y-1 text-sm text-slate-600">
                {questions.map((question) => {
                  const answer = answers[question.id];
                  if (!answer) {
                    return null;
                  }
                  return (
                    <li key={question.id} className="flex flex-wrap justify-between gap-2 border-b border-slate-100 pb-1">
                      <span>{question.prompt}</span>
                      <span className="font-medium text-slate-900">
                        {question.secret
                          ? (answer.value ? 'configured (not stored)' : 'not provided')
                          : answer.skipped ? 'Ask me later' : (answer.value || 'not provided')}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </Panel>
          )}

          <Panel className="space-y-3">
            <p className="text-sm text-slate-600">
              Approval starts the real generation pipeline: plan, code, sandbox
              build/test, verdict, and review. You can still send the result to
              Review afterwards.
            </p>
            <div className="flex flex-wrap justify-between gap-3">
              <button type="button" className={secondaryButtonClass} onClick={() => setStep(4)}>Back</button>
              <button
                type="button"
                className={primaryButtonClass}
                onClick={() => void generate()}
                disabled={creating}
              >
                {creating ? 'Starting…' : 'Approve and generate'}
              </button>
            </div>
          </Panel>
        </div>
      )}

      {step === 6 && (
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
                const Icon = stageIcon(stage.state);
                return (
                  <li key={stage.key} className={`rounded-xl border p-3 transition-all duration-200 motion-reduce:transition-none ${
                    stage.state === 'running' ? 'border-indigo-300 bg-indigo-50/40 shadow-sm' : 'border-slate-200'
                  }`}>
                    <div className="flex items-center justify-between gap-2">
                      <span className="flex items-center gap-2 text-sm font-semibold text-slate-900">
                        <Icon className={`h-4 w-4 ${stage.state === 'running' ? 'animate-spin' : ''}`} aria-hidden="true" />
                        {index + 1}. {stage.label}
                        {stage.key === 'verify' && <span className="sr-only">3. Verifying</span>}
                      </span>
                      <Badge tone={display.tone}>
                        {display.label}
                        {stage.key === 'verify' && stage.state === 'skipped' && (
                          <span className="sr-only">Not verified</span>
                        )}
                      </Badge>
                    </div>
                    <p className="mt-1 text-[11px] text-slate-500">Round {stage.roundNumber}</p>
                    <p className="mt-2 text-xs font-medium text-slate-700">{stage.role}</p>
                    <p className="mt-2 text-xs leading-5 text-slate-600">{stage.detail}</p>
                    <p className="mt-2 flex items-center gap-1 text-[11px] text-slate-500">
                      <Clock3 className="h-3 w-3" aria-hidden="true" />
                      {elapsedLabel(stage.startedAt, stage.finishedAt, clock)}
                    </p>
                    {stage.activityLog && (
                      <ul className="mt-2 max-h-20 space-y-1 overflow-auto rounded-lg bg-slate-50 p-2 text-[11px] text-slate-600" aria-label={`${stage.label} activity log`}>
                        {stage.activityLog.split(/\r?\n/).filter(Boolean).map((activity, activityIndex) => (
                          <li key={`${stage.key}-activity-${activityIndex}`} className="break-words">
                            <span className="mr-1 text-indigo-400">›</span>{activity}
                          </li>
                        ))}
                      </ul>
                    )}
                    {stage.state === 'failed' && stage.executionId && (
                      <button
                        type="button"
                        className={`${secondaryButtonClass} mt-3 w-full`}
                        onClick={() => {
                          setRetryExecutionId(stage.executionId);
                          setRetryError(null);
                        }}
                      >
                        Retry {stage.label}
                      </button>
                    )}
                  </li>
                );
              })}
            </ol>

            {generation?.verificationLevel === 'SANDBOX_UNAVAILABLE' && (
              <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
                <strong>Build and tests not run: sandbox unavailable.</strong>
                <span className="ml-1">Static checks and review are reported separately; this is not build verification.</span>
              </div>
            )}
            {generation?.verificationLevel === 'STATIC' && (
              <p className="text-sm font-medium text-indigo-800">Verified (static)</p>
            )}
            {generation?.verificationLevel === 'BUILD_AND_TESTS' && (
              <p className="text-sm font-medium text-green-800">Verified (build and tests)</p>
            )}
            {generation?.verificationLevel === 'ISSUES_FOUND' && (
              <p className="text-sm font-medium text-amber-800">Checks found unresolved issues.</p>
            )}
            {generation?.status === 'FAILED' && (
              <ErrorAlert message={generation.error ?? 'Generation failed.'} />
            )}
            {generation && !settled(generation) && (
              <p className="text-xs text-slate-500">
                {sseConnected
                  ? 'Receiving live updates.'
                  : `Checking for updates every ${POLL_MS / 1000}s when this tab is visible.`}
              </p>
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
                title={generation?.downloadReady
                  ? 'Download the generated ZIP'
                  : 'Available after all findings are resolved and review completes'}
              >
                {downloading ? 'Preparing…' : 'Download ZIP'}
              </button>
              <span className="basis-full text-xs text-slate-500">
                Verification: {generation?.verificationLevel === 'STATIC'
                  ? 'Verified (static)'
                  : generation?.verificationLevel === 'BUILD_AND_TESTS'
                    ? 'Verified (build and tests)'
                    : generation?.verificationLevel === 'SANDBOX_UNAVAILABLE'
                      ? 'Build and tests not run: sandbox unavailable'
                      : 'Not verified'}
              </span>
              <button type="button" className={secondaryButtonClass} onClick={reset}>Start another</button>
            </div>
          </Panel>

          {generation?.rating && (
            <Panel>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h2 className="text-lg font-semibold text-slate-900">Rating</h2>
                  <p className="mt-1 text-sm text-slate-600">Based on the findings in the latest review.</p>
                </div>
                <Badge tone={generation.rating.overall >= 80 ? 'green' : 'amber'}>
                  {generation.rating.overall}/100
                </Badge>
              </div>
              <dl className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
                {([
                  ['Syntax', generation.rating.syntax],
                  ['Logic', generation.rating.logic],
                  ['Security', generation.rating.security],
                  ['Style', generation.rating.style],
                ] as const).map(([category, score]) => (
                  <div key={category} className="rounded-xl bg-slate-50 p-3">
                    <dt className="text-xs text-slate-500">{category}</dt>
                    <dd className="mt-1 text-lg font-semibold text-slate-900">{score}/100</dd>
                  </div>
                ))}
              </dl>
            </Panel>
          )}

          {generation && (generation.findings?.length ?? 0) > 0 && (
            <Panel className="space-y-3">
              <div>
                <h2 className="text-lg font-semibold text-slate-900">Findings</h2>
                <p className="mt-1 text-sm text-slate-600">
                  {generation.findings?.length ?? 0} issue{generation.findings?.length === 1 ? '' : 's'} from the latest review.
                </p>
              </div>
              {generation.status === 'AWAITING_FIX_APPROVAL' &&
                unresolvedFindingIds.length > 0 &&
                generation.iteration < generation.maxIterations && (
                <div className="flex flex-wrap items-center gap-3">
                  <button
                    type="button"
                    className={secondaryButtonClass}
                    onClick={() => setSelectedFindingIds(
                      selectedFindingIds.length === unresolvedFindingIds.length
                        ? []
                        : unresolvedFindingIds,
                    )}
                  >
                    {selectedFindingIds.length === unresolvedFindingIds.length
                      ? 'Deselect all'
                      : 'Select all'}
                  </button>
                  <button
                    type="button"
                    className={primaryButtonClass}
                    onClick={() => void openFixDialog()}
                    disabled={fixBusy || selectedFindingIds.length === 0}
                  >
                    {fixBusy ? 'Preparing…' : `Fix selected (${selectedFindingIds.length})`}
                  </button>
                </div>
              )}
              {generation.status === 'AWAITING_FIX_APPROVAL' &&
                unresolvedFindingIds.length > 0 &&
                generation.iteration >= generation.maxIterations && (
                <div className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
                  <strong>Fix limit reached.</strong>{' '}
                  These {unresolvedFindingIds.length} finding{unresolvedFindingIds.length === 1 ? '' : 's'} remain and cannot be fixed in another round.
                </div>
              )}
              {fixError && <ErrorAlert message={fixError} />}
              {findingsByFile(generation.findings ?? []).map(([file, findingsForFile]) => (
                <section key={file} className="rounded-xl border border-slate-200 p-3">
                  <h3 className="break-all font-mono text-xs font-semibold text-slate-800">{file}</h3>
                  <ul className="mt-2 space-y-2">
                    {findingsForFile.map((finding) => (
                      <li
                        key={finding.id || `${file}:${finding.line ?? 'unknown'}:${finding.title}`}
                        className="rounded-lg bg-slate-50 p-3"
                      >
                        <div className="flex flex-wrap items-center gap-2">
                          {['OPEN', 'FIX_REQUESTED', 'FIX_PROPOSED'].includes(finding.status ?? 'OPEN')
                            && generation.status === 'AWAITING_FIX_APPROVAL'
                            && generation.iteration < generation.maxIterations && (
                            <input
                              type="checkbox"
                              aria-label={`Select finding: ${finding.title}`}
                              checked={selectedFindingIds.includes(finding.id)}
                              onChange={(event) => setSelectedFindingIds((current) =>
                                event.target.checked
                                  ? [...current, finding.id]
                                  : current.filter((id) => id !== finding.id))}
                              className="size-4 rounded border-slate-300 text-indigo-600 focus:ring-indigo-600"
                            />
                          )}
                          <Badge tone={finding.severity === 'CRITICAL' || finding.severity === 'HIGH' ? 'red' : 'amber'}>
                            {finding.severity}
                          </Badge>
                          <Badge tone={finding.status === 'VERIFIED_FIXED' ? 'green' : 'gray'}>
                            {(finding.status ?? 'OPEN').replaceAll('_', ' ')}
                          </Badge>
                          <span className="text-xs font-medium text-slate-600">
                            {finding.category}{finding.line ? ` · line ${finding.line}` : ''}
                          </span>
                        </div>
                        <p className="mt-1 text-sm font-semibold text-slate-900">{finding.title}</p>
                        {finding.explanation && (
                          <p className="mt-1 text-sm leading-5 text-slate-600">{finding.explanation}</p>
                        )}
                      </li>
                    ))}
                  </ul>
                </section>
              ))}
            </Panel>
          )}

          {generation && (generation.fixRounds?.length ?? 0) > 0 && (
            <Panel className="space-y-3">
              <h2 className="text-lg font-semibold text-slate-900">Fix round history</h2>
              {generation.fixRounds.map((round) => {
                const versions = generation.fileVersions.filter(
                  (version) => version.roundNumber === round.roundNumber,
                );
                return (
                  <section key={round.approvalId} className="rounded-xl border border-slate-200 p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div>
                        <h3 className="font-semibold text-slate-900">Round {round.roundNumber}</h3>
                        <p className="text-sm text-slate-600">
                          Findings: {round.beforeFindingCount} before → {round.afterFindingCount ?? 'not completed'} after · {round.status}
                        </p>
                        <div className="mt-2 flex flex-wrap gap-2" aria-label={`Agent states for round ${round.roundNumber}`}>
                          {generation.agentWorkflow
                            .filter((agent) => agent.roundNumber === round.roundNumber)
                            .map((agent) => (
                              <Badge key={`${agent.agentType}-${agent.createdAt}`} tone={agent.status === 'COMPLETED' ? 'green' : agent.status === 'FAILED' ? 'red' : 'blue'}>
                                {agent.agentType}: {agent.status.replaceAll('_', ' ')}
                              </Badge>
                            ))}
                        </div>
                      </div>
                      {!versions.every((version) => version.reverted) && versions.length > 0 && (
                        <button
                          type="button"
                          className={secondaryButtonClass}
                          onClick={() => setRevertRoundNumber(round.roundNumber)}
                          disabled={fixBusy || (generation.status !== 'AWAITING_FIX_APPROVAL'
                            && generation.status !== 'REVIEWED' && generation.status !== 'FAILED')}
                        >
                          Revert round {round.roundNumber}
                        </button>
                      )}
                    </div>
                    {versions.map((version) => (
                      <details key={version.id} className="mt-3 rounded-lg border border-slate-200 p-3">
                        <summary className="cursor-pointer break-all text-sm font-medium text-slate-800">
                          Diff · {version.path}{version.reverted ? ' · Reverted' : ''}
                        </summary>
                        <div className="mt-3 grid gap-3 md:grid-cols-2">
                          <div>
                            <h4 className="text-xs font-semibold text-slate-600">Before</h4>
                            <pre className="mt-1 max-h-80 overflow-auto rounded-lg bg-slate-950 p-3 text-xs text-slate-100">
                              {version.beforeContent}
                            </pre>
                          </div>
                          <div>
                            <h4 className="text-xs font-semibold text-slate-600">After</h4>
                            <pre className="mt-1 max-h-80 overflow-auto rounded-lg bg-slate-950 p-3 text-xs text-slate-100">
                              {version.afterContent}
                            </pre>
                          </div>
                        </div>
                      </details>
                    ))}
                  </section>
                );
              })}
            </Panel>
          )}

          {generation && (
            <Panel className="space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-lg font-semibold text-slate-900">Generated project</h2>
                  <p className="mt-1 text-sm text-slate-600">
                    {files.length} file{files.length === 1 ? '' : 's'} available
                    {generation.status === 'CODING' && <span className="ml-2 text-indigo-700">· currently writing</span>}
                  </p>
                </div>
                <Badge tone={generation.status === 'CODING' ? 'blue' : 'gray'}>
                  {generation.status === 'CODING' ? 'Writing files' : `iteration ${generation.iteration}`}
                </Badge>
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
                <div className="min-h-40 overflow-hidden rounded-xl border border-slate-800 bg-slate-950 text-xs text-slate-100">
                  <div className="flex border-b border-slate-800">
                    <button type="button" className={`flex items-center gap-1 px-3 py-2 ${previewTab === 'code' ? 'bg-slate-800 text-white' : 'text-slate-400'}`} onClick={() => setPreviewTab('code')}>
                      <Code2 className="h-3.5 w-3.5" aria-hidden="true" /> Code
                    </button>
                    <button type="button" className={`flex items-center gap-1 px-3 py-2 ${previewTab === 'live' ? 'bg-slate-800 text-white' : 'text-slate-400'}`} onClick={() => setPreviewTab('live')}>
                      <Eye className="h-3.5 w-3.5" aria-hidden="true" /> Live app
                    </button>
                  </div>
                  {previewTab === 'live' ? (
                    <div className="p-4 text-slate-300">
                      <p className="flex items-center gap-2 font-medium text-slate-100">
                        <ShieldCheck className="h-4 w-4 text-amber-300" aria-hidden="true" />
                        Live preview unavailable
                      </p>
                      <p className="mt-2 leading-5">
                        Live preview needs a running sandbox, not available here.
                      </p>
                    </div>
                  ) : fileLoading ? (
                    <p className="text-slate-300">Loading…</p>
                  ) : selectedFile ? (
                    selectedFile.binary ? (
                      <p className="text-amber-300">Binary file — not shown. Download the ZIP to inspect it.</p>
                    ) : (
                      <div className="space-y-2">
                        <p className="flex items-center gap-2 font-mono text-[11px] text-slate-400">
                          <FileCode2 className="h-3.5 w-3.5" aria-hidden="true" />
                          {selectedFile.path} · {codeLanguage(selectedFile.path)}
                        </p>
                        <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words"><code>
                          {(selectedFile.content ?? '').split(/\r?\n/).map((line, lineIndex) => (
                            <span key={`${selectedFile.path}-${lineIndex}`} className="block">
                              <span className="mr-4 inline-block w-8 select-none text-right text-slate-600">{lineIndex + 1}</span>
                              {highlightedLine(line, `${selectedFile.path}-${lineIndex}`)}
                            </span>
                          ))}
                        </code></pre>
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

          {generation && settled(generation)
            && ['REVIEWED', 'COMPLETED', 'AWAITING_FIX_APPROVAL'].includes(generation.status)
            && generation.verificationLevel !== 'NOT_RUN'
            && generation.verificationLevel !== 'ISSUES_FOUND'
            && (generation.findings?.length ?? 0) === 0 && (
            <Panel className="space-y-3">
              <h2 className="flex items-center gap-2 text-lg font-semibold text-slate-900">
                <CheckCircle2 className="h-5 w-5 text-emerald-600" aria-hidden="true" />
                No findings
              </h2>
              <p className="text-sm text-slate-600">
                The run finished with no findings from the checks that ran.
              </p>
              <ul className="grid gap-2 text-sm text-slate-700 sm:grid-cols-3">
                <li className="rounded-lg bg-slate-50 p-2">Static analysis: ran</li>
                <li className="rounded-lg bg-slate-50 p-2">
                  Build: {generation.verificationLevel === 'SANDBOX_UNAVAILABLE' ? 'skipped' : 'ran'}
                </li>
                <li className="rounded-lg bg-slate-50 p-2">
                  Tests: {generation.verificationLevel === 'SANDBOX_UNAVAILABLE' ? 'skipped' : 'ran'}
                </li>
              </ul>
              {generation.verificationLevel === 'SANDBOX_UNAVAILABLE' && (
                <p className="text-sm text-amber-800">Build and tests were skipped because the sandbox was unavailable.</p>
              )}
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
                      Structure applied. Open the finding in Review to fix and
                      re-verify the moved files inline.
                    </p>
                  )}
                </div>
              )}
            </Panel>
          )}

          {fixDialogOpen && fixPreview && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4">
              <section
                role="dialog"
                aria-modal="true"
                aria-labelledby="generation-fix-title"
                ref={fixDialogRef}
                className="max-h-[90vh] w-full max-w-xl overflow-auto rounded-2xl bg-white p-6 shadow-xl"
              >
                <h2 id="generation-fix-title" className="text-lg font-semibold text-slate-900">
                  {fixPreview.findingCount} issues found. Allow the coding agent to fix them?
                </h2>
                <p className="mt-2 text-sm text-slate-600">
                  The coding agent will regenerate these files and their dependents. No files change until you approve below.
                </p>
                <h3 className="mt-4 text-sm font-semibold text-slate-800">Files that will change</h3>
                <ul className="mt-2 max-h-48 space-y-1 overflow-auto rounded-lg border border-slate-200 p-3">
                  {fixPreview.files.map((path) => (
                    <li key={path} className="break-all font-mono text-xs text-slate-700">{path}</li>
                  ))}
                </ul>
                {generation?.aiConfig.provider === 'NONE' ? (
                  <p className="mt-4 text-sm text-amber-800">
                    This template generation has no AI coding provider configured, so automatic fixes are unavailable.
                  </p>
                ) : (
                  <div className="mt-4">
                    <label htmlFor="fix-api-key" className="mb-1 block text-sm font-medium text-slate-700">
                      AI provider API key (requested again for this round)
                    </label>
                    <input
                      id="fix-api-key"
                      type="password"
                      autoComplete="off"
                      className={inputClass}
                      value={fixApiKey}
                      onChange={(event) => setFixApiKey(event.target.value)}
                    />
                    <p className="mt-1 text-xs text-slate-500">Used in memory for this round only; never saved.</p>
                  </div>
                )}
                {fixError && <div className="mt-3"><ErrorAlert message={fixError} /></div>}
                <div className="mt-5 flex flex-wrap justify-end gap-2">
                  <button
                    type="button"
                    className={secondaryButtonClass}
                    onClick={() => {
                      setFixDialogOpen(false);
                      setFixPreview(null);
                      setFixApiKey('');
                    }}
                    disabled={fixBusy}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className={primaryButtonClass}
                    onClick={() => void approveFixes()}
                    disabled={fixBusy || generation?.aiConfig.provider === 'NONE' || !fixApiKey.trim()}
                  >
                    {fixBusy ? 'Starting fix round…' : 'Confirm'}
                  </button>
                </div>
              </section>
            </div>
          )}

          {retryExecutionId && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4">
              <section
                role="dialog"
                aria-modal="true"
                aria-labelledby="retry-stage-title"
                ref={retryDialogRef}
                className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-xl"
              >
                <h2 id="retry-stage-title" className="text-lg font-semibold text-slate-900">
                  Retry failed stage
                </h2>
                <p className="mt-2 text-sm text-slate-600">
                  Only this stage in round {generation?.iteration} will run again.
                </p>
                {generation?.aiConfig.provider !== 'NONE' && (
                  <div className="mt-4">
                    <label htmlFor="retry-api-key" className="mb-1 block text-sm font-medium text-slate-700">
                      AI provider API key
                    </label>
                    <input
                      id="retry-api-key"
                      type="password"
                      autoComplete="off"
                      className={inputClass}
                      value={retryApiKey}
                      onChange={(event) => setRetryApiKey(event.target.value)}
                    />
                  </div>
                )}
                {retryError && <div className="mt-3"><ErrorAlert message={retryError} /></div>}
                <div className="mt-5 flex justify-end gap-2">
                  <button
                    type="button"
                    className={secondaryButtonClass}
                    onClick={() => {
                      setRetryExecutionId(null);
                      setRetryApiKey('');
                      setRetryError(null);
                    }}
                    disabled={retryBusy}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className={primaryButtonClass}
                    onClick={() => void retryStage()}
                    disabled={retryBusy || (
                      generation?.aiConfig.provider !== 'NONE' && !retryApiKey.trim()
                    )}
                  >
                    {retryBusy ? 'Retrying…' : 'Retry stage'}
                  </button>
                </div>
              </section>
            </div>
          )}

          {revertRoundNumber !== null && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4">
              <section
                role="dialog"
                aria-modal="true"
                aria-labelledby="revert-round-title"
                ref={revertDialogRef}
                className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-xl"
              >
                <h2 id="revert-round-title" className="text-lg font-semibold text-slate-900">
                  Revert round {revertRoundNumber}?
                </h2>
                <p className="mt-2 text-sm text-slate-600">
                  This restores the files from before the round and removes its fix changes. Nothing changes until you confirm.
                </p>
                {fixError && <div className="mt-3"><ErrorAlert message={fixError} /></div>}
                <div className="mt-5 flex justify-end gap-2">
                  <button
                    type="button"
                    className={secondaryButtonClass}
                    onClick={() => {
                      setRevertRoundNumber(null);
                      setFixError(null);
                    }}
                    disabled={fixBusy}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className={primaryButtonClass}
                    onClick={() => {
                      const round = revertRoundNumber;
                      setRevertRoundNumber(null);
                      void revertFixRound(round);
                    }}
                    disabled={fixBusy}
                  >
                    {fixBusy ? 'Reverting…' : 'Confirm revert'}
                  </button>
                </div>
              </section>
            </div>
          )}
        </div>
      )}
        </div>
        <aside className="h-fit rounded-2xl border border-slate-200 bg-white p-4 shadow-sm lg:sticky lg:top-4" aria-label="Wizard summary">
          <h2 className="text-sm font-semibold text-slate-900">Your answers so far</h2>
          <dl className="mt-3 space-y-3 text-sm">
            <SummaryItem label="Project" value={name || 'Not chosen'} />
            <SummaryItem label="Engine" value={generationEngine || 'Not chosen'} />
            <SummaryItem label="Backend" value={backend ? BACKEND_LABELS[backend] : 'Not chosen'} />
            <SummaryItem label="Frontend" value={frontend ? FRONTEND_LABELS[frontend] : 'Not chosen'} />
            <SummaryItem label="Runtime" value={languageRuntime || 'Not chosen'} />
          </dl>
        </aside>
      </div>
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

function SummaryItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-slate-400">{label}</dt>
      <dd className="mt-0.5 break-words text-slate-700">{value}</dd>
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
  if (error instanceof ApiError) {
    const message = error.message.toLowerCase();
    if (message.includes('template generation supports')) {
      return 'This stack is not available for Template. Switch to the AI engine.';
    }
    if (message.includes('answer these questions')) {
      return 'Complete the required questions before continuing.';
    }
    if (message.includes('database')) {
      return 'Check the database answers or choose Ask me later for the connection.';
    }
    return 'We could not complete that request. Check your answers and try again.';
  }
  if (error instanceof Error) return error.message;
  return 'Something went wrong. Please try again.';
}
