# M3 — Catalogue, timeline metadata and subtitles

**Status:** Implemented and merged into `main` on 2026-11-04. Backend CI run #13 passed: 67 unit tests and 71 integration tests, zero failures, errors, or skips.
**Design basis:** `SYSTEM_DESIGN.md` v0.4, milestone M3; canonical target schema `DATABASE_SCHEMA.sql`.

## Scope delivered in this slice

- Flyway V2 adds the catalogue, timeline-version, timeline-event, subtitle-track, subtitle-cue and approved-variation tables. The migration is the V2 slice extracted from the canonical schema.
- Timeline JSON Schema v1 is checked in at `src/main/resources/timeline/schema-v1.json` (Draft 2020-12).
- A sample title, timeline, and English / English-India subtitle assets are checked in as seed resources.
- Timeline validation checks schema, title/duration agreement, unique event keys, time ranges, plain text, cue references, option approvals/defaults, locale tags, and same-title subtitle swaps.
- Read endpoints expose published catalogue titles, the current timeline segment and the active subtitle-track metadata.
- Subtitle files are written to the local asset directory with SHA-256 checksums. Existing approved asset bytes are not overwritten.

This milestone does not implement title administration, timeline upload/validation/publish APIs, watch spaces, room-pinned timeline selection, playback, chat, or WebSockets. Those belong to later milestones. In particular, the optional `watchSpaceId` timeline-read behavior becomes meaningful after the M4 room and membership tables exist; M3 serves the current published timeline only.

## Read APIs

All routes below require a valid Bearer access token.

| Method and route | Behavior |
|---|---|
| `GET /api/v1/titles?page=0&size=20&query=&genre=` | Published titles only; optional name/description query and normalized genre filter. Page size is 1–50. |
| `GET /api/v1/titles/{titleId}` | Published title details and genres. |
| `GET /api/v1/titles/{titleId}/timeline?from={seconds}&to={seconds}` | Current timeline events overlapping the requested range. `from`/`to` are seconds; persisted positions and `startMs`/`endMs` are milliseconds; `ts` is seconds. Range must be non-negative, ordered, and no wider than 3,600 seconds. |
| `GET /api/v1/titles/{titleId}/subtitles?locale=en-IN` | Active subtitle-track metadata for the requested BCP 47 locale. Omitting locale uses the title's default locale. |

Timeline intervals use inclusive overlap: an interval is returned when `startMs <= toMs` and `endMs >= fromMs`. A point event is returned when its start is inside the requested range. Results are ordered by `startMs`, then `eventKey`. Only approved variation-option labels are exposed.

The subtitle endpoint returns metadata (`trackId`, `locale`, `versionNumber`, `assetUri`, `checksum`). It does not yet serve the VTT bytes over HTTP; the current assets are in the configured local asset directory. A browser media-asset route is a later integration task.

## Sample seed

The sample is **The First Bloom**, a short nature/time-lapse entry with four timeline events: one scene interval, trivia, glossary entry, and a subtitle-text variation point. It includes active `en` and `en-IN` subtitle tracks with the same stable cue key.

The sample seeder is deliberately opt-in. It requires a registered user because `title_timeline_versions.uploaded_by` is a foreign key to `app_users`.

### Docker Compose

1. Copy `.env.example` to `.env`. Set DB credentials and a valid signing key (`openssl rand -base64 32`).
2. Start PostgreSQL and the application using the normal README steps.
3. Register a user and note the returned `userId`:

   ```bash
   curl -X POST http://localhost:8080/api/v1/auth/register \
     -H 'Content-Type: application/json' \
     -d '{"email":"seed-admin@example.test","password":"correct horse battery staple","displayName":"Seed Admin"}'
   ```

4. Set these values in `.env`:

   ```dotenv
   AIWS_SEED_CONTENT_ENABLED=true
   AIWS_SEED_CONTENT_UPLOADED_BY=<userId returned by registration>
   ```

5. Restart the app so the startup runner can seed the content:

   ```bash
   docker compose up -d --build app
   ```

6. Call `GET /api/v1/titles` with a Bearer access token. The seeder is idempotent: if enabled on later starts, it verifies the existing sample and its asset checksums instead of inserting duplicates.
7. Disable the seed runner by setting `AIWS_SEED_CONTENT_ENABLED=false` when you no longer need startup seeding.

The Compose file mounts the local asset volume at `/var/lib/aiws/assets`. The app container receives that path through `AIWS_ASSETS_DIRECTORY`. Tests use `target/test-assets`, which is ignored by Git.

### Tests

The M3 test set covers schema acceptance/rejection, timeline cross-reference validation, range and unit conversion, title/timeline/subtitle API reads, V2 migration parity with the target schema, sample seeder idempotence, subtitle immutability, and SHA-256 equality between persisted checksums and stored VTT files.

Run the full backend checks with:

```bash
./mvnw -B verify
```

GitHub Actions uses PostgreSQL Testcontainers for integration tests. **Verified:** run [#13](https://github.com/Manohar2026/ai-watch-spaces/actions/runs/41876458081) passed on source commit `9524342a6c3bef3a7eeaaf54be0f04ad5cc27f68`: 67 unit tests and 71 integration tests, zero failures, errors, or skips. M3's schema, backend APIs, validation and tests are present on `main`.
