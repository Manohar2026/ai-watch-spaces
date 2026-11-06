import { authenticatedGet, API_BASE_URL } from '../auth/api';

export type RoomPrivacy = 'INVITE_ONLY' | 'PUBLIC_LINK';
export type RoomStatus = 'SCHEDULED' | 'LIVE' | 'HOST_DISCONNECTED' | 'ENDED';
export type AiVerbosity = 'MINIMAL' | 'NORMAL' | 'CHATTY';
export type RoomRole = 'HOST' | 'VIEWER';

export interface RoomSettings {
  maxParticipants: number;
  aiVerbosity: AiVerbosity;
  triviaEnabled: boolean;
  votingEnabled: boolean;
  chatMuted: boolean;
}

export interface CreateRoomRequest {
  titleId: string;
  name?: string;
  privacy?: RoomPrivacy;
  maxParticipants?: number;
  aiVerbosity?: AiVerbosity;
  triviaEnabled?: boolean;
  votingEnabled?: boolean;
  scheduledAt?: string;
}

export interface CreateRoomResponse {
  watchSpaceId: string;
  inviteCode: string;
  status: RoomStatus;
  hostUserId: string;
  createdAt: string;
  settings: RoomSettings;
}

export interface JoinRoomResponse {
  watchSpaceId: string;
  membershipId: string;
  userId: string;
  joinedAt: string;
  roomRole: RoomRole;
  alreadyMember: boolean;
  rejoined: boolean;
}

export interface RoomDetails {
  watchSpaceId: string;
  titleId: string;
  titleName: string;
  titleDurationMs: number;
  name: string;
  privacy: RoomPrivacy;
  status: RoomStatus;
  hostUserId: string;
  myRoomRole: RoomRole;
  participantCount: number;
  activeMembershipCount: number;
  maxParticipants: number;
  aiVerbosity: AiVerbosity;
  triviaEnabled: boolean;
  votingEnabled: boolean;
  locked: boolean;
  chatMuted: boolean;
  scheduledAt: string | null;
  createdAt: string;
  startedAt: string | null;
  endedAt: string | null;
  playbackState: 'PLAYING' | 'PAUSED' | 'SEEKING' | 'BUFFERING';
  lastPlaybackPositionMs: number;
  playbackUpdatedAt: string;
  stateVersion: number;
  lastSequenceNo: number;
}

export interface RoomPreview {
  name: string;
  titleName: string;
  status: RoomStatus;
  participantCount: number;
  maxParticipants: number;
  locked: boolean;
}

export interface RoomParticipant {
  userId: string;
  displayName: string;
  roomRole: RoomRole;
  connected: boolean;
  connectionQuality: 'UNKNOWN' | 'GOOD' | 'FAIR' | 'POOR';
  muted: boolean;
}

export type RoomView = RoomDetails | RoomPreview;

export interface RotateInviteCodeResponse {
  inviteCode: string;
}

interface ErrorEnvelope {
  code?: string;
  message?: string;
  correlationId?: string;
  details?: Array<{ field: string | null; code: string; message: string }>;
}

export class WatchSpaceApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly details: ErrorEnvelope['details'];

  constructor(status: number, body: ErrorEnvelope) {
    super(body.message || 'The Watch Space request could not be completed.');
    this.name = 'WatchSpaceApiError';
    this.status = status;
    this.code = body.code || 'UNKNOWN_ERROR';
    this.correlationId = body.correlationId;
    this.details = body.details || [];
  }
}

async function request<T>(path: string, token: string, method = 'GET', body?: unknown): Promise<T> {
  const headers = new Headers({ Accept: 'application/json', Authorization: `Bearer ${token}` });
  const init: RequestInit = { method, headers, credentials: 'same-origin' };
  if (body !== undefined) {
    headers.set('Content-Type', 'application/json');
    init.body = JSON.stringify(body);
  }
  const response = await fetch(`${API_BASE_URL}${path}`, init);
  if (response.status === 204) return undefined as T;
  const payload: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    throw new WatchSpaceApiError(response.status,
      typeof payload === 'object' && payload !== null ? payload as ErrorEnvelope : {});
  }
  return payload as T;
}

async function mutate<T>(path: string, token: string, method: string, body?: unknown): Promise<T> {
  return request<T>(path, token, method, body);
}

export const watchSpaceApi = {
  listTitles: (token: string) => authenticatedGet<{items: Array<{id: string; name: string}>}>(
    '/titles?page=0&size=100', token),
  create: (token: string, body: CreateRoomRequest) => mutate<CreateRoomResponse>('/watch-spaces', token, 'POST', body),
  joinByCode: (token: string, inviteCode: string) => mutate<JoinRoomResponse>('/watch-spaces/join', token, 'POST', {inviteCode}),
  joinById: (token: string, roomId: string, inviteCode?: string) => mutate<JoinRoomResponse>(
    `/watch-spaces/${encodeURIComponent(roomId)}/join`, token, 'POST', inviteCode ? {inviteCode} : undefined),
  get: (token: string, roomId: string) => authenticatedGet<RoomView>(
    `/watch-spaces/${encodeURIComponent(roomId)}`, token),
  participants: (token: string, roomId: string) => authenticatedGet<RoomParticipant[]>(
    `/watch-spaces/${encodeURIComponent(roomId)}/participants`, token),
  leave: (token: string, roomId: string) => mutate<void>(
    `/watch-spaces/${encodeURIComponent(roomId)}/leave`, token, 'POST'),
  start: (token: string, roomId: string) => mutate<RoomDetails>(
    `/watch-spaces/${encodeURIComponent(roomId)}/start`, token, 'POST'),
  end: (token: string, roomId: string) => mutate<RoomDetails>(
    `/watch-spaces/${encodeURIComponent(roomId)}/end`, token, 'POST'),
  setLocked: (token: string, roomId: string, locked: boolean) => mutate<RoomDetails>(
    `/watch-spaces/${encodeURIComponent(roomId)}/lock`, token, 'POST', {locked}),
  updateSettings: (token: string, roomId: string, patch: Partial<RoomSettings>) => mutate<RoomDetails>(
    `/watch-spaces/${encodeURIComponent(roomId)}/settings`, token, 'PATCH', patch),
  rotateInviteCode: (token: string, roomId: string) => mutate<RotateInviteCodeResponse>(
    `/watch-spaces/${encodeURIComponent(roomId)}/invite-code`, token, 'POST'),
  muteParticipant: (token: string, roomId: string, userId: string, muted: boolean) => mutate<RoomDetails>(
    `/watch-spaces/${encodeURIComponent(roomId)}/participants/${encodeURIComponent(userId)}/mute`, token, 'POST', {muted}),
  removeParticipant: (token: string, roomId: string, userId: string) => mutate<void>(
    `/watch-spaces/${encodeURIComponent(roomId)}/participants/${encodeURIComponent(userId)}`, token, 'DELETE'),
};
