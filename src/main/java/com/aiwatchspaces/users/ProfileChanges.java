package com.aiwatchspaces.users;

/** Validated profile changes; {@code null} means unchanged. */
public record ProfileChanges(String displayName, String subtitleLocale) {
}
