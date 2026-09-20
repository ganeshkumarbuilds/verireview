import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-md space-y-4 rounded-2xl border border-indigo-100 bg-white px-6 py-12 text-center shadow-sm shadow-indigo-100">
      <p className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-100 text-xl font-bold text-indigo-700">
        ?
      </p>
      <h1 className="text-xl font-bold tracking-tight text-indigo-950">Page not found</h1>
      <p className="text-sm leading-relaxed text-slate-600">That route does not exist.</p>
      <Link
        to="/"
        className="inline-flex items-center justify-center rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm shadow-indigo-200 transition-colors hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-600 focus-visible:ring-offset-2"
      >
        Back to home
      </Link>
    </div>
  );
}
