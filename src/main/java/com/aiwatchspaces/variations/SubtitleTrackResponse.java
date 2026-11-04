package com.aiwatchspaces.variations;

import java.util.UUID;

/** Metadata for one active subtitle track; asset bytes are served by the later media-asset slice. */
public record SubtitleTrackResponse(
        UUID trackId,
        String locale,
        int versionNumber,
        String assetUri,
        String checksum) {
}
