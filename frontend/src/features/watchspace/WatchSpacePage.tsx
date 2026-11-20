import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../auth/api';
import { useAuth } from '../auth/AuthProvider';
import { formatDuration } from '../catalog/catalogApi';
import { watchSpaceApi, type ChatMessage, type WatchSpace } from './watchSpaceApi';

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
  const [seekPosition, setSeekPosition] = useState(initialRoom.playbackPositionMs);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [chatDraft, setChatDraft] = useState('');
  const [chatPending, setChatPending] = useState(false);
  const [playbackBlocked, setPlaybackBlocked] = useState(false);
  const chatCursor = useRef(0);
  const videoRef = useRef<HTMLVideoElement>(null);
  const isHost = room.myRole === 'HOST';

  function applyRoom(updated: WatchSpace) {
    setRoom(updated);
    onRoomChange(updated);
    setSeekPosition(updated.playbackPositionMs);
    const video = videoRef.current;
    if (!video) return;
    if (updated.status !== 'LIVE') {
      video.pause();
      setPlaybackBlocked(false);
      return;
    }
    const elapsedMs = updated.playbackState === 'PLAYING'
      ? Math.max(0, Date.now() - Date.parse(updated.playbackUpdatedAt)) : 0;
    const targetSeconds = Math.min(updated.durationMs, updated.playbackPositionMs + elapsedMs) / 1000;
    if (Math.abs(video.currentTime - targetSeconds) > 1.25) video.currentTime = targetSeconds;
    if (updated.playbackState === 'PLAYING' && targetSeconds < updated.durationMs / 1000 - 0.25 && video.paused) {
      void video.play().then(() => setPlaybackBlocked(false)).catch(() => setPlaybackBlocked(true));
    }
    if (updated.playbackState === 'PAUSED' && !video.paused) video.pause();
  }

  useEffect(() => {
    if (room.status === 'ENDED') return;
    let inFlight = false;
    const timer = window.setInterval(async () => {
      if (inFlight || document.visibilityState === 'hidden' || room.status === 'ENDED') return;
      inFlight = true;
      try {
        const latest = await authorized((token) => watchSpaceApi.get(token, initialRoom.watchSpaceId));
        applyRoom(latest);
      } catch (pollError) {
        if (pollError instanceof ApiError && pollError.status === 401) await logout();
      } finally {
        inFlight = false;
      }
    }, 1200);
    return () => window.clearInterval(timer);
  }, [initialRoom.watchSpaceId, room.status]);

  useEffect(() => {
    if (room.status === 'ENDED') return;
    let inFlight = false;
    const heartbeat = async () => {
      if (inFlight || room.status === 'ENDED') return;
      inFlight = true;
      try {
        await authorized((token) => watchSpaceApi.heartbeat(token, initialRoom.watchSpaceId));
      } catch {
        // Keep presence polling alive; the next heartbeat retries.
      } finally {
        inFlight = false;
      }
    };
    heartbeat();
    const timer = window.setInterval(heartbeat, 10000);
    return () => window.clearInterval(timer);
  }, [initialRoom.watchSpaceId, room.status]);

  useEffect(() => {
    if (room.status === 'ENDED') return;
    let inFlight = false;
    const timer = window.setInterval(async () => {
      if (inFlight || document.visibilityState === 'hidden' || room.status === 'ENDED') return;
      inFlight = true;
      try {
        const fresh = await authorized((token) => watchSpaceApi.chatHistory(token, initialRoom.watchSpaceId, chatCursor.current));
        if (fresh.length) {
          chatCursor.current = Math.max(chatCursor.current, ...fresh.map((message) => message.sequenceNo));
          setMessages((current) => [...new Map<string, ChatMessage>([...current, ...fresh].map((message): [string, ChatMessage] => [message.id, message])).values()].slice(-50));
        }
      } catch {
        // Keep the room usable if chat is temporarily unavailable; the next poll retries.
      } finally {
        inFlight = false;
      }
    }, 1600);
    return () => window.clearInterval(timer);
  }, [initialRoom.watchSpaceId, room.status]);

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

  async function enableSynchronizedPlayback() {
    const video = videoRef.current;
    if (!video) return;
    try {
      await video.play();
      setPlaybackBlocked(false);
    } catch {
      setPlaybackBlocked(true);
    }
  }

  async function runRoomAction(action: (token: string) => Promise<WatchSpace>, exitAfter = false) {
    setPending(true);
    setError(null);
    try {
      const updated = await authorized(action);
      applyRoom(updated);
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

  async function sendChat(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = chatDraft.trim();
    if (!body || chatPending) return;
    setChatPending(true);
    setError(null);
    try {
      const message = await authorized((token) => watchSpaceApi.sendChat(token, room.watchSpaceId, body));
      chatCursor.current = Math.max(chatCursor.current, message.sequenceNo);
      setMessages((current) => [...new Map<string, ChatMessage>([...current, message].map((item): [string, ChatMessage] => [item.id, item])).values()].slice(-50));
      setChatDraft('');
    } catch (chatError) {
      setError(errorMessage(chatError));
      if (chatError instanceof ApiError && chatError.status === 401) await logout();
    } finally {
      setChatPending(false);
    }
  }

  return (
    <main className="room-shell">
      <header className="room-topbar">
        <a
          className="brand"
          href="/"
          aria-label="Leave room and return to catalogue"
          onClick={(event) => {
            event.preventDefault();
            if (room.status === 'ENDED') {
              onExit();
            } else {
              void runRoomAction((token) => watchSpaceApi.leave(token, room.watchSpaceId), true);
            }
          }}
        >
          <span className="brand-mark" aria-hidden="true">W</span>
          <span>watch<span className="brand-accent">spaces</span></span>
        </a>
        <div className="room-top-actions">
          <button className="catalog-back-button" type="button" onClick={() => void refreshRoom()} disabled={pending}>Refresh room</button>
          <button
            className="dashboard-logout"
            type="button"
            disabled={pending}
            onClick={() => {
              if (room.status === 'ENDED') {
                onExit();
              } else {
                void runRoomAction((token) => watchSpaceApi.leave(token, room.watchSpaceId), true);
              }
            }}
          >
            {room.status === 'ENDED' ? 'Back to catalogue' : isHost ? 'End & return' : 'Leave room'}
          </button>
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
            {room.status === 'LIVE' && (
              <div className="shared-player">
                <video
                  ref={videoRef}
                  className="shared-player-video"
                  src={room.videoAssetUri}
                  playsInline
                  preload="metadata"
                  onEnded={() => {
                    if (isHost && room.status === 'LIVE') {
                      void runRoomAction((token) =>
                        watchSpaceApi.playback(token, room.watchSpaceId, 'PAUSED', room.durationMs));
                    }
                  }}
                />
                <div className="shared-player-controls">
                  {isHost ? (
                    <>
                      <button
                        className="primary-button"
                        type="button"
                        disabled={pending}
                        onClick={() => {
                          const nextState = room.playbackState === 'PLAYING' ? 'PAUSED' : 'PLAYING';
                          const position = Math.round((videoRef.current?.currentTime ?? 0) * 1000);
                          if (nextState === 'PLAYING') void enableSynchronizedPlayback();
                          else videoRef.current?.pause();
                          void runRoomAction((token) =>
                            watchSpaceApi.playback(token, room.watchSpaceId, nextState, position));
                        }}
                      >
                        {room.playbackState === 'PLAYING' ? 'Pause for everyone' : 'Play for everyone'}
                      </button>
                      <label className="shared-player-seek">{formatDuration(Math.round(seekPosition / 1000) * 1000)}
                        <input
                          type="range"
                          min={0}
                          max={room.durationMs}
                          step={1000}
                          value={Math.min(seekPosition, room.durationMs)}
                          onChange={(event) => setSeekPosition(Number(event.currentTarget.value))}
                          onPointerUp={() => void runRoomAction((token) =>
                            watchSpaceApi.playback(token, room.watchSpaceId, room.playbackState === 'PLAYING' ? 'PLAYING' : 'PAUSED', seekPosition))}
                          onKeyUp={() => void runRoomAction((token) =>
                            watchSpaceApi.playback(token, room.watchSpaceId, room.playbackState === 'PLAYING' ? 'PLAYING' : 'PAUSED', seekPosition))}
                          aria-label="Seek shared playback"
                        />
                        {formatDuration(room.durationMs)}
                      </label>
                    </>
                  ) : <p>Following the host · {room.playbackState === 'PLAYING' ? 'Playing' : 'Paused'}</p>}
                  {playbackBlocked && (
                    <button className="room-secondary-action" type="button" onClick={() => void enableSynchronizedPlayback()}>
                      Enable synchronized playback
                    </button>
                  )}
                </div>
              </div>
            )}
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
              {room.status === 'LIVE' && <div className="room-next-step"><span className="room-next-icon" aria-hidden="true">◷</span><p>Shared playback, room chat, and online status are active for this room.</p></div>}
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
                    <span className={`room-avatar room-avatar-${index % 4} ${person.online ? 'room-avatar-online' : 'room-avatar-offline'}`}>{person.displayName.slice(0, 1).toUpperCase()}</span>
                    <span className="room-person-name">{person.displayName}</span>
                    <span className="room-person-role">{person.online ? 'ONLINE' : 'AWAY'}{person.roomRole === 'HOST' ? ' · HOST' : ''}</span>
                  </li>
                ))}
                {room.participants.length === 0 && <li className="room-empty-people">No active participants.</li>}
              </ul>
            </section>

            <section className="room-panel chat-panel" aria-labelledby="chat-title">
              <div className="room-panel-heading"><div><p className="eyebrow">THE CONVERSATION</p><h2 id="chat-title">Room chat</h2></div><span className="room-people-count">LIVE</span></div>
              <ol className="chat-message-list" aria-live="polite">
                {messages.map((message) => <li key={message.id}><strong>{message.displayName}</strong><p>{message.body}</p></li>)}
                {!messages.length && <li className="chat-empty">Say hello to the room.</li>}
              </ol>
              {room.status !== 'ENDED' && <form className="chat-compose" onSubmit={(event) => void sendChat(event)}><input maxLength={2000} value={chatDraft} onChange={(event) => setChatDraft(event.currentTarget.value)} placeholder="Write a message…" aria-label="Chat message" /><button type="submit" disabled={chatPending || !chatDraft.trim()}>{chatPending ? '…' : 'Send'}</button></form>}
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

