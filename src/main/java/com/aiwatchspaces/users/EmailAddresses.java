package com.aiwatchspaces.users;

import java.util.Locale;

/** Email normalization used for uniqueness and login lookup (SYSTEM_DESIGN Appendix E #10). */
public final class EmailAddresses {

    private EmailAddresses() {
    }

    public static String trim(String email) {
        return email == null ? null : email.strip();
    }

    public static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
