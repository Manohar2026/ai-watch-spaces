import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../auth/api';
import { useAuth } from '../auth/AuthProvider';
import { catalogApi, formatDuration, type PageResponse, type SubtitleTrack, type TimelineEvent, type TimelineResponse, type TitleDetail, type TitleSummary } from './catalogApi';

const PAGE_SIZE = 12;

interface CatalogPageProps {
  onBack(): void;
}

type LoadState = 'loading' | 'ready' | 'error';

function friendlyError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return 'Your session has expired. Please sign in again.';
    }
    if (error.status === 403) {
      return 'You do not have permission to view the catalogue.';
    }
    return error.correlationId
      ? `${error.message} (Reference: ${error.correlationId})`
      : error.message;
  }
  return 'The catalogue could not be loaded. Check your connection and try again.';
}

function formatTimelineTime(seconds: number): string {
  const value = Math.max(0, Math.floor(seconds));
  return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
}

function timelineEventTitle(event: TimelineEvent): string {
  if (event.type === 'SCENE') return event.title || 'Scene';
  if (event.type === 'TRIVIA') return 'Trivia';
  if (event.type === 'GLOSSARY') return event.term || 'Glossary';
  if (event.type === 'VARIATION_POINT') return 'Subtitle variation';
  return event.type.replaceAll('_', ' ').toLowerCase();
}

function timelineEventDescription(event: TimelineEvent): string | null {
  if (event.type === 'SCENE') return event.summary || null;
  if (event.type === 'TRIVIA') return event.text || null;
  if (event.type === 'GLOSSARY') return event.definition || null;
  return null;
}

export function CatalogPage({ onBack }: CatalogPageProps) {
  const { getAccessToken, refreshAccessToken, logout } = useAuth();
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [genre, setGenre] = useState('');
  const [appliedGenre, setAppliedGenre] = useState('');
  const [page, setPage] = useState(0);
  const [reloadVersion, setReloadVersion] = useState(0);
  const [result, setResult] = useState<PageResponse<TitleSummary> | null>(null);
  const [state, setState] = useState<LoadState>('loading');
  const [error, setError] = useState<string | null>(null);
  const [selectedTitleId, setSelectedTitleId] = useState<string | null>(null);
  const [selectedTitle, setSelectedTitle] = useState<TitleDetail | null>(null);
  const [detailState, setDetailState] = useState<LoadState>('ready');
  const [detailError, setDetailError] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<TimelineResponse | null>(null);
  const [timelineState, setTimelineState] = useState<LoadState>('ready');
  const [timelineError, setTimelineError] = useState<string | null>(null);
  const [subtitle, setSubtitle] = useState<SubtitleTrack | null>(null);
  const [subtitleState, setSubtitleState] = useState<LoadState>('ready');
  const [subtitleError, setSubtitleError] = useState<string | null>(null);
  const detailLoadId = useRef(0);

  async function authorized<T>(operation: (token: string) => Promise<T>): Promise<T> {
    let token = getAccessToken();
    if (!token) {
      throw new ApiError(401, { code: 'UNAUTHENTICATED', message: 'Your session has expired.' });
    }
    try {
      return await operation(token);
    } catch (requestError) {
      if (!(requestError instanceof ApiError) || requestError.status !== 401) {
        throw requestError;
      }
      await refreshAccessToken();
      token = getAccessToken();
      if (!token) {
        throw requestError;
      }
      return operation(token);
    }
  }

  useEffect(() => {
    let cancelled = false;

    async function load() {
      setState('loading');
      setError(null);
      try {
        const response = await authorized((token) => catalogApi.list(token, {
          page,
          size: PAGE_SIZE,
          query: appliedQuery,
          genre: appliedGenre,
        }));
        if (!cancelled) {
          setResult(response);
          setState('ready');
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(friendlyError(loadError));
          setState('error');
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [page, appliedQuery, appliedGenre, reloadVersion, getAccessToken, refreshAccessToken]);

  function closeTitleDetails() {
    detailLoadId.current += 1;
    setSelectedTitleId(null);
    setSelectedTitle(null);
    setTimeline(null);
    setSubtitle(null);
  }

  async function openTitle(titleId: string) {
    const loadId = ++detailLoadId.current;
    setSelectedTitleId(titleId);
    setSelectedTitle(null);
    setDetailError(null);
    setDetailState('loading');
    setTimeline(null);
    setTimelineState('loading');
    setTimelineError(null);
    setSubtitle(null);
    setSubtitleState('loading');
    setSubtitleError(null);

    let detail: TitleDetail;
    try {
      detail = await authorized((token) => catalogApi.get(token, titleId));
    } catch (detailLoadError) {
      if (loadId === detailLoadId.current) {
        setDetailError(friendlyError(detailLoadError));
        setDetailState('error');
      }
      return;
    }

    if (loadId !== detailLoadId.current) {
      return;
    }
    setSelectedTitle(detail);
    setDetailState('ready');

    // M3 uses seconds for range bounds and caps each request at one hour.
    const toSeconds = Math.min(Math.ceil(detail.durationMs / 1_000), 3_600);
    await Promise.all([
      authorized((token) => catalogApi.getTimeline(token, titleId, 0, toSeconds))
        .then((response) => {
          if (loadId === detailLoadId.current) {
            setTimeline(response);
            setTimelineState('ready');
          }
        })
        .catch((timelineLoadError: unknown) => {
          if (loadId === detailLoadId.current) {
            setTimelineError(friendlyError(timelineLoadError));
            setTimelineState('error');
          }
        }),
      authorized((token) => catalogApi.getSubtitleTrack(token, titleId, detail.defaultLocale))
        .then((response) => {
          if (loadId === detailLoadId.current) {
            setSubtitle(response);
            setSubtitleState('ready');
          }
        })
        .catch((subtitleLoadError: unknown) => {
          if (loadId === detailLoadId.current) {
            setSubtitleError(friendlyError(subtitleLoadError));
            setSubtitleState('error');
          }
        }),
    ]);
  }

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setAppliedQuery(query.trim());
    setAppliedGenre(genre.trim());
  }

  function clearFilters() {
    setQuery('');
    setAppliedQuery('');
    setGenre('');
    setAppliedGenre('');
    setPage(0);
  }

  const hasResults = Boolean(result && result.items.length > 0);
  const hasNextPage = Boolean(result && (page + 1) * result.size < result.totalElements);
  const hasPreviousPage = page > 0;

  return (
    <main className="catalog-shell">
      <header className="catalog-topbar">
        <a className="brand" href="/" aria-label="AI Watch Spaces home">
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </a>
        <div className="catalog-top-actions">
          <button className="catalog-back-button" type="button" onClick={onBack}>Dashboard</button>
          <button className="dashboard-logout" type="button" onClick={() => void logout()}>Sign out</button>
        </div>
      </header>

      <section className="catalog-content" aria-labelledby="catalog-title">
        <div className="catalog-heading">
          <div>
            <p className="eyebrow"><span className="status-dot" /> FIND YOUR NEXT STORY</p>
            <h1 id="catalog-title">Explore the <span>catalogue.</span></h1>
            <p>Browse the titles available in Watch Spaces and choose something to experience together.</p>
          </div>
          {result && <span className="catalog-count">{result.totalElements} {result.totalElements === 1 ? 'title' : 'titles'}</span>}
        </div>

        <form className="catalog-filters" onSubmit={handleSearch}>
          <label className="catalog-search">
            <span className="sr-only">Search titles</span>
            <span aria-hidden="true">⌕</span>
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search titles and descriptions"
              maxLength={120}
            />
          </label>
          <label className="catalog-genre">
            <span className="sr-only">Filter by genre</span>
            <input
              value={genre}
              onChange={(event) => setGenre(event.target.value)}
              maxLength={80}
              placeholder="Genre (optional)"
            />
          </label>
          <button className="primary-button catalog-search-button" type="submit">Search <span aria-hidden="true">→</span></button>
          {(appliedQuery || genre) && <button className="catalog-clear-button" type="button" onClick={clearFilters}>Clear</button>}
        </form>

        {state === 'loading' && (
          <div className="catalog-state" role="status">
            <span className="loading-orbit" aria-hidden="true" />
            <p>Finding stories for you…</p>
          </div>
        )}

        {state === 'error' && (
          <div className="catalog-state catalog-error" role="alert">
            <h2>We couldn't load the catalogue.</h2>
            <p>{error}</p>
            <button className="primary-button" type="button" onClick={() => setReloadVersion((current) => current + 1)}>Try again <span aria-hidden="true">↻</span></button>
            {error?.includes('session has expired') && <button className="catalog-clear-button" type="button" onClick={() => void logout()}>Sign out</button>}
          </div>
        )}

        {state === 'ready' && !hasResults && (
          <div className="catalog-state">
            <span className="empty-icon" aria-hidden="true">⌕</span>
            <h2>No titles found.</h2>
            <p>Try another search term or remove the genre filter.</p>
            <button className="catalog-clear-button" type="button" onClick={clearFilters}>Clear filters</button>
          </div>
        )}

        {state === 'ready' && hasResults && (
          <>
            <div className="title-grid">
              {result?.items.map((title, index) => (
                <article className="title-card" key={title.id}>
                  <div className={`title-art title-art-${index % 4}`}>
                    <span className="title-art-number">{String(page * PAGE_SIZE + index + 1).padStart(2, '0')}</span>
                    <span className="title-art-duration">{formatDuration(title.durationMs)}</span>
                    <div className="title-art-lines" />
                    <span className="title-art-name">{title.name}</span>
                  </div>
                  <div className="title-card-body">
                    <div className="title-card-heading">
                      <h2>{title.name}</h2>
                      <span className="title-locale">{title.defaultLocale}</span>
                    </div>
                    <p>{title.description || 'A story waiting to be explored together.'}</p>
                    <div className="title-genres">
                      {title.genres.map((item) => <span key={item}>{item}</span>)}
                    </div>
                    <button className="title-detail-button" type="button" onClick={() => void openTitle(title.id)}>
                      View details <span aria-hidden="true">↗</span>
                    </button>
                  </div>
                </article>
              ))}
            </div>
            <nav className="catalog-pagination" aria-label="Catalogue pages">
              <button className="catalog-page-button" type="button" disabled={!hasPreviousPage} onClick={() => setPage((current) => current - 1)}>← Previous</button>
              <span>Page {page + 1} of {Math.max(1, Math.ceil((result?.totalElements ?? 0) / PAGE_SIZE))}</span>
              <button className="catalog-page-button" type="button" disabled={!hasNextPage} onClick={() => setPage((current) => current + 1)}>Next →</button>
            </nav>
          </>
        )}
      </section>

      {selectedTitleId && (
        <div className="detail-overlay" role="presentation" onMouseDown={(event) => {
          if (event.target === event.currentTarget) closeTitleDetails();
        }}>
          <section className="title-detail-panel" role="dialog" aria-modal="true" aria-label="Title details">
            <button className="detail-close" type="button" onClick={closeTitleDetails} aria-label="Close title details">×</button>
            {detailState === 'loading' && <div className="catalog-state" role="status"><span className="loading-orbit" /><p>Loading title…</p></div>}
            {detailState === 'error' && <div className="catalog-state catalog-error" role="alert"><h2>Couldn't load title details.</h2><p>{detailError}</p></div>}
            {detailState === 'ready' && selectedTitle && (
              <>
                <p className="eyebrow">TITLE DETAILS</p>
                <h2 id="title-detail-heading">{selectedTitle.name}</h2>
                <p className="detail-description">{selectedTitle.description || 'No description has been added yet.'}</p>
                <dl className="detail-metadata">
                  <div><dt>Duration</dt><dd>{formatDuration(selectedTitle.durationMs)}</dd></div>
                  <div><dt>Default subtitles</dt><dd>{selectedTitle.defaultLocale}</dd></div>
                  <div><dt>Slug</dt><dd>{selectedTitle.slug}</dd></div>
                  <div><dt>Genres</dt><dd>{selectedTitle.genres.join(', ') || 'Not specified'}</dd></div>
                </dl>
                {(() => {
                  try {
                    const mediaUrl = new URL(selectedTitle.videoAssetUri);
                    return (mediaUrl.protocol === 'https:' || mediaUrl.protocol === 'http:') ? (
                      <a className="detail-asset-link" href={mediaUrl.href} target="_blank" rel="noreferrer">
                        Open sample media <span aria-hidden="true">↗</span>
                      </a>
                    ) : null;
                  } catch {
                    return null;
                  }
                })()}
                <p className="detail-note">This title is available in the catalogue. Watch-space creation and synchronized playback will be added in a later milestone. The media link, when present, opens externally; it is not an in-app player.</p>

                <div className="title-extras">
                  <section className="title-extra-card" aria-labelledby="timeline-title">
                    <p className="eyebrow">BEHIND THE MOMENTS</p>
                    <h3 id="timeline-title">Story timeline</h3>
                    {timelineState === 'loading' && <p className="extra-muted">Loading timeline events…</p>}
                    {timelineState === 'error' && <p className="extra-muted" role="status">{timelineError}</p>}
                    {timelineState === 'ready' && timeline?.events.length ? (
                      <ol className="detail-timeline">
                        {timeline.events.map((event) => (
                          <li key={event.eventId}>
                            <span className="detail-timeline-time">{formatTimelineTime(event.ts)}</span>
                            <div>
                              <span className="detail-event-type">{event.type.replaceAll('_', ' ')}</span>
                              <h4>{timelineEventTitle(event)}</h4>
                              {timelineEventDescription(event) && <p>{timelineEventDescription(event)}</p>}
                              {event.options && event.options.length > 0 && (
                                <div className="detail-variation-options">
                                  <span>Approved alternatives</span>
                                  {event.options.map((option) => <span key={option.id}>{option.label}</span>)}
                                </div>
                              )}
                            </div>
                          </li>
                        ))}
                      </ol>
                    ) : timelineState === 'ready' ? (
                      <p className="extra-muted">No timeline events are currently published.</p>
                    ) : null}
                    {selectedTitle.durationMs > 3_600_000 && (
                      <p className="extra-footnote">Showing the first 60 minutes. The API limits each timeline request to a 3,600-second range.</p>
                    )}
                  </section>

                  <section className="title-extra-card" aria-labelledby="subtitle-title">
                    <p className="eyebrow">LANGUAGE</p>
                    <h3 id="subtitle-title">Subtitle track</h3>
                    {subtitleState === 'loading' && <p className="extra-muted">Loading subtitle metadata…</p>}
                    {subtitleState === 'error' && <p className="extra-muted" role="status">{subtitleError}</p>}
                    {subtitleState === 'ready' && subtitle && (
                      <>
                        <span className="subtitle-track-locale">{subtitle.locale}</span>
                        <p className="extra-muted">Active track · version {subtitle.versionNumber}</p>
                        <p className="subtitle-track-asset">{subtitle.assetUri}</p>
                        <p className="subtitle-track-checksum">SHA-256 · {subtitle.checksum}</p>
                      </>
                    )}
                    <p className="extra-footnote">M3 returns active track metadata. Subtitle VTT file bytes are not served over HTTP yet.</p>
                  </section>
                </div>
              </>
            )}
          </section>
        </div>
      )}
    </main>
  );
}
