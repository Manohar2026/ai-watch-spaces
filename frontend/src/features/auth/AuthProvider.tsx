import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import { authApi, type UserProfile } from './api';

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
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function login(email: string, password: string) {
    setIsSubmitting(true);
    try {
      const response = await authApi.login(email, password);
      try {
        // Verify the newly issued bearer token against the protected profile endpoint.
        const profile = await authApi.me(response.accessToken);
        setSession({
          accessToken: response.accessToken,
          accessTokenExpiresAt: response.accessTokenExpiresAt,
          refreshToken: response.refreshToken,
          refreshTokenExpiresAt: response.refreshTokenExpiresAt,
        });
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
    const refreshToken = session?.refreshToken;
    // Clear local application state even when the network request fails.
    setSession(null);
    setUser(null);
    if (refreshToken) {
      try {
        await authApi.logout(refreshToken);
      } catch {
        // The UI is logged out locally. The server-side token may remain valid until expiry.
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
  }), [user, session, isSubmitting]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider.');
  }
  return context;
}
