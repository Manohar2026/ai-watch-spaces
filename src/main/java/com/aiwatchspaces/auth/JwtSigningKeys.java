package com.aiwatchspaces.auth;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decodes and checks the HS256 signing key (SYSTEM_DESIGN §8.1): base64, at least 256 bits.
 * Error messages never contain the key.
 */
final class JwtSigningKeys {

    static final int MIN_KEY_BYTES = 32;

    private JwtSigningKeys() {
    }

    static SecretKey decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("JWT_SIGNING_KEY is missing: set it to base64 of at least "
                    + MIN_KEY_BYTES + " random bytes");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SIGNING_KEY is not valid base64");
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SIGNING_KEY must decode to at least " + MIN_KEY_BYTES
                    + " bytes (256 bits), was " + bytes.length + " bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
