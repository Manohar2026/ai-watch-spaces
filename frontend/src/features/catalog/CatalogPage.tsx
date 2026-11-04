import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { ApiError } from '../auth/api';
import { useAuth } from '../auth/AuthProvider';
import { catalogApi, formatDuration, type PageResponse, type TitleDetail, type TitleSummary } from './catalogApi';

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

export function CatalogPage({ onBack }: CatalogPageProps) {
  const { getAccessToken, refreshAccessToken, logout } = useAuth();
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [genre, setGenre] = useState('');
  const [page, setPage] = useState(0);
  const [reloadVersion, setReloadVersion] = useState(0);
  const [result, setResult] = useState<PageResponse<TitleSummary> | null>(null);
  const [state, setState] = useState<LoadState>('loading');
  const [error, setError] = useState<string | null>(null);
  const [selectedTitleId, setSelectedTitleId] = useState<string | null>(null);
  const [selectedTitle, setSelectedTitle] = useState<TitleDetail | null>(null);
  const [detailState, setDetailState] = useState<LoadState>('ready');
  const [detailError, setDetailError] = useState<string | null>(null);

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
          genre,
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
  }, [page, appliedQuery, genre, reloadVersion, getAccessToken, refreshAccessToken]);

  const availableGenres = useMemo(
    () => [...new Set(result?.items.flatMap((title) => title.genres) ?? [])].sort(),
    [result],
  );

  async function openTitle(titleId: string) {
    setSelectedTitleId(titleId);
    setSelectedTitle(null);
    setDetailError(null);
    setDetailState('loading');
    try {
      const detail = await authorized((token) => catalogApi.get(token, titleId));
      setSelectedTitle(detail);
      setDetailState('ready');
    } catch (detailLoadError) {
      setDetailError(friendlyError(detailLoadError));
      setDetailState('error');
    }
  }

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setAppliedQuery(query.trim());
  }

  function clearFilters() {
    setQuery('');
    setAppliedQuery('');
    setGenre('');
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
            <select value={genre} onChange={(event) => { setGenre(event.target.value); setPage(0); }}>
              <option value="">All genres</option>
              {availableGenres.map((item) => <option value={item} key={item}>{item}</option>)}
            </select>
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
          if (event.target === event.currentTarget) setSelectedTitleId(null);
        }}>
          <section className="title-detail-panel" role="dialog" aria-modal="true" aria-label="Title details">
            <button className="detail-close" type="button" onClick={() => setSelectedTitleId(null)} aria-label="Close title details">×</button>
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
                <p className="detail-note">This title is available in the catalogue. Watch-space creation and synchronized playback will be added in a later milestone.</p>
              </>
            )}
          </section>
        </div>
      )}
    </main>
  );
}
