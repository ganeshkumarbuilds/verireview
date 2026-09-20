import { ApiError } from './client';

/**
 * Login-facing error copy. Authentication failures must never leak raw
 * HTTP/backend text ("Request failed with status 401") and must never
 * distinguish "unknown email" from "wrong password" (account-enumeration
 * resistance lives server-side; the UI keeps one message for both).
 * No passwords, tokens, or response bodies are logged here.
 */

export function isValidEmail(email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());
}

/** Client-side login validation. Returns the message to show, or null when OK. */
export function loginValidationError(email: string, password: string): string | null {
  if (!email.trim()) {
    return 'Please enter your email address.';
  }
  if (!isValidEmail(email)) {
    return 'Please enter a valid email address.';
  }
  if (!password) {
    return 'Please enter your password.';
  }
  return null;
}

/** Maps a failed login attempt to user-facing copy (never raw status text). */
export function loginServerErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 400:
      case 401:
        // Deliberately identical: unregistered email and wrong password
        // both surface as invalid credentials.
        return 'Invalid email or password.';
      case 403:
        return 'Your account is disabled. Please contact support.';
      case 429:
        return 'Too many login attempts. Please try again later.';
      default:
        return 'Something went wrong. Please try again.';
    }
  }
  // fetch itself rejected (DNS, refused connection, offline, CORS, timeout).
  return 'Unable to connect to VeriReview. Please try again.';
}
