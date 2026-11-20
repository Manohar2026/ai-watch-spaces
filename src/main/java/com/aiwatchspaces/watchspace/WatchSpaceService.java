package com.aiwatchspaces.watchspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.text.PlainText;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.CreateRoomRequest;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.RoomCreatedResponse;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.RoomParticipantResponse;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.RoomResponse;
import com.aiwatchspaces.watchspace.web.WatchSpaceDtos.PlaybackRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted room lifecycle operations. All writes are called through {@link RoomMutationLanes}. */
@Service
public class WatchSpaceService {

    private static final int DEFAULT_CAPACITY = 25;
    private static final int MAX_HOSTED_ROOMS = 5;
    private static final long PRESENCE_WINDOW_SECONDS = 30;

    private static final RowMapper<RoomRow> ROOM_ROW = WatchSpaceService::mapRoom;

    private final NamedParameterJdbcTemplate jdbc;
    private final InviteCodes inviteCodes;
    private final Clock clock;

    public WatchSpaceService(NamedParameterJdbcTemplate jdbc, InviteCodes inviteCodes, Clock clock) {
        this.jdbc = jdbc;
        this.inviteCodes = inviteCodes;
        this.clock = clock;
    }

    @Transactional
    public RoomCreatedResponse create(UUID roomId, AuthenticatedUser actor, CreateRoomRequest request) {
        int maxParticipants = request.maxParticipants() == null ? DEFAULT_CAPACITY : request.maxParticipants();
        if (maxParticipants < 2 || maxParticipants > 50) {
            throw invalid("maxParticipants", "must be between 2 and 50");
        }
        String privacy = request.privacy() == null ? "INVITE_ONLY"
                : request.privacy().strip().toUpperCase(Locale.ROOT);
        if (!privacy.equals("INVITE_ONLY") && !privacy.equals("PUBLIC_LINK")) {
            throw invalid("privacy", "must be INVITE_ONLY or PUBLIC_LINK");
        }

        TitleRow title = publishedTitle(request.titleId());
        String roomName = request.name() == null || request.name().isBlank()
                ? PlainText.normalize("name", truncateTitle(title.name(), 108) + " watch party", 1, 120)
                : PlainText.normalize("name", request.name(), 1, 120);

        // Serialize room creation per host so concurrent requests cannot exceed the cap.
        jdbc.query("SELECT id FROM app_users WHERE id = :host FOR UPDATE",
                Map.of("host", actor.userId()), (rs, rowNum) -> rs.getObject("id", UUID.class));
        Integer hosted = jdbc.queryForObject("""
                SELECT count(*) FROM watch_spaces
                WHERE host_user_id = :host AND status <> 'ENDED'
                """, Map.of("host", actor.userId()), Integer.class);
        if (hosted != null && hosted >= MAX_HOSTED_ROOMS) {
            throw new ApiException(ErrorCode.HOST_ROOM_LIMIT, "You already host the maximum number of active rooms.");
        }

        InviteCodes.Generated code = inviteCodes.generate();
        Instant now = mutationInstant();
        jdbc.update("""
                INSERT INTO watch_spaces (
                    id, title_id, host_user_id, name, privacy, invite_code_hash, status,
                    max_participants, created_at, playback_updated_at, last_playback_position_ms,
                    state_version, last_sequence_no
                ) VALUES (
                    :id, :titleId, :host, :name, :privacy, :inviteHash, 'SCHEDULED',
                    :maxParticipants, :now, :now, 0, 2, 2
                )
                """, new MapSqlParameterSource()
                .addValue("id", roomId)
                .addValue("titleId", title.id())
                .addValue("host", actor.userId())
                .addValue("name", roomName)
                .addValue("privacy", privacy)
                .addValue("inviteHash", code.hash())
                .addValue("maxParticipants", maxParticipants)
                .addValue("now", Timestamp.from(now)));

        jdbc.update("""
                INSERT INTO watch_space_participants (id, watch_space_id, user_id, joined_at, last_seen_at)
                VALUES (:id, :roomId, :userId, :now, :now)
                """, Map.of("id", UUID.randomUUID(), "roomId", roomId, "userId", actor.userId(), "now", Timestamp.from(now)));
        appendEvent(roomId, 1, actor.userId(), "ROOM_CREATED", "{}", now);
        appendEvent(roomId, 2, actor.userId(), "PARTICIPANT_JOINED",
                "{\"userId\":\"" + actor.userId() + "\"}", now);

        return new RoomCreatedResponse(loadRoom(roomId, actor.userId()), code.displayCode());
    }

    @Transactional(readOnly = true)
    public UUID roomIdForInvite(String inviteCode) {
        String hash = inviteCodes.hashInput(inviteCode);
        if (hash == null) {
            throw inviteInvalid();
        }
        List<UUID> ids = jdbc.query("""
                SELECT id FROM watch_spaces WHERE invite_code_hash = :hash AND status <> 'ENDED'
                """, Map.of("hash", hash), (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (ids.isEmpty()) {
            throw inviteInvalid();
        }
        return ids.getFirst();
    }

    @Transactional
    public RoomResponse join(UUID roomId, AuthenticatedUser actor, String inviteCode) {
        RoomRow room = lockRoom(roomId);
        if (room.status().equals("ENDED")) {
            throw new ApiException(ErrorCode.ROOM_ENDED, "This watch space has ended.");
        }
        if (isRemoved(roomId, actor.userId())) {
            throw new ApiException(ErrorCode.REMOVED_FROM_ROOM, "You have been removed from this watch space.");
        }
        if (isActiveMember(roomId, actor.userId())) {
            return loadRoom(roomId, actor.userId());
        }

        boolean previousMember = hasMembership(roomId, actor.userId());
        if (!previousMember) {
            if (room.privacy().equals("INVITE_ONLY")) {
                String suppliedHash = inviteCodes.hashInput(inviteCode);
                if (suppliedHash == null || !suppliedHash.equals(room.inviteCodeHash())) {
                    throw inviteInvalid();
                }
            } else if (inviteCode != null && !inviteCode.isBlank()) {
                String suppliedHash = inviteCodes.hashInput(inviteCode);
                if (suppliedHash == null || !suppliedHash.equals(room.inviteCodeHash())) {
                    throw inviteInvalid();
                }
            }
            if (room.locked()) {
                throw new ApiException(ErrorCode.ROOM_LOCKED, "This watch space is locked to new participants.");
            }
        }

        int activeCount = activeParticipantCount(roomId);
        if (activeCount >= room.maxParticipants()) {
            throw new ApiException(ErrorCode.ROOM_FULL, "This watch space is full.");
        }

        Instant now = mutationInstant();
        jdbc.update("""
                INSERT INTO watch_space_participants (id, watch_space_id, user_id, joined_at, last_seen_at)
                VALUES (:id, :roomId, :userId, :now, :now)
                """, Map.of("id", UUID.randomUUID(), "roomId", roomId, "userId", actor.userId(), "now", now));
        appendEvent(roomId, room.lastSequenceNo() + 1, actor.userId(), "PARTICIPANT_JOINED",
                "{\"userId\":\"" + actor.userId() + "\"}", now);
        advanceRoom(roomId, room.lastSequenceNo(), room.lastSequenceNo() + 1);
        return loadRoom(roomId, actor.userId());
    }

    @Transactional(readOnly = true)
    public RoomResponse get(UUID roomId, AuthenticatedUser actor) {
        if (!isMember(roomId, actor.userId())) {
            throw new ApiException(ErrorCode.NOT_ROOM_MEMBER, "Join this watch space to view it.");
        }
        return loadRoom(roomId, actor.userId());
    }

    @Transactional
    public void heartbeat(UUID roomId, AuthenticatedUser actor) {
        RoomRow room = lockRoom(roomId);
        if (room.status().equals("ENDED")) {
            throw new ApiException(ErrorCode.ROOM_ENDED, "This watch space has ended.");
        }
        if (!isActiveMember(roomId, actor.userId())) {
            throw new ApiException(ErrorCode.NOT_ROOM_MEMBER, "Join this watch space to update presence.");
        }
        jdbc.update("""
                UPDATE watch_space_participants SET last_seen_at = :now
                WHERE watch_space_id = :roomId AND user_id = :userId AND left_at IS NULL
                """, Map.of("now", Timestamp.from(mutationInstant()), "roomId", roomId, "userId", actor.userId()));
    }

    @Transactional
    public RoomResponse leave(UUID roomId, AuthenticatedUser actor) {
        RoomRow room = lockRoom(roomId);
        if (room.status().equals("ENDED")) {
            throw new ApiException(ErrorCode.ROOM_ENDED, "This watch space has ended.");
        }
        if (!isActiveMember(roomId, actor.userId())) {
            throw new ApiException(ErrorCode.NOT_ROOM_MEMBER, "You are not an active member of this watch space.");
        }
        if (room.hostUserId().equals(actor.userId())) {
            return end(roomId, actor);
        }
        Instant now = mutationInstant();
        jdbc.update("""
                UPDATE watch_space_participants SET left_at = :now, leave_reason = 'LEFT'
                WHERE watch_space_id = :roomId AND user_id = :userId AND left_at IS NULL
                """, Map.of("now", Timestamp.from(now), "roomId", roomId, "userId", actor.userId()));
        long sequence = room.lastSequenceNo() + 1;
        appendEvent(roomId, sequence, actor.userId(), "PARTICIPANT_LEFT",
                "{\"userId\":\"" + actor.userId() + "\"}", now);
        advanceRoom(roomId, room.lastSequenceNo(), sequence);
        return loadRoom(roomId, actor.userId());
    }

    @Transactional
    public RoomResponse start(UUID roomId, AuthenticatedUser actor) {
        RoomRow room = lockRoom(roomId);
        requireHost(room, actor.userId());
        if (room.status().equals("ENDED")) {
            throw new ApiException(ErrorCode.ROOM_ENDED, "This watch space has ended.");
        }
        if (room.status().equals("LIVE")) {
            return loadRoom(roomId, actor.userId());
        }
        if (!room.status().equals("SCHEDULED")) {
            throw new ApiException(ErrorCode.ROOM_NOT_LIVE, "This watch space cannot be started in its current state.");
        }
        Instant now = mutationInstant();
        UUID sessionId = UUID.randomUUID();
        long sequence = room.lastSequenceNo() + 1;
        jdbc.update("""
                UPDATE watch_spaces
                SET status = 'LIVE', started_at = :now, session_correlation_id = :sessionId,
                    playback_state = 'PAUSED', last_playback_position_ms = 0, playback_updated_at = :now
                WHERE id = :id AND status = 'SCHEDULED'
                """, Map.of("now", Timestamp.from(now), "sessionId", sessionId, "id", roomId));
        appendEvent(roomId, sequence, actor.userId(), "ROOM_STARTED", "{}", now);
        advanceRoom(roomId, room.lastSequenceNo(), sequence);
        return loadRoom(roomId, actor.userId());
    }

    @Transactional
    public RoomResponse updatePlayback(UUID roomId, AuthenticatedUser actor, PlaybackRequest request) {
        RoomRow room = lockRoom(roomId);
        requireHost(room, actor.userId());
        if (!room.status().equals("LIVE")) {
            throw new ApiException(ErrorCode.ROOM_NOT_LIVE, "Start the watch space before controlling playback.");
        }
        int position = Math.min(request.positionMs(), room.durationMs());
        String eventType = request.playbackState().equals(room.playbackState())
                ? "PLAYBACK_SEEK" : (request.playbackState().equals("PLAYING") ? "PLAYBACK_PLAY" : "PLAYBACK_PAUSE");
        Instant now = mutationInstant();
        long sequence = room.lastSequenceNo() + 1;
        jdbc.update("""
                UPDATE watch_spaces SET playback_state = :state, last_playback_position_ms = :position,
                    playback_updated_at = :now
                WHERE id = :id AND status = 'LIVE'
                """, Map.of("state", request.playbackState(), "position", position, "now", Timestamp.from(now), "id", roomId));
        appendEvent(roomId, sequence, actor.userId(), eventType,
                "{\"playbackState\":\"" + request.playbackState() + "\",\"positionMs\":" + position + "}", now);
        advanceRoom(roomId, room.lastSequenceNo(), sequence);
        return loadRoom(roomId, actor.userId());
    }

    @Transactional
    public RoomResponse end(UUID roomId, AuthenticatedUser actor) {
        RoomRow room = lockRoom(roomId);
        requireHost(room, actor.userId());
        if (room.status().equals("ENDED")) {
            return loadRoom(roomId, actor.userId());
        }
        Instant now = mutationInstant();
        long sequence = room.lastSequenceNo() + 1;
        jdbc.update("""
                UPDATE watch_space_participants
                SET left_at = :now, leave_reason = 'ROOM_ENDED'
                WHERE watch_space_id = :roomId AND left_at IS NULL
                """, Map.of("now", Timestamp.from(now), "roomId", roomId));
        jdbc.update("""
                UPDATE watch_spaces SET status = 'ENDED', ended_reason = 'HOST_ENDED', ended_at = :now,
                    playback_state = 'PAUSED'
                WHERE id = :id AND status <> 'ENDED'
                """, Map.of("now", Timestamp.from(now), "id", roomId));
        appendEvent(roomId, sequence, actor.userId(), "ROOM_ENDED", "{\"reason\":\"HOST_ENDED\"}", now);
        advanceRoom(roomId, room.lastSequenceNo(), sequence);
        return loadRoom(roomId, actor.userId());
    }

    private RoomResponse loadRoom(UUID roomId, UUID viewerId) {
        RoomRow room = locklessRoom(roomId);
        boolean member = isMember(roomId, viewerId);
        List<RoomParticipantResponse> participants = jdbc.query("""
                SELECT u.id, u.display_name,
                       CASE WHEN u.id = :hostId THEN 'HOST' ELSE 'VIEWER' END AS room_role,
                       (p.last_seen_at >= :presenceCutoff) AS online
                FROM watch_space_participants p
                JOIN app_users u ON u.id = p.user_id
                WHERE p.watch_space_id = :roomId AND p.left_at IS NULL
                ORDER BY p.joined_at, u.id
                """, Map.of("roomId", roomId, "hostId", room.hostUserId(),
                        "presenceCutoff", Timestamp.from(mutationInstant().minusSeconds(PRESENCE_WINDOW_SECONDS))),
                (rs, rowNum) -> new RoomParticipantResponse(rs.getObject("id", UUID.class),
                        rs.getString("display_name"), rs.getString("room_role"), rs.getBoolean("online")));
        return new RoomResponse(room.id(), room.titleId(), room.titleName(), room.videoAssetUri(), room.durationMs(),
                room.name(), room.privacy(), room.status(), room.hostUserId(),
                room.hostUserId().equals(viewerId) ? "HOST" : (member ? "VIEWER" : "NONE"),
                activeParticipantCount(roomId), room.maxParticipants(), room.locked(), room.playbackState(),
                room.playbackPositionMs(), room.playbackUpdatedAt(), room.stateVersion(), room.lastSequenceNo(),
                room.createdAt(), room.startedAt(), room.endedAt(), participants);
    }

    private RoomRow lockRoom(UUID roomId) {
        List<RoomRow> rows = jdbc.query(roomSelect("FOR UPDATE"), Map.of("id", roomId), ROOM_ROW);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Watch space not found.");
        }
        return rows.getFirst();
    }

    private RoomRow locklessRoom(UUID roomId) {
        List<RoomRow> rows = jdbc.query(roomSelect(""), Map.of("id", roomId), ROOM_ROW);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Watch space not found.");
        }
        return rows.getFirst();
    }

    private static String roomSelect(String suffix) {
        return """
                SELECT ws.id, ws.title_id, t.name AS title_name, t.video_asset_uri, t.duration_ms,
                       ws.host_user_id, ws.name, ws.privacy, ws.invite_code_hash, ws.status,
                       ws.max_participants, ws.is_locked, ws.playback_state, ws.last_playback_position_ms,
                       ws.playback_updated_at, ws.state_version, ws.last_sequence_no,
                       ws.created_at, ws.started_at, ws.ended_at
                FROM watch_spaces ws JOIN titles t ON t.id = ws.title_id
                WHERE ws.id = :id
                """ + suffix;
    }

    private TitleRow publishedTitle(UUID titleId) {
        List<TitleRow> rows = jdbc.query("""
                SELECT id, name FROM titles WHERE id = :id AND status = 'PUBLISHED'
                """, Map.of("id", titleId), (rs, rowNum) -> new TitleRow(
                rs.getObject("id", UUID.class), rs.getString("name")));
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Published title not found.");
        }
        return rows.getFirst();
    }

    private boolean isMember(UUID roomId, UUID userId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM watch_spaces ws WHERE ws.id = :roomId AND ws.host_user_id = :userId)
                    OR EXISTS (SELECT 1 FROM watch_space_participants p
                               WHERE p.watch_space_id = :roomId AND p.user_id = :userId AND p.left_at IS NULL)
                """, Map.of("roomId", roomId, "userId", userId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    private boolean isActiveMember(UUID roomId, UUID userId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM watch_space_participants
                               WHERE watch_space_id = :roomId AND user_id = :userId AND left_at IS NULL)
                """, Map.of("roomId", roomId, "userId", userId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    private boolean hasMembership(UUID roomId, UUID userId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM watch_space_participants
                               WHERE watch_space_id = :roomId AND user_id = :userId)
                """, Map.of("roomId", roomId, "userId", userId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    private boolean isRemoved(UUID roomId, UUID userId) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM watch_space_moderation
                               WHERE watch_space_id = :roomId AND user_id = :userId AND removed_at IS NOT NULL)
                """, Map.of("roomId", roomId, "userId", userId), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    private int activeParticipantCount(UUID roomId) {
        Integer result = jdbc.queryForObject("""
                SELECT count(*) FROM watch_space_participants WHERE watch_space_id = :roomId AND left_at IS NULL
                """, Map.of("roomId", roomId), Integer.class);
        return result == null ? 0 : result;
    }

    private void requireHost(RoomRow room, UUID userId) {
        if (!room.hostUserId().equals(userId)) {
            throw new ApiException(ErrorCode.NOT_ROOM_HOST, "Only the host can perform this action.");
        }
    }

    private void appendEvent(UUID roomId, long sequence, UUID actor, String eventType,
                             String payload, Instant now) {
        jdbc.update("""
                INSERT INTO room_events (id, watch_space_id, sequence_no, actor_user_id,
                                         event_type, payload, occurred_at)
                VALUES (:id, :roomId, :sequence, :actor, :eventType, CAST(:payload AS jsonb), :now)
                """, Map.of("id", UUID.randomUUID(), "roomId", roomId, "sequence", sequence,
                "actor", actor, "eventType", eventType, "payload", payload, "now", Timestamp.from(now)));
    }

    private void advanceRoom(UUID roomId, long expected, long next) {
        int updated = jdbc.update("""
                UPDATE watch_spaces SET last_sequence_no = :next, state_version = state_version + 1
                WHERE id = :id AND last_sequence_no = :expected
                """, Map.of("next", next, "id", roomId, "expected", expected));
        if (updated != 1) {
            throw new ApiException(ErrorCode.CONFLICT_RETRY, "The watch space changed. Retry the action.");
        }
    }

    private Instant mutationInstant() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static RoomRow mapRoom(ResultSet rs, int rowNum) throws SQLException {
        return new RoomRow(rs.getObject("id", UUID.class), rs.getObject("title_id", UUID.class),
                rs.getString("title_name"), rs.getString("video_asset_uri"), rs.getInt("duration_ms"),
                rs.getObject("host_user_id", UUID.class), rs.getString("name"), rs.getString("privacy"),
                rs.getString("invite_code_hash"), rs.getString("status"), rs.getInt("max_participants"),
                rs.getBoolean("is_locked"), rs.getString("playback_state"),
                rs.getInt("last_playback_position_ms"), rs.getTimestamp("playback_updated_at").toInstant(),
                rs.getLong("state_version"), rs.getLong("last_sequence_no"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant());
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new ErrorDetail(field, "INVALID_VALUE", message)));
    }

    private static String truncateTitle(String title, int maxCodePoints) {
        int count = title.codePointCount(0, title.length());
        if (count <= maxCodePoints) return title;
        return title.substring(0, title.offsetByCodePoints(0, maxCodePoints));
    }

    private static ApiException inviteInvalid() {
        return new ApiException(ErrorCode.INVITE_CODE_INVALID, "The invite code is invalid or no longer active.");
    }

    private record TitleRow(UUID id, String name) {
    }

    private record RoomRow(UUID id, UUID titleId, String titleName, String videoAssetUri, int durationMs,
                           UUID hostUserId, String name, String privacy, String inviteCodeHash, String status,
                           int maxParticipants, boolean locked, String playbackState, int playbackPositionMs,
                           Instant playbackUpdatedAt, long stateVersion, long lastSequenceNo, Instant createdAt,
                           Instant startedAt, Instant endedAt) {
    }
}

