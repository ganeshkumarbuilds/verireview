export const GENERATE_DRAFT_VERSION = 2;
const DRAFT_PREFIX = 'verireview.generate.';

export type GenerateDraft = {
  version: number;
  userId: string;
  name: string;
  idea: string;
  extraInfo: string;
  generationEngine: 'TEMPLATE' | 'AI' | '';
  backend: string;
  frontend: string;
  languageRuntime: string;
  backendCustom: string;
  frontendCustom: string;
  answers: Record<string, { value: string; skipped: boolean }>;
};

export function draftKey(userId: string): string {
  return `${DRAFT_PREFIX}${userId}`;
}

export function clearGenerateDrafts(): void {
  try {
    for (let index = window.localStorage.length - 1; index >= 0; index -= 1) {
      const key = window.localStorage.key(index);
      if (key?.startsWith(DRAFT_PREFIX)) {
        window.localStorage.removeItem(key);
      }
    }
  } catch {
    // Draft persistence is best effort.
  }
}

function isSingleLine(value: unknown, maxLength?: number): value is string {
  return typeof value === 'string'
    && !/[\r\n]/.test(value)
    && (maxLength === undefined || value.length <= maxLength);
}

function isAnswerMap(value: unknown): value is GenerateDraft['answers'] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  return Object.values(value).every((answer) =>
    typeof answer === 'object'
    && answer !== null
    && typeof (answer as { value?: unknown }).value === 'string'
    && typeof (answer as { skipped?: unknown }).skipped === 'boolean');
}

export function readGenerateDraft(userId: string): GenerateDraft | null {
  if (!userId) return null;
  try {
    window.localStorage.removeItem('verireview.generate.wizard');
    const raw = window.localStorage.getItem(draftKey(userId));
    if (!raw) return null;
    const draft = JSON.parse(raw) as Partial<GenerateDraft>;
    if (
      draft.version !== GENERATE_DRAFT_VERSION
      || draft.userId !== userId
      || !isSingleLine(draft.name, 60)
      || !isSingleLine(draft.idea)
      || !isSingleLine(draft.extraInfo)
      || !isSingleLine(draft.languageRuntime)
      || !isSingleLine(draft.backendCustom)
      || !isSingleLine(draft.frontendCustom)
      || !isSingleLine(draft.generationEngine)
      || !isSingleLine(draft.backend)
      || !isSingleLine(draft.frontend)
      || !isAnswerMap(draft.answers)
    ) {
      window.localStorage.removeItem(draftKey(userId));
      return null;
    }
    return draft as GenerateDraft;
  } catch {
    try {
      window.localStorage.removeItem(draftKey(userId));
    } catch {
      // Ignore unavailable storage.
    }
    return null;
  }
}

export function writeGenerateDraft(draft: GenerateDraft): void {
  try {
    window.localStorage.setItem(draftKey(draft.userId), JSON.stringify(draft));
  } catch {
    // Draft persistence is best effort.
  }
}
