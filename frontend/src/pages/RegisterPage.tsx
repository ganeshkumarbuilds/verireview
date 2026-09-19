import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { AuthLayout } from '../components/AuthLayout';
import { EnvelopeIcon, LockIcon, PillField, UserIcon } from '../components/FieldIcons';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

const pillInput =
  'w-full bg-transparent text-[15px] text-slate-900 placeholder:text-slate-500 focus:outline-none';

export function RegisterPage() {
  useDocumentTitle('Register');
  const { register } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await register(email, password, displayName || undefined);
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Registration failed.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout>
      <div className="rounded-3xl border border-slate-200 bg-white px-6 py-10 shadow-sm sm:px-12">
        <h1 className="text-center text-4xl font-bold tracking-tight">Register</h1>
        <p className="mt-2 text-center text-[15px] text-slate-500">
          Create your account to continue
        </p>
        <form onSubmit={handleSubmit} aria-label="Register form" className="mt-8 space-y-4">
          <PillField icon={<UserIcon />}>
            <input
              type="text"
              name="displayName"
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              autoComplete="nickname"
              placeholder="Display name"
              aria-label="Display name"
              className={pillInput}
            />
          </PillField>
          <PillField icon={<EnvelopeIcon />}>
            <input
              type="email"
              name="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              autoComplete="email"
              placeholder="Email id"
              aria-label="Email"
              className={pillInput}
            />
          </PillField>
          <PillField icon={<LockIcon />}>
            <input
              type="password"
              name="password"
              required
            minLength={8}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="new-password"
            placeholder="Password (min 8 characters)"
              aria-label="Password"
              className={pillInput}
            />
          </PillField>
          {error && (
            <p role="alert" className="text-center text-sm text-red-600">
              {error}
            </p>
          )}
          <button
            type="submit"
            disabled={busy}
            className="w-full rounded-full bg-indigo-500 py-3 text-lg font-medium text-white hover:bg-indigo-400 disabled:opacity-60"
          >
            {busy ? 'Creating account…' : 'Register'}
          </button>
        </form>
        <p className="mt-6 text-center text-[15px] text-slate-500">
          Have an account?{' '}
          <Link to="/login" className="text-indigo-500 hover:underline">
            Log in
          </Link>
        </p>
      </div>
    </AuthLayout>
  );
}
