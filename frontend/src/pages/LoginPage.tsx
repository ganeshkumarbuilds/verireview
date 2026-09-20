import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { requestPasswordReset } from '../api/auth';
import { loginServerErrorMessage, loginValidationError } from '../api/authErrors';
import { apiClient, useAuth } from '../auth/AuthContext';
import { AuthLayout } from '../components/AuthLayout';
import { EnvelopeIcon, LockIcon, PillField } from '../components/FieldIcons';

const pillInput =
  'w-full bg-transparent text-[15px] text-slate-900 placeholder:text-slate-500 focus:outline-none focus-visible:outline-none';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [resetHint, setResetHint] = useState(false);
  const [resetEmail, setResetEmail] = useState('');
  const [resetSent, setResetSent] = useState(false);
  const [resetError, setResetError] = useState<string | null>(null);
  const [resetBusy, setResetBusy] = useState(false);
  const [busy, setBusy] = useState(false);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const validation = loginValidationError(email, password);
    if (validation) {
      setError(validation);
      return;
    }
    setError(null);
    setBusy(true);
    try {
      await login(email.trim(), password);
      navigate('/dashboard', { replace: true });
    } catch (err) {
      // Expected auth failures (401/403/429/5xx/offline) land here and are
      // rendered as friendly copy — never rethrown, never logged.
      setError(loginServerErrorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout>
      <div className="rounded-3xl border border-indigo-100 bg-white px-6 py-10 shadow-md shadow-indigo-100 sm:px-12">
        <h1 className="text-center text-4xl font-bold tracking-tight text-indigo-950">Login</h1>
        <p className="mt-2 text-center text-[15px] text-indigo-950/60">Please sign in to continue</p>
        <form onSubmit={handleSubmit} noValidate aria-label="Login form" className="mt-8 space-y-4">
          <PillField icon={<EnvelopeIcon />}>
            <input
              type="email"
              name="email"
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
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              placeholder="Password"
              aria-label="Password"
              className={pillInput}
            />
          </PillField>
          <div>
            <button
              type="button"
              onClick={() => {
                setResetHint((shown) => !shown);
                setResetSent(false);
                setResetError(null);
              }}
              className="text-[15px] font-medium text-indigo-600 hover:text-indigo-700 hover:underline"
            >
              Forgot password?
            </button>
            {resetHint && (
              <form
                aria-label="Password reset form"
                className="mt-2 space-y-2 rounded-2xl border border-indigo-100 bg-indigo-50/60 p-3"
                onSubmit={async (event) => {
                  event.preventDefault();
                  setResetError(null);
                  setResetBusy(true);
                  try {
                    await requestPasswordReset(apiClient(), resetEmail);
                    setResetSent(true);
                  } catch (err) {
                    setResetError(
                      err instanceof ApiError ? err.message : 'Request failed.',
                    );
                  } finally {
                    setResetBusy(false);
                  }
                }}
              >
                {resetSent ? (
                  <p className="text-sm text-slate-600">
                    If an account with that email exists, reset instructions
                    will be sent.
                  </p>
                ) : (
                  <>
                    <PillField icon={<EnvelopeIcon />}>
                      <input
                        type="email"
                        required
                        value={resetEmail}
                        onChange={(event) => setResetEmail(event.target.value)}
                        autoComplete="email"
                        placeholder="Email id"
                        aria-label="Reset email"
                        className={pillInput}
                      />
                    </PillField>
                    {resetError && (
                      <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600">
                        {resetError}
                      </p>
                    )}
                    <button
                      type="submit"
                      disabled={resetBusy}
                      className="w-full rounded-full bg-indigo-600 py-2 text-[15px] font-medium text-white shadow-sm shadow-indigo-200 hover:bg-indigo-500 disabled:opacity-60"
                    >
                      {resetBusy ? 'Sending…' : 'Send reset link'}
                    </button>
                  </>
                )}
              </form>
            )}
          </div>
          {error && (
            <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-center text-sm text-red-600">
              {error}
            </p>
          )}
          <button
            type="submit"
            disabled={busy}
            className="w-full rounded-full bg-indigo-600 py-3 text-lg font-medium text-white shadow-sm shadow-indigo-200 hover:bg-indigo-500 disabled:opacity-60"
          >
            {busy ? 'Signing in…' : 'Login'}
          </button>
        </form>
        <p className="mt-6 text-center text-[15px] text-indigo-950/60">
          Don&apos;t have an account?{' '}
          <Link to="/register" className="font-medium text-indigo-600 hover:text-indigo-700 hover:underline">
            Sign up
          </Link>
        </p>
      </div>
    </AuthLayout>
  );
}
