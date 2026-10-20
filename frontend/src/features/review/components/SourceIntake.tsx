import { useRef, useState } from 'react';
import type { FormEvent, KeyboardEvent } from 'react';
import { ApiError } from '../../../api/client';
import {
  importGitHubSync,
  importPaste,
  uploadZipSync,
  type PasteFileInput,
} from '../../../api/projects';
import type { ProjectResponse } from '../../../api/types';
import { apiClient, useAuth } from '../../../auth/AuthContext';
import {
  inputClass,
  primaryButtonClass,
  quietButtonClass,
  textareaClass,
} from '../../../components/ui';

type SourceTab = 'zip' | 'github' | 'paste';

const TABS: { key: SourceTab; label: string }[] = [
  { key: 'zip', label: 'Upload ZIP' },
  { key: 'github', label: 'GitHub URL' },
  { key: 'paste', label: 'Paste code' },
];

/**
 * Step 1 of Review: bring existing code in. All three intakes live on this
 * page, behind tabs, and only project data is created here — no generation
 * and no fixing.
 */
export function SourceIntake({ onImported }: { onImported: (project: ProjectResponse) => void }) {
  const { token } = useAuth();
  const [tab, setTab] = useState<SourceTab>('zip');
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [imported, setImported] = useState<string | null>(null);

  const [zipName, setZipName] = useState('');
  const [file, setFile] = useState<File | null>(null);

  const [ghName, setGhName] = useState('');
  const [ghUrl, setGhUrl] = useState('');
  const [ghDescription, setGhDescription] = useState('');
  const [ghLanguage, setGhLanguage] = useState('');

  const [pasteName, setPasteName] = useState('');
  const [pasteDescription, setPasteDescription] = useState('');
  const [pasteLanguage, setPasteLanguage] = useState('');
  const [pasteFiles, setPasteFiles] = useState<PasteFileInput[]>([{ path: '', content: '' }]);

  const resetMessages = () => {
    setError(null);
    setImported(null);
  };

  const selectTab = (next: SourceTab) => {
    setTab(next);
    resetMessages();
  };

  /** Standard tablist keyboard model: arrows wrap, Home/End jump to the ends. */
  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = TABS.length - 1;
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
        next = index === last ? 0 : index + 1;
        break;
      case 'ArrowLeft':
        next = index === 0 ? last : index - 1;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = last;
        break;
      default:
        return;
    }
    event.preventDefault();
    selectTab(TABS[next].key);
    tabRefs.current[next]?.focus();
  };

  const complete = (project: ProjectResponse) => {
    setImported(`Imported “${project.name}”. Analysis is ready below.`);
    onImported(project);
  };

  const fail = (err: unknown, fallback: string) => {
    setError(err instanceof ApiError ? err.message : fallback);
  };

  const handleZip = async (event: FormEvent) => {
    event.preventDefault();
    if (!token || busy || !file || !zipName.trim()) return;
    resetMessages();
    setBusy(true);
    try {
      const project = await uploadZipSync(apiClient(), token, {
        file,
        name: zipName.trim(),
      });
      setFile(null);
      setZipName('');
      complete(project);
    } catch (err) {
      fail(err, 'ZIP import failed.');
    } finally {
      setBusy(false);
    }
  };

  const handleGitHub = async (event: FormEvent) => {
    event.preventDefault();
    if (!token || busy || !ghName.trim() || !ghUrl.trim()) return;
    resetMessages();
    setBusy(true);
    try {
      const project = await importGitHubSync(apiClient(), token, {
        name: ghName.trim(),
        url: ghUrl.trim(),
        description: ghDescription.trim() || undefined,
        language: ghLanguage.trim() || undefined,
      });
      setGhName('');
      setGhUrl('');
      setGhDescription('');
      setGhLanguage('');
      complete(project);
    } catch (err) {
      fail(err, 'GitHub import failed.');
    } finally {
      setBusy(false);
    }
  };

  const handlePaste = async (event: FormEvent) => {
    event.preventDefault();
    if (
      !token ||
      busy ||
      !pasteName.trim() ||
      pasteFiles.some((entry) => !entry.path.trim() || !entry.content.trim())
    ) {
      return;
    }
    resetMessages();
    setBusy(true);
    try {
      const project = await importPaste(apiClient(), token, {
        name: pasteName.trim(),
        description: pasteDescription.trim() || undefined,
        language: pasteLanguage.trim() || undefined,
        files: pasteFiles.map((entry) => ({ path: entry.path.trim(), content: entry.content })),
      });
      setPasteName('');
      setPasteDescription('');
      setPasteLanguage('');
      setPasteFiles([{ path: '', content: '' }]);
      complete(project);
    } catch (err) {
      fail(err, 'Paste import failed.');
    } finally {
      setBusy(false);
    }
  };

  const updatePasteFile = (index: number, field: keyof PasteFileInput, value: string) => {
    setPasteFiles((current) =>
      current.map((entry, i) => (i === index ? { ...entry, [field]: value } : entry)),
    );
  };

  return (
    <div className="space-y-4">
      <div role="tablist" aria-label="Code source" className="flex flex-wrap gap-2">
        {TABS.map((item, index) => {
          const selected = tab === item.key;
          return (
            <button
              key={item.key}
              ref={(node) => {
                tabRefs.current[index] = node;
              }}
              type="button"
              role="tab"
              id={`source-tab-${item.key}`}
              aria-selected={selected}
              aria-controls={selected ? `source-panel-${item.key}` : undefined}
              tabIndex={selected ? 0 : -1}
              onKeyDown={(event) => onTabKeyDown(event, index)}
              onClick={() => selectTab(item.key)}
              className={`rounded-full border px-4 py-1.5 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2 ${
                selected
                  ? 'border-indigo-600 bg-indigo-600 text-white'
                  : 'border-indigo-200 bg-white text-slate-700 hover:border-indigo-300 hover:bg-indigo-50'
              }`}
            >
              {item.label}
            </button>
          );
        })}
      </div>

      {error && (
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
          {error}
        </p>
      )}
      {imported && (
        <p role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
          {imported}
        </p>
      )}

      {tab === 'zip' && (
        <form
          id="source-panel-zip"
          role="tabpanel"
          aria-labelledby="source-tab-zip"
          aria-label="Upload ZIP form"
          onSubmit={handleZip}
          className="space-y-3"
        >
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
            <p className="mt-1 text-xs text-slate-500">
              Exclude node_modules, .git and build outputs before zipping. They are skipped anyway.
            </p>
          </div>
          <button
            type="submit"
            disabled={busy || !file || !zipName.trim()}
            className={primaryButtonClass}
          >
            {busy ? 'Importing…' : 'Import ZIP'}
          </button>
        </form>
      )}

      {tab === 'github' && (
        <form
          id="source-panel-github"
          role="tabpanel"
          aria-labelledby="source-tab-github"
          aria-label="GitHub import form"
          onSubmit={handleGitHub}
          className="space-y-3"
        >
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
              disabled={busy}
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
              disabled={busy}
            />
            <p className="mt-1 text-xs text-slate-500">
              Public github.com repositories only; cloned at the latest commit.
            </p>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
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
                disabled={busy}
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
                disabled={busy}
              />
            </div>
          </div>
          <button
            type="submit"
            disabled={busy || !ghName.trim() || !ghUrl.trim()}
            className={primaryButtonClass}
          >
            {busy ? 'Importing…' : 'Import from GitHub'}
          </button>
        </form>
      )}

      {tab === 'paste' && (
        <form
          id="source-panel-paste"
          role="tabpanel"
          aria-labelledby="source-tab-paste"
          aria-label="Paste import form"
          onSubmit={handlePaste}
          className="space-y-3"
        >
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
                Project name
              </p>
              <input
                aria-label="Paste project name"
                value={pasteName}
                onChange={(event) => setPasteName(event.target.value)}
                placeholder="e.g. snippet-demo"
                maxLength={200}
                className={inputClass}
                disabled={busy}
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
                placeholder="e.g. java"
                maxLength={100}
                className={inputClass}
                disabled={busy}
              />
            </div>
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
              disabled={busy}
            />
          </div>
          <div className="space-y-3">
            {pasteFiles.map((entry, index) => (
              <div key={index} className="space-y-2 rounded-xl border border-indigo-100 bg-indigo-50/40 p-3">
                <div className="flex items-end gap-2">
                  <label className="min-w-0 flex-1">
                    <span className="mb-1 block text-xs font-semibold uppercase tracking-wider text-slate-500">
                      File path
                    </span>
                    <input
                      aria-label={`Paste file path ${index + 1}`}
                      value={entry.path}
                      onChange={(event) => updatePasteFile(index, 'path', event.target.value)}
                      placeholder="src/Main.java"
                      maxLength={500}
                      className={inputClass}
                      disabled={busy}
                    />
                  </label>
                  {pasteFiles.length > 1 && (
                    <button
                      type="button"
                      onClick={() =>
                        setPasteFiles((current) => current.filter((_, i) => i !== index))
                      }
                      disabled={busy}
                      className={quietButtonClass}
                    >
                      Remove
                    </button>
                  )}
                </div>
                <label className="block">
                  <span className="mb-1 block text-xs font-semibold uppercase tracking-wider text-slate-500">
                    Content
                  </span>
                  <textarea
                    aria-label={`Paste file content ${index + 1}`}
                    value={entry.content}
                    onChange={(event) => updatePasteFile(index, 'content', event.target.value)}
                    rows={4}
                    className={`${textareaClass} font-mono`}
                    disabled={busy}
                  />
                </label>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => setPasteFiles((current) => [...current, { path: '', content: '' }])}
              disabled={busy}
              className={quietButtonClass}
            >
              Add another file
            </button>
            <button
              type="submit"
              disabled={
                busy ||
                !pasteName.trim() ||
                pasteFiles.some((entry) => !entry.path.trim() || !entry.content.trim())
              }
              className={primaryButtonClass}
            >
              {busy ? 'Importing…' : 'Import pasted files'}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
