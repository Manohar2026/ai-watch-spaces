package com.aiwatchspaces.auth.web;

import java.time.Instant;

import com.aiwatchspaces.users.UserProfile;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request/response bodies of {@code /api/v1/auth} (SYSTEM_DESIGN §6.2). toString never shows secrets. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(@NotBlank @Email @Size(max = 320) String email,
                                  @NotNull String password,
                                  @NotNull String displayName) {
        @Override
        public String toString() {
            return "RegisterRequest[email=<redacted>, password=<redacted>, displayName=" + displayName + "]";
        }
    }

    public record RegisterResponse(String userId) {
    }

    public record LoginRequest(@NotBlank @Size(max = 320) String email, @NotNull String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=<redacted>, password=<redacted>]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=<redacted>]";
        }
    }

    public record LoginResponse(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                                Instant refreshTokenExpiresAt, UserProfile user) {
        @Override
        public String toString() {
            return "LoginResponse[<redacted>]";
        }
    }

    public record TokenPairResponse(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                                    Instant refreshTokenExpiresAt) {
        @Override
        public String toString() {
            return "TokenPairResponse[<redacted>]";
        }
    }
}
