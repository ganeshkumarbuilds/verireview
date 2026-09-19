import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

const NAV_LINKS = [
  { href: '#features', label: 'Features' },
  { href: '#how-it-works', label: 'How it works' },
  { href: '#verification', label: 'Verification' },
  { href: '#architecture', label: 'Architecture' },
];

const FEATURES = [
  {
    title: 'Deterministic analysis first',
    body: 'Checkstyle, PMD, SpotBugs, and dependency scanning run in an isolated Docker sandbox. Same code, same findings — reproducible down to the pinned tool versions.',
  },
  {
    title: 'AI proposes, evidence disposes',
    body: 'Review, coding, and verdict agents suggest; they never decide. Every AI output is re-validated by the backend before it touches your project.',
  },
  {
    title: 'Approval-gated fixes',
    body: 'No patch exists without your explicit FixRequest. Diffs are validated, stored, and viewable — never silently applied to your code.',
  },
  {
    title: 'Sandboxed proof',
    body: 'Every proposed fix is built and tested in a throwaway container with CPU, memory, and time caps. Your machine never executes untrusted code.',
  },
  {
    title: 'Honest finding labels',
    body: 'Every finding is stamped DETERMINISTIC, AI, or VERIFIED. An AI guess is never relabeled as verified — the badge requires a linked passing run.',
  },
  {
    title: 'Full traceability',
    body: 'Each agent call, review, patch, test result, and verdict is persisted with its evidence. Audit every decision back to the exact run that produced it.',
  },
];

const STEPS = [
  {
    title: 'Generate',
    body: 'Describe what you want. A planning agent drafts a design; you approve or redirect it before a single file is scaffolded.',
  },
  {
    title: 'Review',
    body: 'Deterministic analyzers scan the code in a sandbox, then the Review Agent layers AI findings on top — deduplicated, ranked, and labeled.',
  },
  {
    title: 'Fix',
    body: 'Pick a finding and request a fix. The Coding Agent returns a minimal unified diff for your approval — your tree stays untouched until you say so.',
  },
  {
    title: 'Verify',
    body: 'The patch is applied to an isolated copy, built, tested, and re-analyzed. Green build plus passing tests plus no new critical issues earns VERIFIED.',
  },
];

const VERIFICATION_GATES = [
  'Build succeeds in a clean sandbox',
  'Target tests actually run — and pass',
  'No new CRITICAL or HIGH findings introduced',
  'Every verdict links its evidence run ID',
];

const ARCHITECTURE = [
  {
    title: 'Spring Boot backend',
    body: 'Modular monolith owning business truth: auth, persistence, job orchestration, sandbox dispatch, and verdict policy. PostgreSQL via Flyway migrations.',
  },
  {
    title: 'Python AI service',
    body: 'LangGraph agents for planning, review, coding, and verification. Stateless and replaceable — it proposes through versioned contracts only.',
  },
  {
    title: 'React dashboard',
    body: 'This UI. Talks to the backend REST API exclusively — never to the AI service or sandbox directly.',
  },
];

export function HomePage() {
  useDocumentTitle('Home');
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="min-h-full bg-slate-50 text-slate-900">
      <header className="sticky top-0 z-50 border-b border-slate-200 bg-white/90 backdrop-blur">
        <div className="mx-auto flex h-16 w-full max-w-6xl items-center gap-3 px-4">
          <Link to="/" className="flex items-center gap-2.5" aria-label="VeriReview home">
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-600 font-mono text-sm font-bold text-white">
              VR
            </span>
            <span className="text-sm font-semibold tracking-wide">VeriReview</span>
          </Link>
          <nav aria-label="Homepage sections" className="ml-8 hidden items-center gap-1 md:flex">
            {NAV_LINKS.map((link) => (
              <a
                key={link.href}
                href={link.href}
                className="rounded-lg px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-100 hover:text-slate-900"
              >
                {link.label}
              </a>
            ))}
          </nav>
          <div className="ml-auto hidden items-center gap-2 md:flex">
            <Link
              to="/login"
              className="rounded-lg px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900"
            >
              Log in
            </Link>
            <Link
              to="/register"
              className="rounded-lg bg-indigo-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-indigo-500"
            >
              Get started
            </Link>
          </div>
          <button
            type="button"
            aria-label="Toggle menu"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
            className="ml-auto rounded-lg p-2 text-slate-600 hover:bg-slate-100 md:hidden"
          >
            ☰
          </button>
        </div>
        {menuOpen && (
          <nav
            aria-label="Mobile sections"
            className="border-t border-slate-200 bg-white px-4 py-2 md:hidden"
          >
            {NAV_LINKS.map((link) => (
              <a
                key={link.href}
                href={link.href}
                onClick={() => setMenuOpen(false)}
                className="block rounded-lg px-3 py-2 text-sm text-slate-600 hover:bg-slate-100"
              >
                {link.label}
              </a>
            ))}
            <div className="flex gap-2 py-2">
              <Link
                to="/login"
                className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-center text-sm font-semibold text-slate-700"
              >
                Log in
              </Link>
              <Link
                to="/register"
                className="flex-1 rounded-lg bg-indigo-600 px-3 py-2 text-center text-sm font-semibold text-white"
              >
                Get started
              </Link>
            </div>
          </nav>
        )}
      </header>

      <main>
        <section className="mx-auto w-full max-w-6xl px-4">
          <div className="mx-auto max-w-2xl py-16 text-center sm:py-24">
            <p className="mb-4 inline-block rounded-full border border-indigo-200 bg-indigo-50 px-3 py-1 text-xs font-medium text-indigo-700">
              AI-powered review with deterministic proof
            </p>
            <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">
              Generate. Review. Fix. Verify.
            </h1>
            <p className="mx-auto mt-5 max-w-xl text-base leading-relaxed text-slate-500">
              VeriReview is an AI software-engineering platform with one rule:
              the AI is never taken at its word. Upload a project, collect
              reproducible findings, approve surgical fixes, and ship only what
              a sandboxed build and its tests prove correct.
            </p>
            <div className="mt-8 flex flex-col items-stretch justify-center gap-3 sm:flex-row sm:items-center">
              <Link
                to="/register"
                className="rounded-lg bg-indigo-600 px-6 py-2.5 text-center text-sm font-semibold text-white shadow-sm hover:bg-indigo-500"
              >
                Get started
              </Link>
              <Link
                to="/login"
                className="rounded-lg border border-slate-300 bg-white px-6 py-2.5 text-center text-sm font-semibold text-slate-700 hover:border-slate-400"
              >
                Log in
              </Link>
            </div>
          </div>
        </section>

        <section
          id="features"
          aria-label="Features"
          className="mx-auto w-full max-w-6xl scroll-mt-20 px-4 pb-16"
        >
          <p className="text-xs font-semibold uppercase tracking-wider text-indigo-600">
            Why VeriReview
          </p>
          <h2 className="mt-1 text-2xl font-bold tracking-tight">
            Trust, but verify — automatically
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-slate-500">
            AI coding assistants are fluent and frequently wrong. VeriReview wraps
            them in an engineering process where every claim must survive contact
            with a compiler, a test suite, and a policy check.
          </p>
          <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {FEATURES.map((feature) => (
              <div
                key={feature.title}
                className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm"
              >
                <h3 className="mb-1.5 text-sm font-semibold text-slate-800">
                  {feature.title}
                </h3>
                <p className="text-sm leading-relaxed text-slate-500">{feature.body}</p>
              </div>
            ))}
          </div>
        </section>

        <section
          id="how-it-works"
          aria-label="How it works"
          className="border-y border-slate-200 bg-white"
        >
          <div className="mx-auto w-full max-w-6xl px-4 py-16">
            <p className="text-xs font-semibold uppercase tracking-wider text-indigo-600">
              The loop
            </p>
            <h2 className="mt-1 text-2xl font-bold tracking-tight">From idea to proven fix</h2>
            <ol className="mt-6 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
              {STEPS.map((step, index) => (
                <li key={step.title} className="flex flex-col gap-2">
                  <span className="flex h-8 w-8 items-center justify-center rounded-full bg-indigo-600 text-sm font-bold text-white">
                    {index + 1}
                  </span>
                  <h3 className="text-sm font-semibold">{step.title}</h3>
                  <p className="text-sm leading-relaxed text-slate-500">{step.body}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section
          id="verification"
          aria-label="Verification policy"
          className="mx-auto w-full max-w-6xl scroll-mt-20 px-4 py-16"
        >
          <div className="grid gap-8 lg:grid-cols-2 lg:items-start">
            <div>
              <p className="text-xs font-semibold uppercase tracking-wider text-indigo-600">
                The guarantee
              </p>
              <h2 className="mt-1 text-2xl font-bold tracking-tight">
                No VERIFIED without evidence
              </h2>
              <p className="mt-3 text-sm leading-relaxed text-slate-500">
                A fix graduates from <em>proposed</em> to <em>verified</em> only
                when all four gates pass on the same sandboxed run. Anything
                less — including zero tests executed — is recorded as REJECTED
                with its reason, never generously approved.
              </p>
              <ul className="mt-5 space-y-2.5">
                {VERIFICATION_GATES.map((gate) => (
                  <li key={gate} className="flex items-start gap-2.5 text-sm">
                    <span
                      aria-hidden="true"
                      className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-xs font-bold text-emerald-700"
                    >
                      ✓
                    </span>
                    <span className="text-slate-700">{gate}</span>
                  </li>
                ))}
              </ul>
            </div>
            <div
              aria-label="Example verified finding"
              className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm"
            >
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <span className="rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-medium text-red-700">
                  HIGH
                </span>
                <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] font-medium text-emerald-700">
                  VERIFIED
                </span>
                <span className="ml-auto font-mono text-[11px] text-slate-400">
                  run #4821
                </span>
              </div>
              <p className="font-mono text-sm font-semibold text-slate-800">
                SQL injection via string-concatenated query
              </p>
              <p className="mt-1 font-mono text-xs text-slate-500">
                src/main/java/dao/UserDao.java:87
              </p>
              <dl className="mt-4 space-y-1.5 font-mono text-xs">
                <div className="flex justify-between gap-4">
                  <dt className="text-slate-400">build</dt>
                  <dd className="text-emerald-600">SUCCESS</dd>
                </div>
                <div className="flex justify-between gap-4">
                  <dt className="text-slate-400">tests</dt>
                  <dd className="text-emerald-600">42 passed / 0 failed</dd>
                </div>
                <div className="flex justify-between gap-4">
                  <dt className="text-slate-400">new critical / high</dt>
                  <dd className="text-emerald-600">none</dd>
                </div>
              </dl>
            </div>
          </div>
        </section>

        <section
          id="architecture"
          aria-label="Architecture"
          className="border-t border-slate-200 bg-white"
        >
          <div className="mx-auto w-full max-w-6xl px-4 py-16">
            <p className="text-xs font-semibold uppercase tracking-wider text-indigo-600">
              Under the hood
            </p>
            <h2 className="mt-1 text-2xl font-bold tracking-tight">
              Boring where it counts, modern where it matters
            </h2>
            <div className="mt-6 grid gap-4 md:grid-cols-3">
              {ARCHITECTURE.map((item) => (
                <div
                  key={item.title}
                  className="rounded-xl border border-slate-200 bg-slate-50 p-5"
                >
                  <h3 className="mb-1.5 text-sm font-semibold text-slate-800">
                    {item.title}
                  </h3>
                  <p className="text-sm leading-relaxed text-slate-500">{item.body}</p>
                </div>
              ))}
            </div>
            <div className="mt-8 rounded-xl bg-slate-900 p-6 text-center sm:p-8">
              <h2 className="text-xl font-bold text-white">
                Stop trusting AI output. Start verifying it.
              </h2>
              <p className="mx-auto mt-2 max-w-md text-sm text-slate-400">
                Create an account, upload a ZIP, and watch your first
                evidence-backed verdict land in minutes.
              </p>
              <div className="mt-5 flex flex-col items-stretch justify-center gap-3 sm:flex-row sm:items-center">
                <Link
                  to="/register"
                  className="rounded-lg bg-indigo-600 px-6 py-2.5 text-center text-sm font-semibold text-white hover:bg-indigo-500"
                >
                  Get started
                </Link>
                <Link
                  to="/login"
                  className="rounded-lg border border-slate-700 px-6 py-2.5 text-center text-sm font-semibold text-slate-200 hover:border-slate-500"
                >
                  Log in
                </Link>
              </div>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t border-slate-200 bg-white">
        <div className="mx-auto grid w-full max-w-6xl gap-8 px-4 py-10 sm:grid-cols-3">
          <div>
            <div className="flex items-center gap-2.5">
              <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-600 font-mono text-sm font-bold text-white">
                VR
              </span>
              <span className="text-sm font-semibold tracking-wide">VeriReview</span>
            </div>
            <p className="mt-3 max-w-xs text-sm leading-relaxed text-slate-500">
              Generate. Review. Fix. Verify. AI proposes — deterministic tools,
              sandboxes, and backend policy dispose.
            </p>
          </div>
          <nav aria-label="Product">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Product
            </p>
            <ul className="space-y-1.5 text-sm">
              <li>
                <Link to="/dashboard" className="text-slate-600 hover:text-slate-900">
                  Dashboard
                </Link>
              </li>
              <li>
                <Link to="/projects" className="text-slate-600 hover:text-slate-900">
                  Projects
                </Link>
              </li>
              <li>
                <Link to="/review" className="text-slate-600 hover:text-slate-900">
                  Review
                </Link>
              </li>
              <li>
                <Link to="/history" className="text-slate-600 hover:text-slate-900">
                  History
                </Link>
              </li>
            </ul>
          </nav>
          <nav aria-label="Account">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-400">
              Account
            </p>
            <ul className="space-y-1.5 text-sm">
              <li>
                <Link to="/login" className="text-slate-600 hover:text-slate-900">
                  Log in
                </Link>
              </li>
              <li>
                <Link to="/register" className="text-slate-600 hover:text-slate-900">
                  Register
                </Link>
              </li>
            </ul>
          </nav>
        </div>
        <div className="border-t border-slate-200 py-4 text-center text-xs text-slate-400">
          VeriReview — evidence over promises.
        </div>
      </footer>
    </div>
  );
}
