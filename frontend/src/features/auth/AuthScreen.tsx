import { useState, type FormEvent } from 'react';
import { ApiError } from './api';
import { useAuth } from './AuthProvider';

type AuthMode = 'login' | 'register';

interface AuthScreenProps {
  mode: AuthMode;
  notice?: string;
  onModeChange(mode: AuthMode, notice?: string): void;
  onBack(): void;
}

function passwordPolicyError(password: string): string | null {
  const characters = Array.from(password).length;
  const bytes = new TextEncoder().encode(password).length;
  if (characters < 12 || characters > 128) {
    return 'Use between 12 and 128 characters.';
  }
  if (bytes > 72) {
    return 'This password is too long when encoded. Use 72 UTF-8 bytes or fewer.';
  }
  return null;
}

function getErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_CREDENTIALS') {
      return 'The email or password is incorrect. Please try again.';
    }
    if (error.code === 'EMAIL_TAKEN') {
      return 'An account with this email already exists. Sign in instead.';
    }
    if (error.code === 'RATE_LIMITED') {
      return 'Too many attempts. Please wait a moment and try again.';
    }
    return error.correlationId
      ? `${error.message} (Reference: ${error.correlationId})`
      : error.message;
  }
  return 'We could not reach the server. Check that the backend is running and try again.';
}

export function AuthScreen({ mode, notice, onModeChange, onBack }: AuthScreenProps) {
  const { login, register, isSubmitting } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const isRegister = mode === 'register';

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);

    const cleanEmail = email.trim();
    if (!cleanEmail) {
      setError('Enter your email address.');
      return;
    }
    if (isRegister && !displayName.trim()) {
      setError('Enter the name you want people to see.');
      return;
    }
    if (isRegister) {
      const policyError = passwordPolicyError(password);
      if (policyError) {
        setError(policyError);
        return;
      }
    }

    try {
      if (isRegister) {
        await register(cleanEmail, password, displayName.trim());
        onModeChange('login', 'Your account has been created. Sign in to continue.');
      } else {
        await login(cleanEmail, password);
      }
    } catch (submitError) {
      setError(getErrorMessage(submitError));
    }
  }

  return (
    <main className="auth-layout">
      <section className="auth-brand-panel">
        <button className="brand auth-brand" type="button" onClick={onBack}>
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </button>
        <div className="auth-brand-copy">
          <p className="eyebrow"><span className="status-dot" /> YOUR PEOPLE. YOUR STORIES. IN SYNC.</p>
          <h1>Make room<br />for <span>together.</span></h1>
          <p>One place to watch, chat, and discover more about the stories you love.</p>
        </div>
        <p className="auth-brand-footer">A shared way to experience stories.</p>
      </section>

      <section className="auth-form-panel" aria-labelledby="auth-heading">
        <button className="back-link" type="button" onClick={onBack}>← Back to home</button>
        <div className="auth-form-wrap">
          <p className="eyebrow">{isRegister ? 'START YOUR NEXT WATCH PARTY' : 'WELCOME BACK'}</p>
          <h2 id="auth-heading">{isRegister ? 'Create your account' : 'Good to see you again.'}</h2>
          <p className="auth-subtitle">
            {isRegister
              ? 'Set up your profile and bring your people together.'
              : 'Sign in to pick up where your group left off.'}
          </p>

          {notice && <p className="auth-notice" role="status">{notice}</p>}
          {error && <p className="auth-error" role="alert">{error}</p>}

          <form className="auth-form" onSubmit={handleSubmit}>
            {isRegister && (
              <label className="form-field">
                <span>Display name</span>
                <input
                  autoComplete="name"
                  maxLength={80}
                  value={displayName}
                  onChange={(event) => setDisplayName(event.target.value)}
                  placeholder="What should we call you?"
                  required
                />
              </label>
            )}
            <label className="form-field">
              <span>Email address</span>
              <input
                autoComplete="email"
                inputMode="email"
                type="email"
                maxLength={320}
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="you@example.com"
                required
              />
            </label>
            <label className="form-field">
              <span>Password</span>
              <input
                autoComplete={isRegister ? 'new-password' : 'current-password'}
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder={isRegister ? 'At least 12 characters' : 'Enter your password'}
                required
              />
              {isRegister && <small>12–128 characters and no more than 72 UTF-8 bytes.</small>}
            </label>
            <button className="primary-button auth-submit" type="submit" disabled={isSubmitting}>
              {isSubmitting
                ? 'Please wait…'
                : isRegister ? 'Create account' : 'Sign in'}
              {!isSubmitting && <span aria-hidden="true">→</span>}
            </button>
          </form>

          <p className="auth-switch">
            {isRegister ? 'Already have an account?' : 'New to Watch Spaces?'}
            {' '}
            <button
              type="button"
              onClick={() => onModeChange(isRegister ? 'login' : 'register')}
            >
              {isRegister ? 'Sign in' : 'Create an account'}
            </button>
          </p>
          <p className="auth-security-note">
            Your session is held in memory only. Reloading or closing this tab ends it; tokens are not saved in browser storage.
          </p>
        </div>
      </section>
    </main>
  );
}
