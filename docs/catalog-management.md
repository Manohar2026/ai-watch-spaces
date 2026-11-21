# Catalogue administration

## Permissions

Only accounts with the global `ADMIN` role can create and archive catalogue titles. Registration intentionally creates `USER` accounts; there is no public admin signup endpoint.

For local development, promote the intended account after registering it:

```bash
docker compose exec -T postgres psql -U aiws -d aiws -c "UPDATE app_users SET system_role = 'ADMIN' WHERE normalized_email = lower('you@example.com');"
```

Replace the database name/user if your `.env` uses values other than `aiws`. Sign out and sign in again after changing the role.

## Create a title

The admin catalogue screen sends `POST /api/v1/admin/titles` with this shape:

```json
{
  "name": "Example title",
  "description": "A short description",
  "durationMs": 30000,
  "videoAssetUri": "https://cdn.example.test/example.mp4",
  "defaultLocale": "en-IN",
  "genres": ["documentary", "nature"]
}
```

The API validates the name, duration (1 second to 24 hours), locale, genre count, and absolute HTTP(S) media URL. It creates a unique slug and immediately publishes the title. Video upload is not implemented by this form. For direct media, use a public browser-playable MP4/WebM URL. You can also use an individual YouTube watch, share, Shorts, or embed URL when the video owner allows embedding. The YouTube IFrame Player API loads the video directly from YouTube; Watch Spaces does not download or proxy the video. Enter the video's full duration in seconds because the shared seek bar uses the catalogue duration. Playlists and live-stream-specific controls are not supported by the current room player.

## Remove a title

The admin screen sends `DELETE /api/v1/admin/titles/{titleId}`. This is a soft delete: the title status changes to `ARCHIVED`, so it no longer appears in published catalogue queries. The row is intentionally retained because watch spaces, timeline versions, subtitle tracks, and other historical data use restrictive foreign keys to titles.

Archiving does not interrupt rooms that already reference the title. It prevents the title from appearing in the catalogue for new room creation.


## Supported YouTube URLs

Supported examples include `https://www.youtube.com/watch?v=VIDEO_ID`, `https://youtu.be/VIDEO_ID`, `https://www.youtube.com/shorts/VIDEO_ID`, and `https://www.youtube.com/embed/VIDEO_ID`. The video must be public and permitted for embedding. Private, removed, age/region-restricted, or embedding-disabled videos may fail in the official YouTube player. YouTube controls its own advertisements and access restrictions; the application does not bypass them.
