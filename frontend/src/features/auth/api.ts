const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');

export interface UserProfile {
  id: string;
  email: string;
  displayName: string;
  systemRole: 'USER' | 'ADMIN';
  subtitleLocale: string;
  createdAt: string;
  updatedAt: string;
}

export interface RegisterResponse {
  userId: string;
}

export interface TokenPair {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
}

export interface LoginResponse extends TokenPair {
  user: UserProfile;
}

export interface ApiErrorDetail {
  field: string | null;
  code: string;
  message: string;
}

interface ApiErrorResponse {
  code?: string;
  message?: string;
  correlationId?: string;
  details?: ApiErrorDetail[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly details: ApiErrorDetail[];

  constructor(status: number, body: ApiErrorResponse) {
    super(body.message || 'The request could not be completed.');
    this.name = 'ApiError';
    this.status = status;
    this.code = body.code || 'UNKNOWN_ERROR';
    this.correlationId = body.correlationId;
    this.details = body.details || [];
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  headers.set('Accept', 'application/json');

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers,
    credentials: 'same-origin',
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(response.status, isApiErrorResponse(body) ? body : {});
  }
  return body as T;
}

function isApiErrorResponse(value: unknown): value is ApiErrorResponse {
  return typeof value === 'object' && value !== null;
}

export const authApi = {
  register(email: string, password: string, displayName: string): Promise<RegisterResponse> {
    return request<RegisterResponse>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName }),
    });
  },

  login(email: string, password: string): Promise<LoginResponse> {
    return request<LoginResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
  },

  refresh(refreshToken: string): Promise<TokenPair> {
    return request<TokenPair>('/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    });
  },

  me(accessToken: string): Promise<UserProfile> {
    return request<UserProfile>('/me', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
  },

  logout(refreshToken: string): Promise<void> {
    return request<void>('/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    });
  },
};
