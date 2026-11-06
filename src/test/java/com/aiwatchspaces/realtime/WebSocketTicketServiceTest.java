package com.aiwatchspaces.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.security.SystemRole;
import org.junit.jupiter.api.Test;

class WebSocketTicketServiceTest {

    @Test
    void ticketIsBoundToOneUserAndRoomAndCanBeConsumedOnlyOnce() {
        Instant now = Instant.parse("2026-11-06T10:00:00Z");
        MutableClock clock = new MutableClock(now);
        WebSocketTicketService service = new WebSocketTicketService(clock);
        UUID roomId = UUID.randomUUID();
        AuthenticatedUser caller = new AuthenticatedUser(
                UUID.randomUUID(), SystemRole.USER, now.plusSeconds(300));

        WebSocketTicketService.IssuedWebSocketTicket issued = service.issue(caller, roomId);

        assertThat(issued.value()).isNotBlank();
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(30));
        assertThat(service.outstandingTicketCount()).isEqualTo(1);
        assertThat(service.consume(issued.value(), UUID.randomUUID())).isEmpty();
        assertThat(service.consume(issued.value(), roomId)).isEmpty();

        WebSocketTicketService.IssuedWebSocketTicket second = service.issue(caller, roomId);
        assertThat(service.consume(second.value(), roomId)).contains(caller);
        assertThat(service.consume(second.value(), roomId)).isEmpty();
        assertThat(service.outstandingTicketCount()).isZero();
    }

    @Test
    void expiredTicketCannotBeConsumed() {
        Instant now = Instant.parse("2026-11-06T10:00:00Z");
        MutableClock clock = new MutableClock(now);
        WebSocketTicketService service = new WebSocketTicketService(clock);
        UUID roomId = UUID.randomUUID();
        AuthenticatedUser caller = new AuthenticatedUser(
                UUID.randomUUID(), SystemRole.USER, now.plusSeconds(300));
        WebSocketTicketService.IssuedWebSocketTicket issued = service.issue(caller, roomId);

        clock.set(now.plusSeconds(31));

        assertThat(service.consume(issued.value(), roomId)).isEmpty();
    }

    @Test
    void expiredTicketsAreRemovedByTheScheduledCleanup() {
        Instant now = Instant.parse("2026-11-06T10:00:00Z");
        MutableClock clock = new MutableClock(now);
        WebSocketTicketService service = new WebSocketTicketService(clock);
        AuthenticatedUser caller = new AuthenticatedUser(
                UUID.randomUUID(), SystemRole.USER, now.plusSeconds(300));

        service.issue(caller, UUID.randomUUID());
        clock.set(now.plusSeconds(31));

        assertThat(service.purgeExpiredTicketsNow()).isEqualTo(1);
        assertThat(service.outstandingTicketCount()).isZero();
    }

    @Test
    void ticketCannotOutliveAccessToken() {
        Instant now = Instant.parse("2026-11-06T10:00:00Z");
        WebSocketTicketService service = new WebSocketTicketService(Clock.fixed(now, ZoneOffset.UTC));
        UUID roomId = UUID.randomUUID();
        AuthenticatedUser caller = new AuthenticatedUser(
                UUID.randomUUID(), SystemRole.USER, now.plusSeconds(5));

        WebSocketTicketService.IssuedWebSocketTicket issued = service.issue(caller, roomId);

        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(5));
    }

    @Test
    void alreadyExpiredAccessTokenCannotMintTicket() {
        Instant now = Instant.parse("2026-11-06T10:00:00Z");
        WebSocketTicketService service = new WebSocketTicketService(Clock.fixed(now, ZoneOffset.UTC));
        AuthenticatedUser caller = new AuthenticatedUser(
                UUID.randomUUID(), SystemRole.USER, now.minusSeconds(1));

        assertThatThrownBy(() -> service.issue(caller, UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void set(Instant next) {
            instant.set(next);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
