# Frontend catalogue integration

**Status:** Initial catalogue UI and API integration are committed on `feature/catalog-ui`; frontend CI and local browser verification are pending.

## Backend API consumed

The page uses the published catalogue read endpoints from backend M3:

- `GET /api/v1/titles?page=0&size=12&query=&genre=`
- `GET /api/v1/titles/{titleId}`

Every request includes the current Bearer access token. If an API call returns HTTP 401, the page attempts one refresh-token rotation through the auth provider and retries once with the new access token. The auth provider keeps refresh single-flight to avoid replaying a rotated refresh token.

### List response shape

```json
{
  "items": [
    {
      "id": "title-uuid",
      "slug": "the-first-bloom",
      "name": "The First Bloom",
      "description": "A short nature time-lapse.",
      "durationMs": 5000,
      "videoAssetUri": "https://example.test/sample.mp4",
      "defaultLocale": "en",
      "genres": ["nature", "time-lapse"]
    }
  ],
  "page": 0,
  "size": 12,
  "totalElements": 1
}
```

### UI behavior

- Search sends the trimmed query to the backend; filtering is server-side.
- Genre filtering sends the entered genre to the backend; the UI does not assume that the current page contains all available genres.
- Pagination follows the backend's zero-based `page`, `size`, and `totalElements` fields.
- The detail panel requests the selected title's full details from the backend.
- Media links are rendered only for HTTP or HTTPS asset URIs.
- Loading, empty, error, retry, and page-navigation states are explicit.
- Catalogue access is protected; the UI redirects to the landing page after local sign-out.

## Scope not yet implemented

- The backend does not yet expose a dedicated genres endpoint, so genre is a free-text filter.
- Catalogue cards currently use generated CSS artwork rather than stored poster images; the current title DTO has no poster field.
- The detail panel opens the media URL in a new tab; it is not an in-app player.
- Watch-space creation, room joining, synchronized playback and chat are not implemented in this frontend slice.
- Authentication is still memory-only. Reloading the tab requires signing in again.

## Verification

Run:

```bash
cd frontend
npm install
npm run typecheck
npm run build
```

Then start PostgreSQL/backend with the optional M3 sample seeder enabled, register/login, open the catalogue, search for `The First Bloom`, filter by `nature`, page through results, and open the detail panel.

GitHub Actions is configured to run dependency installation, TypeScript checking and the Vite production build for this branch and its pull request to `feature/m3-catalog-timeline`. A passing build does not substitute for the local browser/API walkthrough.
