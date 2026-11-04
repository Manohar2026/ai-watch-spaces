const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');

export interface TitleSummary {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  durationMs: number;
  videoAssetUri: string;
  defaultLocale: string;
  genres: string[];
}

export interface TitleDetail extends TitleSummary {
  createdAt: string;
  updatedAt: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
}

export interface TimelineOption {
  id: string;
  label: string;
}

export interface TimelineEvent {
  eventId: string;
  eventKey: string;
  type: string;
  ts: number;
  startMs: number;
  endMs: number | null;
  title?: string;
  summary?: string;
  text?: string;
  term?: string;
  definition?: string;
  options?: TimelineOption[];
  [key: string]: unknown;
}

export interface TimelineResponse {
  titleId: string;
  timelineVersionId: string;
  events: TimelineEvent[];
}

export interface SubtitleTrack {
  trackId: string;
  locale: string;
  versionNumber: number;
  assetUri: string;
  checksum: string;
}

export class CatalogApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
  ) {
    super(message);
    this.name = 'CatalogApiError';
  }
}

async function request<T>(path: string, accessToken: string): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'GET',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: 'same-origin',
  });

  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const errorBody = isRecord(body) ? body : {};
    throw new CatalogApiError(
      typeof errorBody.message === 'string'
        ? errorBody.message
        : `The catalogue request failed (HTTP ${response.status}).`,
      response.status,
      typeof errorBody.code === 'string' ? errorBody.code : 'UNKNOWN_ERROR',
    );
  }

  return body as T;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

export interface CatalogFilters {
  page: number;
  size: number;
  query?: string;
  genre?: string;
}

export const catalogApi = {
  list(accessToken: string, filters: CatalogFilters): Promise<PageResponse<TitleSummary>> {
    const params = new URLSearchParams({
      page: String(filters.page),
      size: String(filters.size),
    });
    if (filters.query?.trim()) {
      params.set('query', filters.query.trim());
    }
    if (filters.genre) {
      params.set('genre', filters.genre);
    }
    return request<PageResponse<TitleSummary>>(`/titles?${params.toString()}`, accessToken);
  },

  getTitle(accessToken: string, titleId: string): Promise<TitleDetail> {
    return request<TitleDetail>(`/titles/${encodeURIComponent(titleId)}`, accessToken);
  },

  getTimeline(
    accessToken: string,
    titleId: string,
    fromSeconds: number,
    toSeconds: number,
  ): Promise<TimelineResponse> {
    const params = new URLSearchParams({
      from: String(fromSeconds),
      to: String(toSeconds),
    });
    return request<TimelineResponse>(
      `/titles/${encodeURIComponent(titleId)}/timeline?${params.toString()}`,
      accessToken,
    );
  },

  getSubtitleTrack(
    accessToken: string,
    titleId: string,
    locale?: string,
  ): Promise<SubtitleTrack> {
    const params = locale ? `?locale=${encodeURIComponent(locale)}` : '';
    return request<SubtitleTrack>(
      `/titles/${encodeURIComponent(titleId)}/subtitles${params}`,
      accessToken,
    );
  },
};
