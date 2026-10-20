import { Link } from 'react-router-dom';
import { primaryButtonClass } from '../components/ui';

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-md space-y-4 rounded-2xl border border-indigo-100 bg-white px-6 py-12 text-center shadow-sm shadow-indigo-100">
      <p className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-100 text-xl font-bold text-indigo-700">
        ?
      </p>
      <h1 className="text-xl font-bold tracking-tight text-indigo-950">Page not found</h1>
      <p className="text-sm leading-relaxed text-slate-600">
        That route does not exist. Use the navigation above to get back to your work.
      </p>
      <Link to="/dashboard" className={primaryButtonClass}>
        Go to dashboard
      </Link>
    </div>
  );
}
