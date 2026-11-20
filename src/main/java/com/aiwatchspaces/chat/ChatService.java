package com.aiwatchspaces.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.text.PlainText;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, member-only plain-text room chat. */
@Service
public class ChatService {
    private static final RowMapper<ChatMessage> MESSAGE = ChatService::mapMessage;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ChatService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ChatMessage send(UUID roomId, AuthenticatedUser actor, String rawBody) {
        String body = PlainText.normalize("body", rawBody, 1, 2000);
        List<RoomState> states = jdbc.query("""
                SELECT status, last_sequence_no FROM watch_spaces WHERE id = :id FOR UPDATE
                """, Map.of("id", roomId), (rs, row) -> new RoomState(rs.getString("status"), rs.getLong("last_sequence_no")));
        if (states.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "Watch space not found.");
        RoomState room = states.getFirst();
        if (room.status().equals("ENDED")) throw new ApiException(ErrorCode.ROOM_ENDED, "This watch space has ended.");
        if (!activeMember(roomId, actor.userId())) {
            throw new ApiException(ErrorCode.NOT_ROOM_MEMBER, "Join this watch space to chat.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT ws.chat_muted OR COALESCE(m.is_muted, FALSE)
                FROM watch_spaces ws LEFT JOIN watch_space_moderation m
                  ON m.watch_space_id = ws.id AND m.user_id = :userId
                WHERE ws.id = :roomId
                """, Map.of("roomId", roomId, "userId", actor.userId()), Boolean.class))) {
            throw new ApiException(ErrorCode.CHAT_MUTED, "Chat is muted for this room or participant.");
        }

        UUID messageId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        long sequence = room.lastSequenceNo() + 1;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO room_events (id, watch_space_id, sequence_no, actor_user_id, event_type, payload, occurred_at)
                VALUES (:eventId, :roomId, :sequence, :actor, 'CHAT_MESSAGE_POSTED', CAST(:payload AS jsonb), :now)
                """, Map.of("eventId", eventId, "roomId", roomId, "sequence", sequence, "actor", actor.userId(),
                "payload", "{\"messageId\":\"" + messageId + "\"}", "now", now));
        int advanced = jdbc.update("""
                UPDATE watch_spaces SET last_sequence_no = :next, state_version = state_version + 1
                WHERE id = :id AND last_sequence_no = :expected
                """, Map.of("next", sequence, "id", roomId, "expected", room.lastSequenceNo()));
        if (advanced != 1) throw new ApiException(ErrorCode.CONFLICT_RETRY, "The watch space changed. Retry the action.");
        jdbc.update("""
                INSERT INTO chat_messages (id, watch_space_id, room_event_id, user_id, message_type, body, created_at)
                VALUES (:id, :roomId, :eventId, :userId, 'CHAT', :body, :createdAt)
                """, Map.of("id", messageId, "roomId", roomId, "eventId", eventId, "userId", actor.userId(),
                "body", body, "createdAt", now));
        return jdbc.queryForObject("""
                SELECT m.id, m.user_id, u.display_name, m.body, e.sequence_no, m.created_at
                FROM chat_messages m JOIN app_users u ON u.id = m.user_id
                JOIN room_events e ON e.id = m.room_event_id WHERE m.id = :id
                """, Map.of("id", messageId), MESSAGE);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(UUID roomId, AuthenticatedUser actor, long afterSequence, int limit) {
        if (limit < 1 || limit > 100) throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and 100.");
        if (!activeMember(roomId, actor.userId())) throw new ApiException(ErrorCode.NOT_ROOM_MEMBER, "Join this watch space to read chat.");
        return jdbc.query("""
                SELECT * FROM (
                    SELECT m.id, m.user_id, u.display_name, m.body, e.sequence_no, m.created_at
                    FROM chat_messages m JOIN app_users u ON u.id = m.user_id
                    JOIN room_events e ON e.id = m.room_event_id
                    WHERE m.watch_space_id = :roomId AND e.sequence_no > :afterSequence
                    ORDER BY e.sequence_no DESC LIMIT :limit
                ) recent ORDER BY sequence_no ASC
                """, Map.of("roomId", roomId, "afterSequence", Math.max(0, afterSequence), "limit", limit), MESSAGE);
    }

    private boolean activeMember(UUID roomId, UUID userId) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM watch_space_participants
                WHERE watch_space_id = :room AND user_id = :user AND left_at IS NULL)
                """, Map.of("room", roomId, "user", userId), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    private static ChatMessage mapMessage(ResultSet rs, int row) throws SQLException {
        return new ChatMessage(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("display_name"), rs.getString("body"), rs.getLong("sequence_no"),
                rs.getTimestamp("created_at").toInstant());
    }

    private record RoomState(String status, long lastSequenceNo) { }
    public record ChatMessage(UUID id, UUID userId, String displayName, String body, long sequenceNo, Instant createdAt) { }
}

