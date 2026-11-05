package com.aiwatchspaces.watchspace;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Generates Crockford Base32 invite codes and stores only their HMAC-SHA256 digest. */
@Component
final class InviteCodeService {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final byte[] pepper;
    private final SecureRandom random = new SecureRandom();

    InviteCodeService(@Value("${aiws.watchspace.invite-code-pepper:}") String configuredPepper) {
        this.pepper = configuredPepper.getBytes(StandardCharsets.UTF_8);
        if (pepper.length < 32) {
            throw new IllegalStateException("INVITE_CODE_PEPPER must contain at least 32 UTF-8 bytes.");
        }
    }

    String generate() {
        char[] code = new char[10];
        for (int i = 0; i < code.length; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code, 0, 5) + "-" + new String(code, 5, 5);
    }

    String hash(String displayedCode) {
        String normalized = normalize(displayedCode);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by the Java runtime.", impossible);
        }
    }

    static String normalize(String displayedCode) {
        String normalized = displayedCode.replace("-", "").strip().toUpperCase(Locale.ROOT);
        if (normalized.length() != 10) {
            throw new IllegalArgumentException("Invite code must contain exactly 10 characters.");
        }
        for (int i = 0; i < normalized.length(); i++) {
            boolean valid = false;
            for (char allowed : ALPHABET) {
                if (normalized.charAt(i) == allowed) {
                    valid = true;
                    break;
                }
            }
            if (!valid) {
                throw new IllegalArgumentException("Invite code contains an unsupported character.");
            }
        }
        return normalized;
    }
}
