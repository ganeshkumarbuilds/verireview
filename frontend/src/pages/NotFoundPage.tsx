import { Link } from 'react-router-dom';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

export function NotFoundPage() {
  useDocumentTitle('Not found');
  return (
    <div className="space-y-3 pt-6 text-center">
      <h1 className="text-xl font-semibold text-slate-900">Page not found</h1>
      <p className="text-sm text-slate-500">That route does not exist.</p>
      <Link to="/" className="text-sm text-indigo-600 hover:underline">
        Back to home
      </Link>
    </div>
  );
}
