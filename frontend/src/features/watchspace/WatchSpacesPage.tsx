import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { ApiError } from '../auth/api';
import { useAuth } from '../auth/AuthProvider';
import { watchSpaceApi, WatchSpaceApiError, type CreateRoomRequest, type RoomDetails, type RoomParticipant, type RoomPreview, type RoomView } from './api';

interface Props { onBack(): void }
type Screen = 'overview' | 'room';

function message(error: unknown): string {
  if (error instanceof WatchSpaceApiError || error instanceof ApiError) {
    return error.correlationId ? `${error.message} (Reference: ${error.correlationId})` : error.message;
  }
  return error instanceof Error ? error.message : 'The request could not be completed.';
}

function isDetails(room: RoomView | null): room is RoomDetails {
  return Boolean(room && 'watchSpaceId' in room);
}

function timestamp(value: string | null): string {
  if (!value) return 'Not scheduled';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function WatchSpacesPage({ onBack }: Props) {
  const { getAccessToken, refreshAccessToken, logout, user } = useAuth();
  const [screen, setScreen] = useState<Screen>('overview');
  const [titleOptions, setTitleOptions] = useState<Array<{id: string; name: string}>>([]);
  const [roomIdInput, setRoomIdInput] = useState('');
  const [inviteCodeInput, setInviteCodeInput] = useState('');
  const [roomId, setRoomId] = useState<string | null>(null);
  const [room, setRoom] = useState<RoomView | null>(null);
  const [participants, setParticipants] = useState<RoomParticipant[]>([]);
  const [inviteCode, setInviteCode] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [createForm, setCreateForm] = useState({titleId: '', name: '', privacy: 'INVITE_ONLY' as CreateRoomRequest['privacy'], maxParticipants: 25});

  const authorized = useCallback(async <T,>(operation: (token: string) => Promise<T>): Promise<T> => {
    let token = getAccessToken();
    if (!token) throw new ApiError(401, {code: 'UNAUTHENTICATED', message: 'Your session has expired.'});
    try {
      return await operation(token);
    } catch (cause) {
      if (!(cause instanceof ApiError || cause instanceof WatchSpaceApiError) || cause.status !== 401) throw cause;
      await refreshAccessToken();
      token = getAccessToken();
      if (!token) throw cause;
      return operation(token);
    }
  }, [getAccessToken, refreshAccessToken]);

  useEffect(() => {
    let active = true;
    void authorized((token) => watchSpaceApi.listTitles(token)).then((response) => {
      if (active) setTitleOptions(response.items || []);
    }).catch((cause) => { if (active) setError(message(cause)); });
    return () => { active = false; };
  }, [authorized]);

  const loadRoom = useCallback(async (id: string) => {
    setIsLoading(true); setError(null); setNotice(null);
    try {
      const [nextRoom, people] = await authorized(async (token) => Promise.all([
        watchSpaceApi.get(token, id),
        watchSpaceApi.participants(token, id).catch(() => [] as RoomParticipant[]),
      ]));
      setRoom(nextRoom); setParticipants(people); setRoomId(id); setScreen('room');
    } catch (cause) { setError(message(cause)); }
    finally { setIsLoading(false); }
  }, [authorized]);

  async function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setIsSaving(true); setError(null); setNotice(null); setInviteCode(null);
    try {
      const body: CreateRoomRequest = {titleId: createForm.titleId, privacy: createForm.privacy, maxParticipants: createForm.maxParticipants};
      if (createForm.name.trim()) body.name = createForm.name.trim();
      const created = await authorized((token) => watchSpaceApi.create(token, body));
      setInviteCode(created.inviteCode);
      setNotice('Watch Space created. Copy the invite code now; it is only shown once.');
      await loadRoom(created.watchSpaceId);
    } catch (cause) { setError(message(cause)); }
    finally { setIsSaving(false); }
  }

  async function submitJoin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setIsSaving(true); setError(null); setNotice(null);
    try {
      const joined = await authorized((token) => roomIdInput.trim()
        ? watchSpaceApi.joinById(token, roomIdInput.trim(), inviteCodeInput.trim() || undefined)
        : watchSpaceApi.joinByCode(token, inviteCodeInput.trim()));
      setNotice(joined.rejoined ? 'Welcome back. Your previous membership was restored.' : joined.alreadyMember ? 'You are already a member of this Watch Space.' : 'You joined the Watch Space.');
      await loadRoom(joined.watchSpaceId);
    } catch (cause) { setError(message(cause)); }
    finally { setIsSaving(false); }
  }

  async function runAction(action: (token: string) => Promise<unknown>, success: string) {
    setIsSaving(true); setError(null); setNotice(null);
    try {
      await authorized(action);
      setNotice(success);
      if (roomId) await loadRoom(roomId);
    } catch (cause) { setError(message(cause)); }
    finally { setIsSaving(false); }
  }

  const host = isDetails(room) && room.myRoomRole === 'HOST';
  const isRoomEnded = isDetails(room) && room.status === 'ENDED';
  const titleName = useMemo(() => titleOptions.find((item) => item.id === createForm.titleId)?.name || '', [titleOptions, createForm.titleId]);

  return (
    <main className="watchspace-shell">
      <header className="watchspace-topbar">
        <a className="brand" href="/" aria-label="AI Watch Spaces home"><span className="brand-mark">W</span><span>watch<span className="brand-accent">spaces</span></span></a>
        <div className="watchspace-top-actions">
          <button className="catalog-back-button" type="button" onClick={onBack}>Dashboard</button>
          <button className="dashboard-logout" type="button" onClick={() => void logout()}>Sign out</button>
        </div>
      </header>

      <section className="watchspace-content">
        <div className="watchspace-heading">
          <div><p className="eyebrow"><span className="status-dot"/> ROOM MANAGEMENT</p><h1>Watch Spaces<span>.</span></h1><p>Create a private room or join an existing one with its room ID or invite code.</p></div>
          {user && <span className="watchspace-user">SIGNED IN AS {user.displayName.toUpperCase()}</span>}
        </div>

        {error && <div className="watchspace-alert" role="alert"><span>{error}</span><button type="button" onClick={() => setError(null)} aria-label="Dismiss error">×</button></div>}
        {notice && <div className="watchspace-notice" role="status"><span>{notice}</span><button type="button" onClick={() => setNotice(null)} aria-label="Dismiss notice">×</button></div>}
        {inviteCode && <div className="watchspace-invite"><div><span className="eyebrow">ONE-TIME INVITE CODE</span><strong>{inviteCode}</strong><small>Copy this code and share it with your guests.</small></div><button type="button" onClick={() => void navigator.clipboard?.writeText(inviteCode)}>Copy code</button></div>}

        {screen === 'overview' ? (
          <div className="watchspace-columns">
            <section className="watchspace-panel">
              <div className="watchspace-panel-heading"><span className="eyebrow">HOST A SESSION</span><h2>Create a Watch Space</h2><p>Choose a published title and set the room's entry policy.</p></div>
              <form className="watchspace-form" onSubmit={(event) => void submitCreate(event)}>
                <label>Title<select required value={createForm.titleId} onChange={(event) => setCreateForm((form) => ({...form, titleId: event.target.value}))}><option value="">Choose a title</option>{titleOptions.map((title) => <option key={title.id} value={title.id}>{title.name}</option>)}</select></label>
                <label>Room name<input value={createForm.name} maxLength={120} placeholder={titleName ? `${titleName} watch party` : 'Friday watch party'} onChange={(event) => setCreateForm((form) => ({...form, name: event.target.value}))}/></label>
                <label>Privacy<select value={createForm.privacy} onChange={(event) => setCreateForm((form) => ({...form, privacy: event.target.value as CreateRoomRequest['privacy']}))}><option value="INVITE_ONLY">Invite only</option><option value="PUBLIC_LINK">Public link</option></select></label>
                <label>Maximum participants<input type="number" min={2} max={50} required value={createForm.maxParticipants} onChange={(event) => setCreateForm((form) => ({...form, maxParticipants: Number(event.target.value)}))}/><small>Between 2 and 50, including the host.</small></label>
                <button className="primary-button watchspace-submit" type="submit" disabled={isSaving || isLoading || !titleOptions.length}>Create room <span>→</span></button>
              </form>
            </section>

            <section className="watchspace-panel">
              <div className="watchspace-panel-heading"><span className="eyebrow">JOIN A SESSION</span><h2>Enter a Watch Space</h2><p>Use the room ID with an optional invite code, or enter the invite code alone.</p></div>
              <form className="watchspace-form" onSubmit={(event) => void submitJoin(event)}>
                <label>Room ID <span className="watchspace-optional">OPTIONAL WITH INVITE CODE</span><input value={roomIdInput} onChange={(event) => setRoomIdInput(event.target.value)} placeholder="Watch Space UUID" /></label>
                <label>Invite code<input value={inviteCodeInput} onChange={(event) => setInviteCodeInput(event.target.value.toUpperCase())} placeholder="ABCDE-FGHJK" /></label>
                <button className="primary-button watchspace-submit" type="submit" disabled={isSaving || (!roomIdInput.trim() && !inviteCodeInput.trim())}>Join room <span>→</span></button>
              </form>
              <div className="watchspace-help"><strong>Already a member?</strong><p>Enter the room ID without a code to return to a previous membership. Removed members cannot rejoin.</p></div>
            </section>
          </div>
        ) : (
          <section className="watchspace-room-panel">
            <div className="watchspace-room-header"><div><span className="eyebrow">ROOM OVERVIEW</span><h2>{isDetails(room) ? room.name : (room as RoomPreview | null)?.name || 'Watch Space'}</h2><p>{isDetails(room) ? room.titleName : (room as RoomPreview | null)?.titleName}</p></div><div className="watchspace-room-actions"><button type="button" className="catalog-back-button" onClick={() => {setScreen('overview');setRoom(null);setRoomId(null);}}>All rooms</button><button type="button" className="catalog-back-button" onClick={() => roomId && void loadRoom(roomId)} disabled={isLoading}>Refresh</button></div></div>
            {isLoading ? <div className="catalog-state" role="status"><span className="loading-orbit"/><p>Loading room…</p></div> : room && (
              <>
                <div className="watchspace-stat-grid"><div><small>STATUS</small><strong>{room.status}</strong></div><div><small>MEMBERS</small><strong>{isDetails(room) ? room.activeMembershipCount : room.participantCount} / {room.maxParticipants}</strong></div><div><small>ACCESS</small><strong>{room.locked ? 'LOCKED' : 'OPEN'}</strong></div><div><small>ROLE</small><strong>{isDetails(room) ? room.myRoomRole : 'VISITOR'}</strong></div></div>
                {isDetails(room) && <div className="watchspace-metadata"><div><small>ROOM ID</small><code>{room.watchSpaceId}</code></div><div><small>CREATED</small><span>{timestamp(room.createdAt)}</span></div><div><small>SCHEDULED</small><span>{timestamp(room.scheduledAt)}</span></div><div><small>PLAYBACK</small><span>{room.playbackState} · {Math.floor(room.lastPlaybackPositionMs / 1000)}s</span></div></div>}
                {isDetails(room) && <div className="watchspace-participants"><div className="watchspace-subheading"><h3>Participants</h3><span>{participants.length} active memberships</span></div>{participants.length ? <div className="watchspace-participant-list">{participants.map((participant) => <div className="watchspace-participant" key={participant.userId}><span className="watchspace-avatar">{participant.displayName.slice(0,1).toUpperCase()}</span><div><strong>{participant.displayName}{participant.userId === user?.id ? ' (you)' : ''}</strong><small>{participant.roomRole} · {participant.connected ? participant.connectionQuality : 'Offline until M5'}</small></div><span className={participant.muted ? 'watchspace-muted' : 'watchspace-unmuted'}>{participant.muted ? 'MUTED' : 'CAN CHAT'}</span>{host && participant.userId !== room.hostUserId && <div className="watchspace-moderation"><button type="button" disabled={isSaving} onClick={() => void runAction((token) => watchSpaceApi.muteParticipant(token, room.watchSpaceId, participant.userId, !participant.muted), participant.muted ? 'Participant unmuted.' : 'Participant muted.')}>{participant.muted ? 'Unmute' : 'Mute'}</button><button type="button" disabled={isSaving} onClick={() => {if(window.confirm(`Remove ${participant.displayName} from this room?`)) void runAction((token) => watchSpaceApi.removeParticipant(token, room.watchSpaceId, participant.userId), 'Participant removed.');}}>Remove</button></div>}</div>)}</div> : <p className="watchspace-empty">No participant records are available.</p>}</div>}
                {!isDetails(room) && <div className="watchspace-preview-note">This is a public preview. Join the room to see its members and full details.</div>}
                {isDetails(room) && <div className="watchspace-controls">{host && <><button type="button" disabled={isSaving || isRoomEnded} onClick={() => void runAction((token) => watchSpaceApi.setLocked(token, room.watchSpaceId, !room.locked), room.locked ? 'Room unlocked.' : 'Room locked.')}>{room.locked ? 'Unlock room' : 'Lock room'}</button><button type="button" disabled={isSaving || room.status !== 'SCHEDULED'} onClick={() => void runAction((token) => watchSpaceApi.start(token, room.watchSpaceId), 'Room started. Playback is paused until M5 playback controls.')}>Start room</button><button type="button" disabled={isSaving || isRoomEnded} onClick={() => void runAction((token) => watchSpaceApi.rotateInviteCode(token, room.watchSpaceId).then((result) => {setInviteCode(result.inviteCode); return result;}), 'Invite code rotated. The previous code no longer works.')}>Rotate invite code</button><button className="danger" type="button" disabled={isSaving || isRoomEnded} onClick={() => {if(window.confirm('End this Watch Space for all members? This cannot be undone.')) void runAction((token) => watchSpaceApi.end(token, room.watchSpaceId), 'Watch Space ended.');}}>End room</button></>}{!host && !isRoomEnded && <button className="danger" type="button" disabled={isSaving} onClick={() => {if(window.confirm('Leave this Watch Space?')) void runAction((token) => watchSpaceApi.leave(token, room.watchSpaceId), 'You left the Watch Space.');}}>Leave room</button>}</div>}
              </>
            )}
          </section>
        )}
        <p className="watchspace-scope-note">M4 currently exposes room lifecycle and moderation over REST. Live playback synchronization, realtime presence, chat and host handoff are M5 work; this screen does not simulate those capabilities.</p>
      </section>
    </main>
  );
}
