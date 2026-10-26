package com.aiwatchspaces.auth;

import java.util.UUID;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.security.SystemRole;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.users.UserAccounts;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Global ADMIN check for admin endpoints: the token must carry {@code ROLE_ADMIN} and the
 * database must still say {@code ADMIN}, so a demotion takes effect immediately (SYSTEM_DESIGN §8.1).
 */
@Component
class AdminAuthorizationManager implements ReactiveAuthorizationManager<AuthorizationContext> {

    private final UserAccounts userAccounts;
    private final DbExecutor db;

    AdminAuthorizationManager(UserAccounts userAccounts, DbExecutor db) {
        this.userAccounts = userAccounts;
        this.db = db;
    }

    @Override
    public Mono<AuthorizationResult> authorize(Mono<Authentication> authentication, AuthorizationContext context) {
        return authentication
                .filter(Authentication::isAuthenticated)
                .mapNotNull(JwtAuthentications::toUser)
                .filter(user -> user.systemRole() == SystemRole.ADMIN)
                .map(AuthenticatedUser::userId)
                .flatMap(this::isAdminInDatabase)
                .<AuthorizationResult>map(AuthorizationDecision::new)
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    private Mono<Boolean> isAdminInDatabase(UUID userId) {
        return db.call(() -> userAccounts.findSystemRole(userId).map(role -> role == SystemRole.ADMIN).orElse(false));
    }
}
