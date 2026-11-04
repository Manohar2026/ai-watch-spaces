import { useEffect, useMemo, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import {
  CatalogApiError,
  catalogApi,
  type PageResponse,
  type SubtitleTrack,
  type TimelineEvent,
  type TimelineResponse,
  type TitleDetail,
  type TitleSummary,
} from './api';

const PAGE_SIZE = 12;

function durationLabel(durationMs: number): string {
  const totalSeconds = Math.max(1, Math.ceil(durationMs / 1000));
  if (totalSeconds < 60) {
    return `${totalSeconds} sec`;
  }
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}h ${String(minutes).padStart(2, '0')}m`;
  }
  return seconds > 0 ? `${minutes}m ${seconds}s` : `${minutes} min`;
}

function timeLabel(seconds: number): string {
  const safeSeconds = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(safeSeconds / 60);
  const remainder = safeSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

function textValue(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null;
}

function eventHeading(event: TimelineEvent): string {
  if (event.type === 'SCENE') {
    return textValue(event.title) || 'Scene';
  }
  if (event.type === 'TRIVIA') {
    return 'Trivia';
  }
  if (event.type === 'GLOSSARY') {
    return textValue(event.term) || 'Glossary';
  }
  if (event.type === 'VARIATION_POINT') {
    return 'Subtitle variation';
  }
  return event.type.replaceAll('_', ' ').toLowerCase();
}

function eventDescription(event: TimelineEvent): string | null {
  if (event.type === 'SCENE') {
    return textValue(event.summary);
  }
  if (event.type === 'TRIVIA') {
    return textValue(event.text);
  }
  if (event.type === 'GLOSSARY') {
    return textValue(event.definition);
  }
  return null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.';
}

export function Catalog() {
  const { getAccessToken, refreshAccessToken } = useAuth();
  const [searchText, setSearchText] = useState('');
  const [genre, setGenre] = useState('');
  const [page, setPage] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);
  const [result, setResult] = useState<PageResponse<TitleSummary> | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [listError, setListError] = useState<string | null>(null);

  const [selectedTitle, setSelectedTitle] = useState<TitleSummary | null>(null);
  const [detail, setDetail] = useState<TitleDetail | null>(null);
  const [timeline, setTimeline] = useState<TimelineResponse | null>(null);
  const [subtitle, setSubtitle] = useState<SubtitleTrack | null>(null);
  const [isDetailLoading, setIsDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [timelineError, setTimelineError] = useState<string | null>(null);
  const [subtitleError, setSubtitleError] = useState<string | null>(null);

  async function withAccessToken<T>(operation: (token: string) => Promise<T>): Promise<T> {
    const token = getAccessToken();
    if (!token) {
      throw new Error('Your session has expired. Please sign in again.');
    }
    try {
      return await operation(token);
    } catch (error) {
      if (!(error instanceof CatalogApiError) || error.status !== 401) {
        throw error;
      }
      await refreshAccessToken();
      const renewedToken = getAccessToken();
      if (!renewedToken) {
        throw error;
      }
      return operation(renewedToken);
    }
  }

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      setIsLoading(true);
      setListError(null);
      void withAccessToken((token) => catalogApi.list(token, {
        page,
        size: PAGE_SIZE,
        query: searchText,
        genre: genre || undefined,
      })).then((response) => {
        if (active) {
          setResult(response);
        }
      }).catch((error: unknown) => {
        if (active) {
          setListError(errorMessage(error));
        }
      }).finally(() => {
        if (active) {
          setIsLoading(false);
        }
      });
    }, searchText.trim() ? 250 : 0);

    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [page, searchText, genre, reloadKey]);

  useEffect(() => {
    if (!selectedTitle) {
      setDetail(null);
      setTimeline(null);
      setSubtitle(null);
      setDetailError(null);
      setTimelineError(null);
      setSubtitleError(null);
      return;
    }

    let active = true;
    const title = selectedTitle;
    const endSeconds = Math.min(Math.ceil(title.durationMs / 1000), 3_600);
    setIsDetailLoading(true);
    setDetail(null);
    setTimeline(null);
    setSubtitle(null);
    setDetailError(null);
    setTimelineError(null);
    setSubtitleError(null);

    void Promise.allSettled([
      withAccessToken((token) => catalogApi.getTitle(token, title.id)),
      withAccessToken((token) => catalogApi.getTimeline(token, title.id, 0, endSeconds)),
      withAccessToken((token) => catalogApi.getSubtitleTrack(token, title.id, title.defaultLocale)),
    ]).then(([detailResult, timelineResult, subtitleResult]) => {
      if (!active) {
        return;
      }
      if (detailResult.status === 'fulfilled') {
        setDetail(detailResult.value);
      } else {
        setDetailError(errorMessage(detailResult.reason));
      }
      if (timelineResult.status === 'fulfilled') {
        setTimeline(timelineResult.value);
      } else {
        setTimelineError(errorMessage(timelineResult.reason));
      }
      if (subtitleResult.status === 'fulfilled') {
        setSubtitle(subtitleResult.value);
      } else {
        setSubtitleError(errorMessage(subtitleResult.reason));
      }
    }).finally(() => {
      if (active) {
        setIsDetailLoading(false);
      }
    });

    return () => {
      active = false;
    };
  }, [selectedTitle?.id]);

  const availableGenres = useMemo(() => {
    const values = new Set((result?.items || []).flatMap((title) => title.genres));
    if (genre) {
      values.add(genre);
    }
    return [...values].sort((left, right) => left.localeCompare(right));
  }, [result?.items, genre]);

  const totalPages = Math.max(1, Math.ceil((result?.totalElements || 0) / PAGE_SIZE));

  return (
    <section className="catalog-section" aria-labelledby="catalog-heading">
      <div className="catalog-section-heading">
        <div>
          <p className="eyebrow">THE LIBRARY</p>
          <h2 id="catalog-heading">Explore the catalogue.</h2>
          <p className="catalog-section-lead">Find a story, explore its moments, and see the details that make it worth sharing.</p>
        </div>
        <span className="catalog-count">{result?.totalElements ?? '—'} TITLES</span>
      </div>

      <div className="catalog-toolbar">
        <label className="catalog-search">
          <span aria-hidden="true">⌕</span>
          <input
            type="search"
            value={searchText}
            onChange={(event) => {
              setSearchText(event.target.value);
              setPage(0);
            }}
            placeholder="Search titles or descriptions"
            aria-label="Search titles or descriptions"
          />
        </label>
        <label className="catalog-genre-select">
          <span>GENRE</span>
          <select
            value={genre}
            onChange={(event) => {
              setGenre(event.target.value);
              setPage(0);
            }}
            aria-label="Filter by genre"
          >
            <option value="">All genres</option>
            {availableGenres.map((value) => (
              <option key={value} value={value}>{value}</option>
            ))}
          </select>
        </label>
        <button className="catalog-reset" type="button" onClick={() => {
          setSearchText('');
          setGenre('');
          setPage(0);
        }}>
          Clear filters
        </button>
      </div>

      {listError && (
        <div className="catalog-alert" role="alert">
          <span>{listError}</span>
          <button type="button" onClick={() => setReloadKey((value) => value + 1)}>Retry</button>
        </div>
      )}

      {isLoading ? (
        <div className="catalog-loading" role="status">Loading published titles…</div>
      ) : result?.items.length ? (
        <>
          <div className="catalog-grid">
            {result.items.map((title, index) => (
              <article className="catalog-card" key={title.id}>
                <button
                  className="catalog-card-button"
                  type="button"
                  onClick={() => setSelectedTitle(title)}
                  aria-label={`Open details for ${title.name}`}
                >
                  <div className={`catalog-art catalog-art-${index % 4}`}>
                    <span className="catalog-art-label">{title.genres[0] || 'FEATURED'}</span>
                    <span className="catalog-art-monogram" aria-hidden="true">{title.name.slice(0, 1).toUpperCase()}</span>
                    <span className="catalog-duration">{durationLabel(title.durationMs)}</span>
                  </div>
                  <div className="catalog-card-copy">
                    <div className="catalog-card-title-row">
                      <h3>{title.name}</h3>
                      <span aria-hidden="true">↗</span>
                    </div>
                    <p>{title.description || 'A story waiting to be explored.'}</p>
                    <div className="catalog-tags">
                      {title.genres.map((value) => <span key={value}>{value}</span>)}
                    </div>
                  </div>
                </button>
              </article>
            ))}
          </div>

          <div className="catalog-pagination">
            <span>Page {page + 1} of {totalPages}</span>
            <div>
              <button type="button" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>Previous</button>
              <button type="button" disabled={page + 1 >= totalPages} onClick={() => setPage((value) => value + 1)}>Next</button>
            </div>
          </div>
        </>
      ) : (
        <div className="catalog-empty">
          <span className="catalog-empty-mark">✦</span>
          <h3>{searchText || genre ? 'No titles match those filters.' : 'Your catalogue is ready for its first story.'}</h3>
          <p>
            {searchText || genre
              ? 'Try a different search or clear the filters to see all published titles.'
              : 'No published titles are available yet. For local development, the opt-in sample catalogue can be enabled using the steps in docs/m3-catalog-timeline.md.'}
          </p>
          {(searchText || genre) && <button type="button" onClick={() => {
            setSearchText('');
            setGenre('');
            setPage(0);
          }}>Clear filters</button>}
        </div>
      )}

      {selectedTitle && (
        <section className="catalog-detail" aria-labelledby="catalog-detail-heading">
          <div className="catalog-detail-topline">
            <div>
              <p className="eyebrow">TITLE EXPLORER</p>
              <h2 id="catalog-detail-heading">{detail?.name || selectedTitle.name}</h2>
            </div>
            <button className="catalog-detail-close" type="button" onClick={() => setSelectedTitle(null)}>Close <span aria-hidden="true">×</span></button>
          </div>

          {isDetailLoading && <p className="catalog-loading">Loading title details and timeline…</p>}
          {detailError && <p className="catalog-inline-error" role="alert">Title details: {detailError}</p>}
          <p className="catalog-detail-description">{detail?.description || selectedTitle.description || 'No description has been added for this title.'}</p>
          <div className="catalog-detail-meta">
            <span>{durationLabel(detail?.durationMs || selectedTitle.durationMs)}</span>
            <span>Default subtitles: {detail?.defaultLocale || selectedTitle.defaultLocale}</span>
            {(detail?.genres || selectedTitle.genres).map((value) => <span key={value}>{value}</span>)}
          </div>

          <div className="catalog-detail-columns">
            <section className="catalog-detail-block" aria-labelledby="timeline-heading">
              <div className="catalog-block-heading">
                <p className="eyebrow">BEHIND THE MOMENTS</p>
                <h3 id="timeline-heading">Story timeline</h3>
              </div>
              {timelineError ? (
                <p className="catalog-muted">{timelineError}</p>
              ) : timeline?.events.length ? (
                <ol className="timeline-list">
                  {timeline.events.map((event) => (
                    <li key={event.eventId}>
                      <span className="timeline-time">{timeLabel(event.ts)}</span>
                      <div className="timeline-event">
                        <span className="timeline-event-type">{event.type.replaceAll('_', ' ')}</span>
                        <h4>{eventHeading(event)}</h4>
                        {eventDescription(event) && <p>{eventDescription(event)}</p>}
                        {event.options && event.options.length > 0 && (
                          <div className="timeline-options">
                            <span>Approved alternatives</span>
                            {event.options.map((option) => <span key={option.id}>{option.label}</span>)}
                          </div>
                        )}
                      </div>
                    </li>
                  ))}
                </ol>
              ) : !isDetailLoading ? (
                <p className="catalog-muted">No timeline events are currently published for this title.</p>
              ) : null}
            </section>

            <aside className="catalog-detail-side">
              <div className="catalog-detail-block">
                <div className="catalog-block-heading">
                  <p className="eyebrow">LANGUAGE</p>
                  <h3>Subtitle track</h3>
                </div>
                {subtitle ? (
                  <>
                    <div className="subtitle-locale">{subtitle.locale}</div>
                    <p className="catalog-muted">Active track · version {subtitle.versionNumber}</p>
                    <p className="subtitle-asset">{subtitle.assetUri}</p>
                    <p className="subtitle-checksum">SHA-256 · {subtitle.checksum.slice(0, 20)}…</p>
                  </>
                ) : subtitleError ? (
                  <p className="catalog-muted">{subtitleError}</p>
                ) : (
                  <p className="catalog-muted">Loading subtitle metadata…</p>
                )}
                <p className="catalog-footnote">M3 provides track metadata. Subtitle file delivery is not part of this API yet.</p>
              </div>
              <div className="catalog-coming-next">
                <span>UP NEXT</span>
                <h3>Watch together.</h3>
                <p>Creating and joining live watch spaces is the next backend milestone.</p>
                <small>M4 · WATCH SPACE LIFECYCLE</small>
              </div>
            </aside>
          </div>
        </section>
      )}
    </section>
  );
}
