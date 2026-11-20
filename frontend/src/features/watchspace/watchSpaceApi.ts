import { authenticatedGet, authenticatedPost } from '../auth/api';

export interface RoomParticipant {
  userId: string;
  displayName: string;
  roomRole: 'HOST' | 'VIEWER';
  online: boolean;
  muted: boolean;
}

export interface ChatMessage {
  id: string;
  userId: string;
  displayName: string;
  body: string;
  sequenceNo: number;
  createdAt: string;
}

export interface WatchSpace {
  watchSpaceId: string;
  titleId: string;
  titleName: string;
  videoAssetUri: string;
  durationMs: number;
  name: string;
  privacy: 'PUBLIC_LINK' | 'INVITE_ONLY';
  status: 'SCHEDULED' | 'LIVE' | 'HOST_DISCONNECTED' | 'ENDED';
  hostUserId: string;
  myRole: 'HOST' | 'VIEWER' | 'NONE';
  participantCount: number;
  maxParticipants: number;
  locked: boolean;
  playbackState: 'PLAYING' | 'PAUSED' | 'BUFFERING';
  playbackPositionMs: number;
  playbackUpdatedAt: string;
  stateVersion: number;
  lastSequenceNo: number;
  createdAt: string;
  startedAt: string | null;
  endedAt: string | null;
  participants: RoomParticipant[];
}

export interface CreatedWatchSpace {
  room: WatchSpace;
  inviteCode: string;
}

export const watchSpaceApi = {
  create(accessToken: string, titleId: string): Promise<CreatedWatchSpace> {
    return authenticatedPost<CreatedWatchSpace>('/watch-spaces', accessToken, { titleId });
  },

  joinByCode(accessToken: string, inviteCode: string): Promise<WatchSpace> {
    return authenticatedPost<WatchSpace>('/watch-spaces/join', accessToken, { inviteCode });
  },

  get(accessToken: string, roomId: string): Promise<WatchSpace> {
    return authenticatedGet(`/watch-spaces/${encodeURIComponent(roomId)}`, accessToken);
  },

  start(accessToken: string, roomId: string): Promise<WatchSpace> {
    return authenticatedPost<WatchSpace>(`/watch-spaces/${encodeURIComponent(roomId)}/start`, accessToken);
  },

  end(accessToken: string, roomId: string): Promise<WatchSpace> {
    return authenticatedPost<WatchSpace>(`/watch-spaces/${encodeURIComponent(roomId)}/end`, accessToken);
  },

  leave(accessToken: string, roomId: string): Promise<WatchSpace> {
    return authenticatedPost<WatchSpace>(`/watch-spaces/${encodeURIComponent(roomId)}/leave`, accessToken);
  },

  muteParticipant(accessToken: string, roomId: string, participantId: string): Promise<WatchSpace> {
    return authenticatedPost<WatchSpace>(
      `/watch-spaces/${encodeURIComponent(roomId)}/participants/${encodeURIComponent(participantId)}/mute`, accessToken);
  },

  unmuteParticipant(accessToken: string, roomId: string, participantId: string): Promise<WatchSpace> {
    return authenticatedPost<WatchSpace>(
      `/watch-spaces/${encodeURIComponent(roomId)}/participants/${encodeURIComponent(participantId)}/unmute`, accessToken);
  },

  removeParticipant(accessToken: string, roomId: string, participantId: string): Promise<WatchSpace> {
    return authenticatedPost<WatchSpace>(
      `/watch-spaces/${encodeURIComponent(roomId)}/participants/${encodeURIComponent(participantId)}/remove`, accessToken);
  },

  playback(accessToken: string, roomId: string, playbackState: 'PLAYING' | 'PAUSED', positionMs: number): Promise<WatchSpace> {
    return authenticatedPost<WatchSpace>(`/watch-spaces/${encodeURIComponent(roomId)}/playback`, accessToken, { playbackState, positionMs });
  },

  heartbeat(accessToken: string, roomId: string): Promise<void> {
    return authenticatedPost<void>(`/watch-spaces/${encodeURIComponent(roomId)}/heartbeat`, accessToken);
  },

  chatHistory(accessToken: string, roomId: string, afterSequence: number): Promise<ChatMessage[]> {
    return authenticatedGet(`/watch-spaces/${encodeURIComponent(roomId)}/chat?afterSequence=${afterSequence}&limit=50`, accessToken);
  },

  sendChat(accessToken: string, roomId: string, body: string): Promise<ChatMessage> {
    return authenticatedPost<ChatMessage>(`/watch-spaces/${encodeURIComponent(roomId)}/chat`, accessToken, { body });
  },
};

