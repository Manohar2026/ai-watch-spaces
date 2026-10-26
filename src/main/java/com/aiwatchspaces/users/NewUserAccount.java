package com.aiwatchspaces.users;

/** Already validated and normalized registration data; {@code passwordHash} is a DelegatingPasswordEncoder value. */
public record NewUserAccount(String email, String normalizedEmail, String passwordHash, String displayName) {

    @Override
    public String toString() {
        return "NewUserAccount[normalizedEmail=<redacted>, displayName=" + displayName + "]";
    }
}
