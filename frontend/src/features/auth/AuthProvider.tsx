import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ApiError, authApi, type UserProfile } from './api';

interface AuthSession {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
}

interface AuthContextValue {
  user: UserProfile | null;
  isAuthenticated: boolean;
  isSubmitting: boolean;
  login(email: string, password: string): Promise<void>;
  register(email: string, password: string, displayName: string): Promise<void>;
  logout(): Promise<void>;
  refreshAccessToken(): Promise<void>;
  getAccessToken(): string | null;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function toSession(pair: {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
}): AuthSession {
  return {
    accessToken: pair.accessToken,
    accessTokenExpiresAt: pair.accessTokenExpiresAt,
    refreshToken: pair.refreshToken,
    refreshTokenExpiresAt: pair.refreshTokenExpiresAt,
  };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const currentSession = useRef<AuthSession | null>(null);
  const refreshInFlight = useRef<Promise<void> | null>(null);

  const replaceSession = useCallback((next: AuthSession | null) => {
    currentSession.current = next;
    setSession(next);
  }, []);

  const refreshAccessToken = useCallback((): Promise<void> => {
    if (refreshInFlight.current) {
      return refreshInFlight.current;
    }

    const existing = currentSession.current;
    if (!existing) {
      return Promise.resolve();
    }

    const task = (async () => {
      try {
        // The backend rotates refresh tokens. Only one refresh may run at a time,
        // otherwise replay detection intentionally revokes the whole token family.
        const pair = await authApi.refresh(existing.refreshToken);
        // A sign-out or replacement session may have happened while the request was in flight.
        if (currentSession.current !== existing) {
          return;
        }
        const nextSession = toSession(pair);
        replaceSession(nextSession);

        try {
          const profile = await authApi.me(pair.accessToken);
          setUser(profile);
        } catch (profileError) {
          if (profileError instanceof ApiError && profileError.status === 401) {
            replaceSession(null);
            setUser(null);
          } else {
            throw profileError;
          }
        }
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          replaceSession(null);
          setUser(null);
        }
        throw error;
      }
    })().finally(() => {
      refreshInFlight.current = null;
    });

    refreshInFlight.current = task;
    return task;
  }, [replaceSession]);

  useEffect(() => {
    if (!session) {
      return;
    }

    let timerId: number;
    const schedule = (delayMs: number) => {
      timerId = window.setTimeout(() => {
        void refreshAccessToken().catch(() => {
          // Preserve the session after transient network/5xx failures and retry.
          // A 401 clears the session inside refreshAccessToken.
          if (currentSession.current) {
            schedule(15_000);
          }
        });
      }, delayMs);
    };

    const expiresAt = Date.parse(session.accessTokenExpiresAt);
    const refreshInMs = Number.isFinite(expiresAt)
      ? Math.max(0, expiresAt - Date.now() - 60_000)
      : 0;
    schedule(refreshInMs);

    return () => window.clearTimeout(timerId);
  }, [session?.accessToken, session?.accessTokenExpiresAt, refreshAccessToken]);

  async function login(email: string, password: string) {
    setIsSubmitting(true);
    try {
      const response = await authApi.login(email, password);
      try {
        // Verify the newly issued bearer token against the protected profile endpoint.
        const profile = await authApi.me(response.accessToken);
        replaceSession(toSession(response));
        setUser(profile);
      } catch (error) {
        // Do not leave an unused refresh-token family active if profile verification fails.
        await authApi.logout(response.refreshToken).catch(() => undefined);
        throw error;
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  async function register(email: string, password: string, displayName: string) {
    setIsSubmitting(true);
    try {
      await authApi.register(email, password, displayName);
    } finally {
      setIsSubmitting(false);
    }
  }

  async function logout() {
    // Wait for an in-flight rotation so logout revokes the newest token, not the one it replaced.
    const pendingRefresh = refreshInFlight.current;
    if (pendingRefresh) {
      await pendingRefresh.catch(() => undefined);
    }
    const refreshToken = currentSession.current?.refreshToken;
    // Clear local application state even when the network request fails.
    replaceSession(null);
    setUser(null);
    if (refreshToken) {
      try {
        await authApi.logout(refreshToken);
      } catch {
        // The UI is logged out locally. The server-side token may remain valid until its expiry.
      }
    }
  }

  const value = useMemo<AuthContextValue>(() => ({
    user,
    isAuthenticated: user !== null && session !== null,
    isSubmitting,
    login,
    register,
    logout,
    refreshAccessToken,
    getAccessToken: () => currentSession.current?.accessToken ?? null,
  }), [user, session, isSubmitting, refreshAccessToken]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider.');
  }
  return context;
}
