package com.aiwatchspaces.watchspace;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/** Crockford Base32 invite codes. Only their keyed hash is persisted. */
@Component
final class InviteCodes {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Pattern CODE = Pattern.compile("[0-9ABCDEFGHJKMNPQRSTVWXYZ]{10}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] pepper;

    InviteCodes(InviteCodeProperties properties) {
        this.pepper = properties.pepper().getBytes(StandardCharsets.UTF_8);
    }

    Generated generate() {
        char[] normalized = new char[10];
        for (int i = 0; i < normalized.length; i++) {
            normalized[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        String value = new String(normalized);
        return new Generated(value.substring(0, 5) + "-" + value.substring(5), hash(value));
    }

    String hashInput(String input) {
        String normalized = input == null ? "" : input.strip().toUpperCase(Locale.ROOT).replace("-", "");
        if (!CODE.matcher(normalized).matches()) {
            return null;
        }
        return hash(normalized);
    }

    private String hash(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }

    record Generated(String displayCode, String hash) {
    }
}

