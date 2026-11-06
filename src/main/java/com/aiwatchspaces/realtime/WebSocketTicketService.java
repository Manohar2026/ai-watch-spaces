package com.aiwatchspaces.realtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Short-lived, single-use browser WebSocket credentials. Only SHA-256 digests are retained in
 * memory; tickets are scoped to one user and one room and never appear in a URL.
 */
@Component
final class WebSocketTicketService {

    static final Duration TICKET_TTL = Duration.ofSeconds(30);
    private static final int RANDOM_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    WebSocketTicketService(Clock clock) {
        this.clock = clock;
    }

    IssuedWebSocketTicket issue(AuthenticatedUser caller, UUID watchSpaceId) {
        Instant now = Instant.now(clock);
        if (!caller.tokenExpiresAt().isAfter(now)) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED, "The access token has expired.");
        }
        Instant expiresAt = min(now.plus(TICKET_TTL), caller.tokenExpiresAt());
        purgeExpired(now);

        byte[] randomValue = new byte[RANDOM_BYTES];
        String raw;
        String digest;
        do {
            random.nextBytes(randomValue);
            raw = Base64.getUrlEncoder().withoutPadding().encodeToString(randomValue);
            digest = digest(raw);
        } while (tickets.putIfAbsent(digest,
                new Ticket(caller.userId(), caller.systemRole(), caller.tokenExpiresAt(),
                        watchSpaceId, expiresAt)) != null);

        return new IssuedWebSocketTicket(raw, expiresAt);
    }

    /**
     * Consumes a ticket even when it is presented for a different room. This prevents a captured
     * credential from being probed across endpoints or replayed after a failed handshake.
     */
    Optional<AuthenticatedUser> consume(String rawTicket, UUID requestedRoomId) {
        if (rawTicket == null || rawTicket.isBlank() || requestedRoomId == null) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(digest(rawTicket));
        if (ticket == null) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        if (!ticket.watchSpaceId().equals(requestedRoomId)
                || !ticket.expiresAt().isAfter(now)
                || !ticket.accessTokenExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedUser(
                ticket.userId(), ticket.systemRole(), ticket.accessTokenExpiresAt()));
    }

    int outstandingTicketCount() {
        return tickets.size();
    }

    @Scheduled(fixedDelay = 30_000L)
    void purgeExpiredTickets() {
        purgeExpired(Instant.now(clock));
    }

    int purgeExpiredTicketsNow() {
        int before = tickets.size();
        purgeExpired(Instant.now(clock));
        return before - tickets.size();
    }

    private void purgeExpired(Instant now) {
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static String digest(String rawTicket) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawTicket.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available.", impossible);
        }
    }

    record IssuedWebSocketTicket(String value, Instant expiresAt) {
    }

    private record Ticket(UUID userId, com.aiwatchspaces.common.security.SystemRole systemRole,
                          Instant accessTokenExpiresAt, UUID watchSpaceId, Instant expiresAt) {
    }
}
