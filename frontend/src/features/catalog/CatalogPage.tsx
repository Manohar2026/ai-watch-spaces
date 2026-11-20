import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../auth/api';
import { useAuth } from '../auth/AuthProvider';
import { catalogApi, formatDuration, type PageResponse, type TitleDetail, type TitleSummary } from './catalogApi';
import { watchSpaceApi, type WatchSpace } from '../watchspace/watchSpaceApi';

const PAGE_SIZE = 12;

interface CatalogPageProps {
  onBack(): void;
  onRoomEnter(room: WatchSpace, inviteCode?: string): void;
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

export function CatalogPage({ onBack, onRoomEnter }: CatalogPageProps) {
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
  const [joinOpen, setJoinOpen] = useState(false);
  const [inviteCode, setInviteCode] = useState('');
  const [joinError, setJoinError] = useState<string | null>(null);
  const [roomError, setRoomError] = useState<string | null>(null);
  const [roomPending, setRoomPending] = useState(false);
  const [createTitleOpen, setCreateTitleOpen] = useState(false);
  const [createTitlePending, setCreateTitlePending] = useState(false);
  const [createTitleError, setCreateTitleError] = useState<string | null>(null);
  const [catalogNotice, setCatalogNotice] = useState<string | null>(null);
  const [titleDraft, setTitleDraft] = useState({
    name: '',
    description: '',
    videoAssetUri: '',
    durationSeconds: '',
    genres: '',
  });

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

  async function openTitle(titleId: string) {
    setSelectedTitleId(titleId);
    setRoomError(null);
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

  async function createWatchSpace(titleId: string) {
    setRoomPending(true);
    setRoomError(null);
    try {
      const created = await authorized((token) => watchSpaceApi.create(token, titleId));
      if (!created?.room?.watchSpaceId) {
        throw new ApiError(502, {
          code: 'INVALID_ROOM_RESPONSE',
          message: 'The backend did not return the new room. Update and restart the backend, then try again.',
        });
      }
      onRoomEnter(created.room, created.inviteCode);
    } catch (createError) {
      setRoomError(friendlyError(createError));
    } finally {
      setRoomPending(false);
    }
  }

  async function createCatalogTitle(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreateTitleError(null);
    setCatalogNotice(null);
    const durationSeconds = Number(titleDraft.durationSeconds);
    if (!Number.isInteger(durationSeconds) || durationSeconds < 1 || durationSeconds > 86400) {
      setCreateTitleError('Enter a duration from 1 second to 24 hours.');
      return;
    }
    setCreateTitlePending(true);
    try {
      const title = await authorized((token) => catalogApi.create(token, {
        name: titleDraft.name,
        description: titleDraft.description,
        videoAssetUri: titleDraft.videoAssetUri,
        durationSeconds,
        genres: titleDraft.genres.split(',').map((item) => item.trim()).filter(Boolean),
      }));
      setCreateTitleOpen(false);
      setTitleDraft({ name: '', description: '', videoAssetUri: '', durationSeconds: '', genres: '' });
      setQuery('');
      setAppliedQuery('');
      setGenre('');
      setPage(0);
      setReloadVersion((current) => current + 1);
      setCatalogNotice(`“${title.name}” was added to the catalogue.`);
    } catch (createError) {
      setCreateTitleError(friendlyError(createError));
      if (createError instanceof ApiError && createError.status === 401) await logout();
    } finally {
      setCreateTitlePending(false);
    }
  }

  async function joinWatchSpace(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setJoinError(null);
    setRoomPending(true);
    try {
      const room = await authorized((token) => watchSpaceApi.joinByCode(token, inviteCode.trim()));
      setJoinOpen(false);
      setInviteCode('');
      onRoomEnter(room);
    } catch (joinRequestError) {
      setJoinError(friendlyError(joinRequestError));
    } finally {
      setRoomPending(false);
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
        <a className="brand" href="/" aria-label="Watch Spaces home">
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </a>
        <div className="catalog-top-actions">
          <button className="catalog-back-button catalog-join-button" type="button" onClick={() => { setJoinError(null); setJoinOpen(true); }}>Join a space</button>
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
          <div className="catalog-heading-actions">
            {result && <span className="catalog-count">{result.totalElements} {result.totalElements === 1 ? 'title' : 'titles'}</span>}
            <button className="primary-button catalog-add-title" type="button" onClick={() => { setCreateTitleError(null); setCreateTitleOpen(true); }}>+ Add a title</button>
          </div>
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
              onChange={(event) => { setGenre(event.target.value); setPage(0); }}
              maxLength={80}
              placeholder="Genre (optional)"
            />
          </label>
          <button className="primary-button catalog-search-button" type="submit">Search <span aria-hidden="true">→</span></button>
          {(appliedQuery || genre) && <button className="catalog-clear-button" type="button" onClick={clearFilters}>Clear</button>}
        </form>

        {roomError && <p className="room-action-error catalog-room-error" role="alert">{roomError}</p>}
        {catalogNotice && <p className="catalog-notice" role="status">{catalogNotice}</p>}

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
                    </div>
                    <p>{title.description || 'A story waiting to be explored together.'}</p>
                    <div className="title-genres">
                      {title.genres.map((item) => <span key={item}>{item}</span>)}
                    </div>
                    <div className="title-card-actions">
                      <button className="primary-button catalog-room-create" type="button" disabled={roomPending} onClick={() => void createWatchSpace(title.id)}>
                        {roomPending ? 'Creating room…' : 'Create room'} <span aria-hidden="true">→</span>
                      </button>
                      <button className="title-detail-button" type="button" onClick={() => void openTitle(title.id)}>
                        View details <span aria-hidden="true">↗</span>
                      </button>
                    </div>
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
                  <div><dt>Slug</dt><dd>{selectedTitle.slug}</dd></div>
                  <div><dt>Genres</dt><dd>{selectedTitle.genres.join(', ') || 'Not specified'}</dd></div>
                </dl>
                <div className="room-create-action">
                  {roomError && <p className="room-action-error" role="alert">{roomError}</p>}
                  <button className="primary-button catalog-room-create" type="button" disabled={roomPending} onClick={() => void createWatchSpace(selectedTitle.id)}>
                    {roomPending ? 'Creating your space…' : 'Create a watch space'} <span aria-hidden="true">→</span>
                  </button>
                </div>
                {(() => {
                  try {
                    const mediaUrl = new URL(selectedTitle.videoAssetUri);
                    return (mediaUrl.protocol === 'https:' || mediaUrl.protocol === 'http:') ? (
                      <div className="sample-player-wrap">
                        <video className="sample-player" controls playsInline preload="metadata" aria-label={`Preview ${selectedTitle.name}`}>
                          <source src={mediaUrl.href} type="video/mp4" />
                          Your browser does not support this video. <a href={mediaUrl.href}>Open the sample clip</a>.
                        </video>
                        <p className="detail-note">Create a room to invite friends and watch this title together.</p>
                      </div>
                    ) : null;
                  } catch {
                    return null;
                  }
                })()}
              </>
            )}
          </section>
        </div>
      )}

      {joinOpen && (
        <div className="detail-overlay" role="presentation" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setJoinOpen(false);
        }}>
          <section className="title-detail-panel join-space-panel" role="dialog" aria-modal="true" aria-labelledby="join-space-title">
            <button className="detail-close" type="button" onClick={() => setJoinOpen(false)} aria-label="Close join form">×</button>
            <p className="eyebrow">YOUR FRIENDS ARE WAITING</p>
            <h2 id="join-space-title">Join a watch space.</h2>
            <p className="detail-description">Enter the invite code shared by the host to join their room.</p>
            <form className="join-space-form" onSubmit={(event) => void joinWatchSpace(event)}>
              <label htmlFor="watch-space-code">Invite code</label>
              <input id="watch-space-code" autoComplete="off" autoCapitalize="characters" maxLength={11} required value={inviteCode} onChange={(event) => setInviteCode(event.target.value.toUpperCase())} placeholder="XXXXX-XXXXX" />
              {joinError && <p className="room-action-error" role="alert">{joinError}</p>}
              <button className="primary-button" type="submit" disabled={roomPending || inviteCode.trim().length < 10}>
                {roomPending ? 'Joining…' : 'Join space'} <span aria-hidden="true">→</span>
              </button>
            </form>
          </section>
        </div>
      )}

      {createTitleOpen && (
        <div className="detail-overlay" role="presentation" onMouseDown={(event) => {
          if (event.target === event.currentTarget && !createTitlePending) setCreateTitleOpen(false);
        }}>
          <section className="title-detail-panel add-title-panel" role="dialog" aria-modal="true" aria-labelledby="add-title-heading">
            <button className="detail-close" type="button" onClick={() => setCreateTitleOpen(false)} aria-label="Close add title form">×</button>
            <p className="eyebrow">GROW THE CATALOGUE</p>
            <h2 id="add-title-heading">Add a title.</h2>
            <p className="detail-description">Add a direct video URL and basic details. The title will be available to everyone using this catalogue.</p>
            <form className="add-title-form" onSubmit={(event) => void createCatalogTitle(event)}>
              <label>Title name<input required maxLength={200} value={titleDraft.name} onChange={(event) => { const value = event.currentTarget.value; setTitleDraft((current) => ({ ...current, name: value })); }} placeholder="e.g. The First Bloom" /></label>
              <label>Description<textarea maxLength={5000} rows={3} value={titleDraft.description} onChange={(event) => { const value = event.currentTarget.value; setTitleDraft((current) => ({ ...current, description: value })); }} placeholder="What is this video about?" /></label>
              <label>Direct video URL<input required type="url" maxLength={2000} value={titleDraft.videoAssetUri} onChange={(event) => { const value = event.currentTarget.value; setTitleDraft((current) => ({ ...current, videoAssetUri: value })); }} placeholder="https://example.com/video.mp4" /></label>
              <div className="add-title-form-row">
                <label>Duration (seconds)<input required type="number" min={1} max={86400} step={1} value={titleDraft.durationSeconds} onChange={(event) => { const value = event.currentTarget.value; setTitleDraft((current) => ({ ...current, durationSeconds: value })); }} placeholder="120" /></label>
                <label>Genres<input maxLength={800} value={titleDraft.genres} onChange={(event) => { const value = event.currentTarget.value; setTitleDraft((current) => ({ ...current, genres: value })); }} placeholder="nature, comedy" /></label>
              </div>
              {createTitleError && <p className="room-action-error" role="alert">{createTitleError}</p>}
              <button className="primary-button" type="submit" disabled={createTitlePending}>{createTitlePending ? 'Adding title…' : 'Add to catalogue'} <span aria-hidden="true">→</span></button>
            </form>
          </section>
        </div>
      )}
    </main>
  );
}

