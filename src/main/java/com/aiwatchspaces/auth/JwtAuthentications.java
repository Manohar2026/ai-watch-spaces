package com.aiwatchspaces.auth;

import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.security.SystemRole;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import com.aiwatchspaces.users.UserAccounts;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Maps a validated JWT to an authentication with exactly one role authority. A token claiming
 * {@code sys_role = ADMIN} gets {@code ROLE_ADMIN} only if {@code app_users.system_role} is still
 * {@code ADMIN}, re-read on this request on the {@code db-*} executor; otherwise {@code ROLE_USER}
 * (SYSTEM_DESIGN §8.1, Appendix E #8). USER tokens need no database read.
 */
@Component
class JwtAuthentications implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    private final UserAccounts userAccounts;
    private final DbExecutor db;

    JwtAuthentications(UserAccounts userAccounts, DbExecutor db) {
        this.userAccounts = userAccounts;
        this.db = db;
    }

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        SystemRole claimed = SystemRole.valueOf(jwt.getClaimAsString(JwtConfig.SYS_ROLE_CLAIM));
        if (claimed != SystemRole.ADMIN) {
            return Mono.just(authentication(jwt, SystemRole.USER));
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        return db.call(() -> userAccounts.findSystemRole(userId).orElse(SystemRole.USER))
                .map(current -> authentication(jwt, current == SystemRole.ADMIN ? SystemRole.ADMIN : SystemRole.USER));
    }

    private static AbstractAuthenticationToken authentication(Jwt jwt, SystemRole effectiveRole) {
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(effectiveRole.authority())),
                jwt.getSubject());
    }

    /** The principal, with the effective (database-confirmed) role rather than the raw claim. */
    static AuthenticatedUser toUser(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return null;
        }
        boolean admin = token.getAuthorities().stream()
                .anyMatch(a -> SystemRole.ADMIN.authority().equals(a.getAuthority()));
        Jwt jwt = token.getToken();
        return new AuthenticatedUser(UUID.fromString(jwt.getSubject()), admin ? SystemRole.ADMIN : SystemRole.USER,
                jwt.getExpiresAt());
    }
}
