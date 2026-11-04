package com.aiwatchspaces.variations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** R13: an approved track's cues and media asset are immutable for every later status. */
class SubtitleTrackMutabilityTest {

    @Test
    void draftTrackMayBeEdited() {
        assertThatCode(() -> SubtitleTrackMutability.requireCueOrAssetMutationAllowed("DRAFT"))
                .doesNotThrowAnyException();
    }

    @Test
    void approvedTrackIsImmutable() {
        assertThatThrownBy(() -> SubtitleTrackMutability.requireCueOrAssetMutationAllowed("APPROVED"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activeTrackIsImmutable() {
        assertThatThrownBy(() -> SubtitleTrackMutability.requireCueOrAssetMutationAllowed("ACTIVE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void archivedTrackRemainsImmutable() {
        assertThatThrownBy(() -> SubtitleTrackMutability.requireCueOrAssetMutationAllowed("ARCHIVED"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> SubtitleTrackMutability.requireCueOrAssetMutationAllowed("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
