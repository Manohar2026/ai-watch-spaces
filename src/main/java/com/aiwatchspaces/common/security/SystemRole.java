package com.aiwatchspaces.common.security;

/** Global role ({@code app_users.system_role}). Room roles (HOST/VIEWER) are separate. */
public enum SystemRole {
    USER,
    ADMIN;

    /** The Spring Security authority for this role, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
