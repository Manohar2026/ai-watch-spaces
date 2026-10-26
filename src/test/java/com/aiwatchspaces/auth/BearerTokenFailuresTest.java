package com.aiwatchspaces.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.aiwatchspaces.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class BearerTokenFailuresTest {

    @Test
    void expiredIsDistinguishedFromOtherFailures() {
        var expired = new JwtValidationException("expired", List.of(new OAuth2Error(JwtConfig.TOKEN_EXPIRED_ERROR)));
        assertThat(BearerTokenFailures.classify(new InvalidBearerTokenException("x", expired)))
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
        var badIssuer = new JwtValidationException("iss", List.of(new OAuth2Error("invalid_token")));
        assertThat(BearerTokenFailures.classify(new InvalidBearerTokenException("x", badIssuer)))
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(BearerTokenFailures.classify(new InvalidBearerTokenException("x")))
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }
}
