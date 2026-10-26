package com.aiwatchspaces.auth;

import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.security.AuthenticatedUser;
import com.aiwatchspaces.common.security.SystemRole;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/** Maps a validated JWT to an authentication ({@code sys_role} → {@code ROLE_<sys_role>}) and to the principal. */
final class JwtAuthentications {

    private JwtAuthentications() {
    }

    static Mono<AbstractAuthenticationToken> toAuthentication(Jwt jwt) {
        SystemRole role = SystemRole.valueOf(jwt.getClaimAsString(JwtConfig.SYS_ROLE_CLAIM));
        return Mono.just(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role.authority())),
                jwt.getSubject()));
    }

    static AuthenticatedUser toUser(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return null;
        }
        Jwt jwt = token.getToken();
        return new AuthenticatedUser(UUID.fromString(jwt.getSubject()),
                SystemRole.valueOf(jwt.getClaimAsString(JwtConfig.SYS_ROLE_CLAIM)), jwt.getExpiresAt());
    }
}
