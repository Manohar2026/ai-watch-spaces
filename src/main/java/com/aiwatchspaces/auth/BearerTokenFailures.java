package com.aiwatchspaces.auth;

import com.aiwatchspaces.common.error.ErrorCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;

/** Classifies a bearer-token failure as {@code TOKEN_EXPIRED} or {@code UNAUTHENTICATED} (Appendix E #3). */
final class BearerTokenFailures {

    private BearerTokenFailures() {
    }

    static ErrorCode classify(AuthenticationException exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof JwtValidationException validation) {
                for (OAuth2Error error : validation.getErrors()) {
                    if (JwtConfig.TOKEN_EXPIRED_ERROR.equals(error.getErrorCode())) {
                        return ErrorCode.TOKEN_EXPIRED;
                    }
                }
            }
        }
        return ErrorCode.UNAUTHENTICATED;
    }
}
