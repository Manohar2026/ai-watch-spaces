import { useState } from 'react';
import { ApiError } from '../auth/api';
import { useAuth } from '../auth/AuthProvider';
import { formatDuration } from '../catalog/catalogApi';
import { watchSpaceApi, type WatchSpace } from './watchSpaceApi';

interface WatchSpacePageProps {
  initialRoom: WatchSpace;
  inviteCode?: string;
  onExit(): void;
  onRoomChange(room: WatchSpace): void;
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.correlationId ? `${error.message} (Reference: ${error.correlationId})` : error.message;
  }
  return 'The request could not be completed. Check your connection and try again.';
}

function statusLabel(status: WatchSpace['status']): string {
  switch (status) {
    case 'SCHEDULED': return 'Waiting to start';
    case 'LIVE': return 'Room is live';
    case 'HOST_DISCONNECTED': return 'Host reconnecting';
    case 'ENDED': return 'Room ended';
  }
}

export function WatchSpacePage({ initialRoom, inviteCode, onExit, onRoomChange }: WatchSpacePageProps) {
  const { getAccessToken, refreshAccessToken, logout } = useAuth();
  const [room, setRoom] = useState(initialRoom);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const isHost = room.myRole === 'HOST';

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
      if (!token) throw requestError;
      return operation(token);
    }
  }

  async function runRoomAction(action: (token: string) => Promise<WatchSpace>, exitAfter = false) {
    setPending(true);
    setError(null);
    try {
      const updated = await authorized(action);
      setRoom(updated);
      onRoomChange(updated);
      if (updated.status === 'ENDED' || exitAfter) onExit();
    } catch (actionError) {
      setError(errorMessage(actionError));
      if (actionError instanceof ApiError && actionError.status === 401) {
        await logout();
      }
    } finally {
      setPending(false);
    }
  }

  async function refreshRoom() {
    await runRoomAction((token) => watchSpaceApi.get(token, room.watchSpaceId));
  }

  async function copyInvite() {
    if (!inviteCode) return;
    try {
      await navigator.clipboard.writeText(inviteCode);
      setCopyState('copied');
    } catch {
      setCopyState('failed');
    }
    window.setTimeout(() => setCopyState('idle'), 2500);
  }

  return (
    <main className="room-shell">
      <header className="room-topbar">
        <a className="brand" href="/" aria-label="Watch Spaces home">
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </a>
        <div className="room-top-actions">
          <button className="catalog-back-button" type="button" onClick={() => void refreshRoom()} disabled={pending}>Refresh room</button>
          <button className="dashboard-logout" type="button" onClick={onExit}>Back to catalogue</button>
        </div>
      </header>

      <section className="room-content" aria-labelledby="room-title">
        <div className="room-heading">
          <div>
            <p className="eyebrow"><span className={`status-dot ${room.status === 'LIVE' ? 'room-live-dot' : ''}`} /> {statusLabel(room.status).toUpperCase()}</p>
            <h1 id="room-title">{room.name}</h1>
            <p className="room-subtitle">Watching <strong>{room.titleName}</strong> · {formatDuration(room.durationMs)}</p>
          </div>
          <span className="room-role-badge">{room.myRole}</span>
        </div>

        {error && <p className="room-page-error" role="alert">{error}</p>}

        <div className="room-grid">
          <section className="room-main-card" aria-labelledby="lobby-title">
            <div className="room-art" aria-hidden="true">
              <div className="room-art-sun" />
              <div className="room-art-horizon" />
              <span className="room-art-label">{room.status === 'LIVE' ? 'THE ROOM IS READY' : 'YOUR NEXT SHARED STORY'}</span>
            </div>
            <div className="room-main-body">
              <p className="eyebrow">{room.status === 'LIVE' ? 'LIVE ROOM' : 'ROOM LOBBY'}</p>
              <h2 id="lobby-title">{room.status === 'LIVE' ? 'Everyone can join the room.' : 'Invite your people.'}</h2>
              <p>
                {room.status === 'ENDED'
                  ? 'This room has ended. Return to the catalogue to start another watch space.'
                  : 'Share the invite code with friends. Room membership is saved, and the watch room will remember who has joined.'}
              </p>
              {room.status === 'LIVE' && (
                <div className="room-next-step">
                  <span className="room-next-icon" aria-hidden="true">◷</span>
                  <p>Playback synchronization, live chat, and online presence are the next room features.</p>
                </div>
              )}
              <div className="room-actions">
                {isHost && room.status === 'SCHEDULED' && (
                  <button className="primary-button" type="button" disabled={pending} onClick={() => void runRoomAction((token) => watchSpaceApi.start(token, room.watchSpaceId))}>
                    {pending ? 'Starting…' : 'Start the room'} <span aria-hidden="true">→</span>
                  </button>
                )}
                {isHost && room.status !== 'ENDED' && (
                  <button className="room-secondary-action" type="button" disabled={pending} onClick={() => void runRoomAction((token) => watchSpaceApi.end(token, room.watchSpaceId))}>End room</button>
                )}
                {!isHost && room.status !== 'ENDED' && (
                  <button className="room-secondary-action" type="button" disabled={pending} onClick={() => void runRoomAction((token) => watchSpaceApi.leave(token, room.watchSpaceId), true)}>Leave room</button>
                )}
              </div>
            </div>
          </section>

          <aside className="room-side-column">
            <section className="room-panel" aria-labelledby="people-title">
              <div className="room-panel-heading">
                <div><p className="eyebrow">THE GROUP</p><h2 id="people-title">People here</h2></div>
                <span className="room-people-count">{room.participantCount}/{room.maxParticipants}</span>
              </div>
              <ul className="room-people-list">
                {room.participants.map((person, index) => (
                  <li key={person.userId}>
                    <span className={`room-avatar room-avatar-${index % 4}`}>{person.displayName.slice(0, 1).toUpperCase()}</span>
                    <span className="room-person-name">{person.displayName}</span>
                    <span className="room-person-role">{person.roomRole === 'HOST' ? 'HOST' : 'MEMBER'}</span>
                  </li>
                ))}
                {room.participants.length === 0 && <li className="room-empty-people">No active participants.</li>}
              </ul>
            </section>

            {inviteCode && (
              <section className="room-panel invite-panel" aria-labelledby="invite-title">
                <p className="eyebrow">ROOM INVITE</p>
                <h2 id="invite-title">Bring someone along.</h2>
                <p>Share this invite code with friends so they can join your room.</p>
                <div className="invite-code-box"><code>{inviteCode}</code><button type="button" onClick={() => void copyInvite()} aria-label="Copy invite code">{copyState === 'copied' ? 'Copied' : 'Copy'}</button></div>
                {copyState === 'failed' && <p className="room-action-error" role="status">Select and copy the code manually.</p>}
              </section>
            )}

            <section className="room-panel room-details-panel" aria-label="Room details">
              <div><span>Status</span><strong>{statusLabel(room.status)}</strong></div>
              <div><span>Access</span><strong>{room.privacy === 'INVITE_ONLY' ? 'Invite only' : 'Link access'}</strong></div>
              <div><span>Room code</span><strong>{room.watchSpaceId.slice(0, 8).toUpperCase()}</strong></div>
            </section>
          </aside>
        </div>
      </section>
    </main>
  );
}

