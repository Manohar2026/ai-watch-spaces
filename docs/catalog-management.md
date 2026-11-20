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

The API validates the name, duration (1 second to 24 hours), locale, genre count, and absolute HTTP(S) media URL. It creates a unique slug and immediately publishes the title. Video hosting/upload is not implemented by this form; the URL must point directly to a browser-playable media file.

## Remove a title

The admin screen sends `DELETE /api/v1/admin/titles/{titleId}`. This is a soft delete: the title status changes to `ARCHIVED`, so it no longer appears in published catalogue queries. The row is intentionally retained because watch spaces, timeline versions, subtitle tracks, and other historical data use restrictive foreign keys to titles.

Archiving does not interrupt rooms that already reference the title. It prevents the title from appearing in the catalogue for new room creation.
