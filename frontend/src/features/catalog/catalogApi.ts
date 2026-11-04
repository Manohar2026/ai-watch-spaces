import { authenticatedGet } from '../auth/api';

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
}

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

export interface CatalogFilters {
  page: number;
  size: number;
  query?: string;
  genre?: string;
}

function buildQuery(filters: CatalogFilters): string {
  const params = new URLSearchParams({
    page: String(filters.page),
    size: String(filters.size),
  });
  if (filters.query?.trim()) {
    params.set('query', filters.query.trim());
  }
  if (filters.genre?.trim()) {
    params.set('genre', filters.genre.trim());
  }
  return params.toString();
}

export const catalogApi = {
  list(accessToken: string, filters: CatalogFilters): Promise<PageResponse<TitleSummary>> {
    return authenticatedGet<PageResponse<TitleSummary>>(
      `/titles?${buildQuery(filters)}`,
      accessToken,
    );
  },

  get(accessToken: string, titleId: string): Promise<TitleDetail> {
    return authenticatedGet<TitleDetail>(`/titles/${encodeURIComponent(titleId)}`, accessToken);
  },

  getTimeline(accessToken: string, titleId: string, fromSeconds: number, toSeconds: number): Promise<TimelineResponse> {
    const params = new URLSearchParams({ from: String(fromSeconds), to: String(toSeconds) });
    return authenticatedGet<TimelineResponse>(
      `/titles/${encodeURIComponent(titleId)}/timeline?${params.toString()}`,
      accessToken,
    );
  },

  getSubtitleTrack(accessToken: string, titleId: string, locale?: string): Promise<SubtitleTrack> {
    const suffix = locale ? `?locale=${encodeURIComponent(locale)}` : '';
    return authenticatedGet<SubtitleTrack>(
      `/titles/${encodeURIComponent(titleId)}/subtitles${suffix}`,
      accessToken,
    );
  },
};

export function formatDuration(durationMs: number): string {
  const totalSeconds = Math.floor(durationMs / 1_000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes > 0 ? `${minutes} min${seconds > 0 ? ` ${seconds} sec` : ''}` : `${seconds} sec`;
}
