package com.aiwatchspaces.users;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.text.LocaleTags;
import com.aiwatchspaces.common.text.PlainText;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code GET/PATCH /api/v1/me} (SYSTEM_DESIGN §6.2). The {@code room.subtitle.variants}
 * notification that a locale change triggers arrives with WebSockets/variations (Appendix E #9).
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    record UpdateMeRequest(String displayName, String subtitleLocale) {
    }

    private final UserAccounts userAccounts;
    private final DbExecutor db;
    private final Clock clock;

    MeController(UserAccounts userAccounts, DbExecutor db, Clock clock) {
        this.userAccounts = userAccounts;
        this.db = db;
        this.clock = clock;
    }

    @GetMapping
    Mono<UserProfile> me(AuthenticatedUser caller) {
        return db.call(() -> userAccounts.findProfile(caller.userId()).orElseThrow(MeController::notFound));
    }

    @PatchMapping
    Mono<UserProfile> update(AuthenticatedUser caller, @RequestBody UpdateMeRequest request) {
        return Mono.defer(() -> {
            if (request.displayName() == null && request.subtitleLocale() == null) {
                return Mono.error(new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                        List.of(new ErrorDetail(null, "EMPTY_UPDATE",
                                "at least one of displayName, subtitleLocale is required"))));
            }
            ProfileChanges changes = new ProfileChanges(
                    request.displayName() == null ? null : PlainText.normalize("displayName", request.displayName(), 1, 80),
                    request.subtitleLocale() == null ? null
                            : LocaleTags.canonicalize("subtitleLocale", request.subtitleLocale()));
            Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
            return db.call(() -> userAccounts.updateProfile(caller.userId(), changes, now));
        });
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Resource not found.");
    }
}
