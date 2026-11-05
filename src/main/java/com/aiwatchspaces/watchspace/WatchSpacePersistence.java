package com.aiwatchspaces.watchspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL persistence for the watchspace module. Every write is called from a room lane. */
@Service
class WatchSpacePersistence {

    private static final RowMapper<RoomState> ROOM_ROW = WatchSpacePersistence::mapRoom;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    WatchSpacePersistence(NamedParameterJdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Creates the room, host membership and first two ledger rows atomically.
     * An empty result means the invite digest collided with its unique index; the lane generates a
     * fresh code and retries without exposing the colliding value.
     */
    @Transactional
    Optional<CreationResult> create(
            UUID watchSpaceId, UUID hostUserId, UUID titleId, String requestedName,
            RoomState.Privacy privacy, int maxParticipants, RoomState.AiVerbosity aiVerbosity,
            boolean triviaEnabled, boolean votingEnabled, Instant scheduledAt,
            String inviteCodeHash, Instant mutationInstant) {

        List<UUID> hostRows = jdbc.query(
                "SELECT id FROM app_users WHERE id = :hostUserId FOR UPDATE",
                Map.of("hostUserId", hostUserId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (hostRows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }

        List<TitleInfo> titles = jdbc.query("""
                SELECT id, name, duration_ms
                FROM titles
                WHERE id = :titleId AND status = 'PUBLISHED'
                """, Map.of("titleId", titleId),
                (rs, rowNum) -> new TitleInfo(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getInt("duration_ms")));
        if (titles.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }
        TitleInfo title = titles.getFirst();

        Long hostedRooms = jdbc.queryForObject("""
                SELECT count(*)
                FROM watch_spaces
                WHERE host_user_id = :hostUserId AND status <> 'ENDED'
                """, Map.of("hostUserId", hostUserId), Long.class);
        if (hostedRooms != null && hostedRooms >= 5) {
            throw new ApiException(ErrorCode.HOST_ROOM_LIMIT, "You may host at most five active watch spaces.");
        }

        String name = requestedName == null ? defaultName(title.name()) : requestedName;
        int inserted = jdbc.update("""
                INSERT INTO watch_spaces
                    (id, title_id, host_user_id, name, privacy, invite_code_hash, status, playback_state,
                     max_participants, ai_verbosity, trivia_enabled, voting_enabled, scheduled_at,
                     created_at, playback_updated_at, state_version, last_sequence_no)
                VALUES
                    (:id, :titleId, :hostUserId, :name, :privacy, :inviteCodeHash, 'SCHEDULED', 'PAUSED',
                     :maxParticipants, :aiVerbosity, :triviaEnabled, :votingEnabled, :scheduledAt,
                     :createdAt, :playbackUpdatedAt, 0, 0)
                ON CONFLICT (invite_code_hash) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("id", watchSpaceId)
                .addValue("titleId", titleId)
                .addValue("hostUserId", hostUserId)
                .addValue("name", name)
                .addValue("privacy", privacy.name())
                .addValue("inviteCodeHash", inviteCodeHash)
                .addValue("maxParticipants", maxParticipants)
                .addValue("aiVerbosity", aiVerbosity.name())
                .addValue("triviaEnabled", triviaEnabled)
                .addValue("votingEnabled", votingEnabled)
                .addValue("scheduledAt", utc(scheduledAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("createdAt", utc(mutationInstant), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("playbackUpdatedAt", utc(mutationInstant), Types.TIMESTAMP_WITH_TIMEZONE));
        if (inserted == 0) {
            return Optional.empty();
        }

        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO watch_space_participants
                    (id, watch_space_id, user_id, joined_at, last_seen_at)
                VALUES
                    (:id, :watchSpaceId, :userId, :joinedAt, :lastSeenAt)
                """, new MapSqlParameterSource()
                .addValue("id", membershipId)
                .addValue("watchSpaceId", watchSpaceId)
                .addValue("userId", hostUserId)
                .addValue("joinedAt", utc(mutationInstant), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("lastSeenAt", utc(mutationInstant), Types.TIMESTAMP_WITH_TIMEZONE));

        RoomEvent created = new RoomEvent(UUID.randomUUID(), watchSpaceId, 1, hostUserId, "ROOM_CREATED",
                null, Map.of("titleId", titleId.toString()), null, null, mutationInstant);
        RoomEvent joined = new RoomEvent(UUID.randomUUID(), watchSpaceId, 2, hostUserId, "PARTICIPANT_JOINED",
                null, Map.of("userId", hostUserId.toString(), "membershipId", membershipId.toString()),
                null, null, mutationInstant);
        insertEvent(created);
        insertEvent(joined);

        int guarded = jdbc.update("""
                UPDATE watch_spaces
                SET last_sequence_no = 2
                WHERE id = :id AND last_sequence_no = 0
                """, Map.of("id", watchSpaceId));
        if (guarded != 1) {
            throw new ApiException(ErrorCode.CONFLICT_RETRY,
                    "The room changed while this command was being committed. Retry the request.");
        }

        return Optional.of(new CreationResult(loadStateInternal(watchSpaceId), List.of(created, joined)));
    }

    @Transactional(readOnly = true)
    Optional<UUID> findRoomIdByInviteHash(String inviteCodeHash) {
        List<UUID> roomIds = jdbc.query("""
                SELECT id FROM watch_spaces WHERE invite_code_hash = :inviteCodeHash
                """, Map.of("inviteCodeHash", inviteCodeHash),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return roomIds.stream().findFirst();
    }

    /**
     * Re-checks all join rules while holding the room row lock. The caller is already inside the
     * serialized room lane; the lock and sequence guard also protect the durable write boundary.
     */
    @Transactional
    JoinResult join(UUID watchSpaceId, UUID userId, String inviteCodeHash, Instant mutationInstant) {
        List<PersistedRoom> rooms = jdbc.query("""
                SELECT id, privacy, status, invite_code_hash, is_locked, max_participants,
                       state_version, last_sequence_no
                FROM watch_spaces
                WHERE id = :watchSpaceId
                FOR UPDATE
                """, Map.of("watchSpaceId", watchSpaceId),
                (rs, rowNum) -> new PersistedRoom(
                        rs.getObject("id", UUID.class),
                        RoomState.Privacy.valueOf(rs.getString("privacy")),
                        RoomState.Status.valueOf(rs.getString("status")),
                        rs.getString("invite_code_hash"),
                        rs.getBoolean("is_locked"),
                        rs.getInt("max_participants"),
                        rs.getLong("state_version"),
                        rs.getLong("last_sequence_no")));
        if (rooms.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }
        PersistedRoom room = rooms.getFirst();

        if (room.status() == RoomState.Status.ENDED) {
            throw new ApiException(ErrorCode.ROOM_ENDED, "This room has ended.");
        }

        Boolean removed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM watch_space_moderation
                    WHERE watch_space_id = :watchSpaceId AND user_id = :userId AND removed_at IS NOT NULL
                )
                """, Map.of("watchSpaceId", watchSpaceId, "userId", userId), Boolean.class);
        if (Boolean.TRUE.equals(removed)) {
            throw new ApiException(ErrorCode.REMOVED_FROM_ROOM, "You have been removed from this room.");
        }

        List<MembershipInfo> activeMemberships = jdbc.query("""
                SELECT id, joined_at
                FROM watch_space_participants
                WHERE watch_space_id = :watchSpaceId AND user_id = :userId AND left_at IS NULL
                ORDER BY joined_at DESC, id DESC
                LIMIT 1
                """, Map.of("watchSpaceId", watchSpaceId, "userId", userId),
                (rs, rowNum) -> new MembershipInfo(
                        rs.getObject("id", UUID.class), instant(rs, "joined_at")));
        if (!activeMemberships.isEmpty()) {
            MembershipInfo active = activeMemberships.getFirst();
            return new JoinResult(loadStateInternal(watchSpaceId), active.membershipId(),
                    active.joinedAt(), true, false, List.of());
        }

        Boolean hasPreviousMembership = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM watch_space_participants
                    WHERE watch_space_id = :watchSpaceId AND user_id = :userId
                )
                """, Map.of("watchSpaceId", watchSpaceId, "userId", userId), Boolean.class);
        boolean rejoined = Boolean.TRUE.equals(hasPreviousMembership);

        if (!rejoined) {
            if (room.privacy() == RoomState.Privacy.INVITE_ONLY
                    && !sameDigest(inviteCodeHash, room.inviteCodeHash())) {
                throw new ApiException(ErrorCode.INVITE_CODE_INVALID, "The invitation code is invalid.");
            }
            if (room.locked()) {
                throw new ApiException(ErrorCode.ROOM_LOCKED, "This room is locked to new members.");
            }
        }

        Long activeCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM watch_space_participants
                WHERE watch_space_id = :watchSpaceId AND left_at IS NULL
                """, Map.of("watchSpaceId", watchSpaceId), Long.class);
        if (activeCount != null && activeCount >= room.maxParticipants()) {
            throw new ApiException(ErrorCode.ROOM_FULL, "This room has reached its participant limit.");
        }

        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO watch_space_participants
                    (id, watch_space_id, user_id, joined_at, last_seen_at)
                VALUES
                    (:id, :watchSpaceId, :userId, :joinedAt, :lastSeenAt)
                """, new MapSqlParameterSource()
                .addValue("id", membershipId)
                .addValue("watchSpaceId", watchSpaceId)
                .addValue("userId", userId)
                .addValue("joinedAt", utc(mutationInstant), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("lastSeenAt", utc(mutationInstant), Types.TIMESTAMP_WITH_TIMEZONE));

        long nextSequenceNo = room.lastSequenceNo() + 1;
        RoomEvent joined = new RoomEvent(UUID.randomUUID(), watchSpaceId, nextSequenceNo, userId,
                "PARTICIPANT_JOINED", null,
                Map.of("userId", userId.toString(), "membershipId", membershipId.toString()),
                null, null, mutationInstant);
        insertEvent(joined);

        int guarded = jdbc.update("""
                UPDATE watch_spaces
                SET state_version = state_version + 1,
                    last_sequence_no = :nextSequenceNo
                WHERE id = :watchSpaceId
                  AND last_sequence_no = :expectedSequenceNo
                  AND state_version = :expectedStateVersion
                """, Map.of("watchSpaceId", watchSpaceId, "nextSequenceNo", nextSequenceNo,
                "expectedSequenceNo", room.lastSequenceNo(), "expectedStateVersion", room.stateVersion()));
        if (guarded != 1) {
            throw new ApiException(ErrorCode.CONFLICT_RETRY,
                    "The room changed while this command was being committed. Retry the request.");
        }

        return new JoinResult(loadStateInternal(watchSpaceId), membershipId, mutationInstant,
                false, rejoined, List.of(joined));
    }

    @Transactional(readOnly = true)
    RoomState loadState(UUID watchSpaceId) {
        return loadStateInternal(watchSpaceId);
    }

    private RoomState loadStateInternal(UUID watchSpaceId) {
        List<RoomState> rooms = jdbc.query("""
                SELECT w.id, w.title_id, w.host_user_id, t.name AS title_name, t.duration_ms AS title_duration_ms,
                       w.name, w.privacy, w.invite_code_hash, w.invite_code_rotated_at, w.status, w.ended_reason,
                       w.playback_state, w.max_participants, w.ai_verbosity, w.trivia_enabled, w.voting_enabled,
                       w.is_locked, w.chat_muted, w.scheduled_at, w.created_at, w.started_at, w.ended_at,
                       w.host_disconnected_at, w.session_correlation_id, w.pinned_timeline_version_id,
                       w.last_playback_position_ms, w.playback_updated_at, w.state_version, w.last_sequence_no
                FROM watch_spaces w
                JOIN titles t ON t.id = w.title_id
                WHERE w.id = :watchSpaceId
                """, Map.of("watchSpaceId", watchSpaceId), ROOM_ROW);
        if (rooms.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
        }
        RoomState room = rooms.getFirst();

        List<RoomState.Participant> participants = jdbc.query("""
                SELECT p.id, p.user_id, u.display_name, p.joined_at, p.last_seen_at, p.left_at, p.leave_reason
                FROM watch_space_participants p
                JOIN app_users u ON u.id = p.user_id
                WHERE p.watch_space_id = :watchSpaceId
                ORDER BY p.joined_at, p.id
                """, Map.of("watchSpaceId", watchSpaceId),
                (rs, rowNum) -> new RoomState.Participant(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                        instant(rs, "joined_at"), instant(rs, "last_seen_at"),
                        instant(rs, "left_at"), rs.getString("leave_reason")));

        Map<UUID, RoomState.Moderation> moderation = new HashMap<>();
        jdbc.query("""
                SELECT user_id, is_muted, muted_at, muted_by, removed_at, removed_by, updated_at
                FROM watch_space_moderation
                WHERE watch_space_id = :watchSpaceId
                """, Map.of("watchSpaceId", watchSpaceId), rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            moderation.put(userId, new RoomState.Moderation(rs.getBoolean("is_muted"),
                    instant(rs, "muted_at"), rs.getObject("muted_by", UUID.class),
                    instant(rs, "removed_at"), rs.getObject("removed_by", UUID.class),
                    instant(rs, "updated_at")));
        });

        List<Instant> lastEventTimes = jdbc.query("""
                SELECT occurred_at
                FROM room_events
                WHERE watch_space_id = :watchSpaceId AND sequence_no = :sequenceNo
                """, Map.of("watchSpaceId", watchSpaceId, "sequenceNo", room.lastSequenceNo()),
                (rs, rowNum) -> instant(rs, "occurred_at"));
        Instant mutationInstant = lastEventTimes.isEmpty() ? room.createdAt() : lastEventTimes.getFirst();

        return new RoomState(room.watchSpaceId(), room.titleId(), room.hostUserId(), room.titleName(),
                room.titleDurationMs(), room.name(), room.privacy(), room.inviteCodeHash(),
                room.inviteCodeRotatedAt(), room.status(), room.endedReason(), room.playbackState(),
                room.maxParticipants(), room.aiVerbosity(), room.triviaEnabled(), room.votingEnabled(),
                room.locked(), room.chatMuted(), room.scheduledAt(), room.createdAt(), room.startedAt(),
                room.endedAt(), room.hostDisconnectedAt(), room.sessionCorrelationId(),
                room.pinnedTimelineVersionId(), room.lastPlaybackPositionMs(), room.playbackUpdatedAt(),
                room.stateVersion(), room.lastSequenceNo(), mutationInstant, participants, moderation);
    }

    private void insertEvent(RoomEvent event) {
        jdbc.update("""
                INSERT INTO room_events
                    (id, watch_space_id, sequence_no, actor_user_id, event_type, playback_position_ms,
                     payload, idempotency_key, correlation_id, occurred_at)
                VALUES
                    (:id, :watchSpaceId, :sequenceNo, :actorUserId, :eventType, :playbackPositionMs,
                     CAST(:payload AS jsonb), :idempotencyKey, :correlationId, :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("watchSpaceId", event.watchSpaceId())
                .addValue("sequenceNo", event.sequenceNo())
                .addValue("actorUserId", event.actorUserId())
                .addValue("eventType", event.eventType())
                .addValue("playbackPositionMs", event.playbackPositionMs(), Types.INTEGER)
                .addValue("payload", jsonMapper.writeValueAsString(event.payload()))
                .addValue("idempotencyKey", event.idempotencyKey())
                .addValue("correlationId", event.correlationId())
                .addValue("occurredAt", utc(event.occurredAt()), Types.TIMESTAMP_WITH_TIMEZONE));
    }

    private static RoomState mapRoom(ResultSet rs, int rowNum) throws SQLException {
        return new RoomState(
                rs.getObject("id", UUID.class), rs.getObject("title_id", UUID.class),
                rs.getObject("host_user_id", UUID.class), rs.getString("title_name"),
                rs.getInt("title_duration_ms"), rs.getString("name"),
                RoomState.Privacy.valueOf(rs.getString("privacy")), rs.getString("invite_code_hash"),
                instant(rs, "invite_code_rotated_at"), RoomState.Status.valueOf(rs.getString("status")),
                rs.getString("ended_reason"), RoomState.PlaybackState.valueOf(rs.getString("playback_state")),
                rs.getInt("max_participants"), RoomState.AiVerbosity.valueOf(rs.getString("ai_verbosity")),
                rs.getBoolean("trivia_enabled"), rs.getBoolean("voting_enabled"), rs.getBoolean("is_locked"),
                rs.getBoolean("chat_muted"), instant(rs, "scheduled_at"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "ended_at"), instant(rs, "host_disconnected_at"),
                rs.getObject("session_correlation_id", UUID.class),
                rs.getObject("pinned_timeline_version_id", UUID.class), rs.getInt("last_playback_position_ms"),
                instant(rs, "playback_updated_at"), rs.getLong("state_version"), rs.getLong("last_sequence_no"),
                null, List.of(), Map.of());
    }

    private static boolean sameDigest(String supplied, String expected) {
        return supplied != null && expected != null
                && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.US_ASCII),
                        expected.getBytes(StandardCharsets.US_ASCII));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String defaultName(String titleName) {
        String name = titleName + " watch party";
        int points = name.codePointCount(0, name.length());
        return points <= 120 ? name : name.substring(0, name.offsetByCodePoints(0, 120));
    }

    record JoinResult(RoomState state, UUID membershipId, Instant joinedAt,
                      boolean alreadyMember, boolean rejoined, List<RoomEvent> events) {
        JoinResult {
            events = List.copyOf(events);
        }
    }

    record CreationResult(RoomState state, List<RoomEvent> events) {
        CreationResult {
            events = List.copyOf(events);
        }
    }

    private record PersistedRoom(UUID id, RoomState.Privacy privacy, RoomState.Status status,
                                 String inviteCodeHash, boolean locked, int maxParticipants,
                                 long stateVersion, long lastSequenceNo) {
    }

    private record MembershipInfo(UUID membershipId, Instant joinedAt) {
    }

    private record TitleInfo(UUID id, String name, int durationMs) {
    }
}
