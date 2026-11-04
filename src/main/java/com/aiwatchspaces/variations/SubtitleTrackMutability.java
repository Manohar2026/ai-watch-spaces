package com.aiwatchspaces.variations;

/**
 * R13 application guard for future subtitle authoring operations. Once a track has been approved,
 * its cues and asset are immutable even after it is archived; edits must create a new version.
 */
public final class SubtitleTrackMutability {

    private SubtitleTrackMutability() {
    }

    public static void requireCueOrAssetMutationAllowed(String status) {
        if ("APPROVED".equals(status) || "ACTIVE".equals(status) || "ARCHIVED".equals(status)) {
            throw new IllegalStateException(
                    "Approved subtitle tracks are immutable; create a new version_number instead.");
        }
        if (!"DRAFT".equals(status)) {
            throw new IllegalArgumentException("Unknown subtitle track status: " + status);
        }
    }
}
