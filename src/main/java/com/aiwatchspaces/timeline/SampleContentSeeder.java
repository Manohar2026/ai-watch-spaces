package com.aiwatchspaces.timeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aiwatchspaces.timeline.TimelineDocumentValidator.SubtitleTrackReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Explicit local/development sample seeder. It uses the same JSON Schema and R1 checks as timeline
 * ingestion; it is disabled unless AIWS_SEED_CONTENT_ENABLED=true is set.
 */
@Service
class SampleContentSeeder {

    static final UUID TITLE_ID = UUID.fromString("0c6b2679-9f94-4bbd-ae4d-47e8d7f1c001");
    static final UUID TIMELINE_VERSION_ID = UUID.fromString("0c6b2679-9f94-4bbd-ae4d-47e8d7f1c002");
    static final int DURATION_MS = 5_000;
    static final String SLUG = "the-first-bloom";
    static final String VIDEO_URI =
            "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4";

    private static final String CUE_KEY = "bloom-caption-01";
    private static final String EN_FILE = "the-first-bloom.en.vtt";
    private static final String EN_IN_FILE = "the-first-bloom.en-IN.vtt";
    private static final UUID EN_TRACK_ID = namedUuid("track:" + SLUG + ":en:v1");
    private static final UUID EN_IN_TRACK_ID = namedUuid("track:" + SLUG + ":en-IN:v1");

    private final NamedParameterJdbcTemplate jdbc;
    private final TimelineDocumentValidator validator;
    private final Path assetsDirectory;

    SampleContentSeeder(NamedParameterJdbcTemplate jdbc, TimelineDocumentValidator validator,
                        @org.springframework.beans.factory.annotation.Value("${aiws.assets.directory:./assets}")
                        String assetsDirectory) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.assetsDirectory = Path.of(assetsDirectory).toAbsolutePath().normalize();
    }

    @Transactional
    SeedResult seed(UUID uploadedBy) {
        Boolean userExists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app_users WHERE id = :id)",
                Map.of("id", uploadedBy), Boolean.class);
        if (!Boolean.TRUE.equals(userExists)) {
            throw new IllegalArgumentException("AIWS_SEED_CONTENT_UPLOADED_BY must identify an existing app user.");
        }

        byte[] documentBytes = readResource("timeline/samples/the-first-bloom.timeline.json");
        String rawDocument = new String(documentBytes, StandardCharsets.UTF_8);
        JsonNode document = com.aiwatchspaces.infrastructure.parsing.SafeTreeParsers.readJsonTree(rawDocument);

        byte[] enBytes = readResource("timeline/samples/" + EN_FILE);
        byte[] enInBytes = readResource("timeline/samples/" + EN_IN_FILE);
        AssetFile enAsset = storeImmutableAsset(EN_FILE, enBytes);
        AssetFile enInAsset = storeImmutableAsset(EN_IN_FILE, enInBytes);

        Map<UUID, SubtitleTrackReference> tracks = Map.of(
                EN_TRACK_ID, new SubtitleTrackReference(EN_TRACK_ID, TITLE_ID, "en", "ACTIVE"),
                EN_IN_TRACK_ID, new SubtitleTrackReference(EN_IN_TRACK_ID, TITLE_ID, "en-IN", "ACTIVE"));
        validator.validate(document, TITLE_ID, DURATION_MS, "en", Set.of(CUE_KEY), tracks);

        if (titleAlreadySeeded()) {
            verifyExistingSeed(enAsset, enInAsset);
            return new SeedResult(TITLE_ID, TIMELINE_VERSION_ID, true);
        }

        jdbc.update("""
                INSERT INTO titles
                    (id, slug, name, description, duration_ms, video_asset_uri,
                     default_locale, status, created_by)
                VALUES
                    (:id, :slug, :name, :description, :duration, :videoUri,
                     'en', 'PUBLISHED', :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", TITLE_ID)
                .addValue("slug", SLUG)
                .addValue("name", "The First Bloom")
                .addValue("description", "A five-second CC0 time-lapse of a flower opening in a quiet garden.")
                .addValue("duration", DURATION_MS)
                .addValue("videoUri", VIDEO_URI)
                .addValue("createdBy", uploadedBy));

        seedGenre("nature");
        seedGenre("time-lapse");
        jdbc.update("INSERT INTO title_genres (title_id, genre_id) VALUES (:title, :genre)",
                Map.of("title", TITLE_ID, "genre", genreId("nature")));
        jdbc.update("INSERT INTO title_genres (title_id, genre_id) VALUES (:title, :genre)",
                Map.of("title", TITLE_ID, "genre", genreId("time-lapse")));

        jdbc.update("""
                INSERT INTO title_timeline_versions
                    (id, title_id, version_number, status, source_format, raw_document,
                     schema_version, content_hash, validation_status, validation_errors,
                     validated_at, uploaded_by, published_at)
                VALUES
                    (:id, :titleId, 1, 'CURRENT', 'JSON', :rawDocument,
                     1, :contentHash, 'VALID', '[]'::jsonb,
                     now(), :uploadedBy, now())
                """, new MapSqlParameterSource()
                .addValue("id", TIMELINE_VERSION_ID)
                .addValue("titleId", TITLE_ID)
                .addValue("rawDocument", rawDocument)
                .addValue("contentHash", sha256(documentBytes))
                .addValue("uploadedBy", uploadedBy));

        seedSubtitleTrack(EN_TRACK_ID, "en", EN_FILE, enAsset);
        seedSubtitleTrack(EN_IN_TRACK_ID, "en-IN", EN_IN_FILE, enInAsset);

        ArrayNode events = (ArrayNode) document.path("events");
        for (JsonNode event : events) {
            UUID eventId = namedUuid("event:" + TIMELINE_VERSION_ID + ":" + event.path("eventKey").asText());
            JsonNode endNode = event.get("endMs");
            Integer endMs = endNode == null || endNode.isNull() ? null : endNode.asInt();
            String searchText = event.path("payload").fields().stream()
                    .filter(entry -> entry.getValue().isTextual())
                    .map(entry -> entry.getValue().asText())
                    .reduce((left, right) -> left + " " + right).orElse("");
            jdbc.update("""
                    INSERT INTO timeline_events
                        (id, timeline_version_id, event_key, event_type, start_ms, end_ms, payload, search_text)
                    VALUES
                        (:id, :versionId, :eventKey, :type, :startMs, :endMs,
                         CAST(:payload AS jsonb), :searchText)
                    """, new MapSqlParameterSource()
                    .addValue("id", eventId)
                    .addValue("versionId", TIMELINE_VERSION_ID)
                    .addValue("eventKey", event.path("eventKey").asText())
                    .addValue("type", event.path("type").asText())
                    .addValue("startMs", event.path("startMs").asInt())
                    .addValue("endMs", endMs, Types.INTEGER)
                    .addValue("payload", event.path("payload").toString())
                    .addValue("searchText", searchText));

            if ("VARIATION_POINT".equals(event.path("type").asText())) {
                ArrayNode options = (ArrayNode) event.path("options");
                for (JsonNode option : options) {
                    jdbc.update("""
                            INSERT INTO variation_options
                                (id, timeline_event_id, option_key, label, option_type,
                                 locale, text_value, is_default, is_approved, sort_order)
                            VALUES
                                (:id, :eventId, :optionKey, :label, :optionType,
                                 :locale, :textValue, :isDefault, :isApproved, :sortOrder)
                            """, new MapSqlParameterSource()
                            .addValue("id", namedUuid("option:" + eventId + ":" + option.path("key").asText()))
                            .addValue("eventId", eventId)
                            .addValue("optionKey", option.path("key").asText())
                            .addValue("label", option.path("label").asText())
                            .addValue("optionType", event.path("payload").path("variationType").asText())
                            .addValue("locale", option.path("locale").asText())
                            .addValue("textValue", option.path("textValue").asText())
                            .addValue("isDefault", option.path("default").asBoolean())
                            .addValue("isApproved", option.path("approved").asBoolean())
                            .addValue("sortOrder", option.path("sortOrder").asInt()));
                }
            }
        }

        return new SeedResult(TITLE_ID, TIMELINE_VERSION_ID, false);
    }

    private void seedSubtitleTrack(UUID id, String locale, String fileName, AssetFile asset) {
        jdbc.update("""
                INSERT INTO subtitle_tracks
                    (id, title_id, locale, version_number, asset_uri, checksum, status)
                VALUES
                    (:id, :titleId, :locale, 1, :assetUri, :checksum, 'ACTIVE')
                """, Map.of("id", id, "titleId", TITLE_ID, "locale", locale,
                "assetUri", asset.uri(), "checksum", asset.checksum()));

        String cueText = "en".equals(locale)
                ? "Every bloom has its own moment."
                : "Every flower has its own moment.";
        jdbc.update("""
                INSERT INTO subtitle_cues
                    (id, subtitle_track_id, cue_key, start_ms, end_ms, text_value)
                VALUES
                    (:id, :trackId, :cueKey, 2000, 4500, :text)
                """, Map.of("id", namedUuid("cue:" + id + ":" + CUE_KEY),
                "trackId", id, "cueKey", CUE_KEY, "text", cueText));
    }

    private void seedGenre(String name) {
        jdbc.update("""
                INSERT INTO genres (id, name)
                VALUES (:id, :name)
                ON CONFLICT (name) DO NOTHING
                """, Map.of("id", namedUuid("genre:" + name), "name", name));
    }

    private UUID genreId(String name) {
        return jdbc.queryForObject("SELECT id FROM genres WHERE name = :name",
                Map.of("name", name), UUID.class);
    }

    private boolean titleAlreadySeeded() {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM titles WHERE id = :id)",
                Map.of("id", TITLE_ID), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private void verifyExistingSeed(AssetFile en, AssetFile enIn) {
        Integer eventCount = jdbc.queryForObject("""
                SELECT count(*) FROM timeline_events WHERE timeline_version_id = :versionId
                """, Map.of("versionId", TIMELINE_VERSION_ID), Integer.class);
        Integer trackCount = jdbc.queryForObject("""
                SELECT count(*) FROM subtitle_tracks
                WHERE title_id = :titleId AND status = 'ACTIVE'
                """, Map.of("titleId", TITLE_ID), Integer.class);
        String validation = jdbc.queryForObject("""
                SELECT validation_status FROM title_timeline_versions WHERE id = :versionId
                """, Map.of("versionId", TIMELINE_VERSION_ID), String.class);
        List<String> checksums = jdbc.query("""
                SELECT checksum FROM subtitle_tracks WHERE title_id = :titleId ORDER BY locale
                """, Map.of("titleId", TITLE_ID), (rs, rowNum) -> rs.getString("checksum"));
        if (eventCount == null || eventCount != 4 || trackCount == null || trackCount != 2
                || !"VALID".equals(validation)
                || !checksums.containsAll(List.of(en.checksum(), enIn.checksum()))) {
            throw new IllegalStateException("Existing sample seed is incomplete or its asset checksums changed.");
        }
    }

    private AssetFile storeImmutableAsset(String fileName, byte[] bytes) {
        Path root = assetsDirectory;
        Path destination = root.resolve("subtitles").resolve(fileName).normalize();
        if (!destination.startsWith(root)) {
            throw new IllegalArgumentException("Invalid subtitle asset path.");
        }
        try {
            Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) {
                if (!sha256(Files.readAllBytes(destination)).equals(sha256(bytes))) {
                    throw new IllegalStateException(
                            "Subtitle asset is immutable once approved; publish a new track version.");
                }
            } else {
                try {
                    Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    if (!sha256(Files.readAllBytes(destination)).equals(sha256(bytes))) {
                        throw new IllegalStateException("A conflicting immutable subtitle asset already exists.", race);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not store subtitle asset " + fileName, e);
        }
        return new AssetFile("asset://subtitles/" + fileName, sha256(bytes));
    }

    private static byte[] readResource(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read sample resource " + path, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", impossible);
        }
    }

    private static UUID namedUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    record SeedResult(UUID titleId, UUID timelineVersionId, boolean alreadySeeded) {
    }

    private record AssetFile(String uri, String checksum) {
    }
}
