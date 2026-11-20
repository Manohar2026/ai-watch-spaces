package com.aiwatchspaces.watchspace;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Secret used to store invite codes as HMACs, never as plaintext. */
@ConfigurationProperties("aiws.invite-codes")
public record InviteCodeProperties(String pepper) {

    public InviteCodeProperties {
        if (pepper == null || pepper.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("aiws.invite-codes.pepper must contain at least 32 UTF-8 bytes");
        }
    }

    @Override
    public String toString() {
        return "InviteCodeProperties[pepper=<redacted>]";
    }
}

