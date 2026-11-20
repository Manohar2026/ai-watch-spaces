# Deploy AI Watch Spaces on Render

This repository includes a Render Blueprint at `render.yaml`. It deploys the React frontend and Spring Boot API as one Docker web service, plus a managed PostgreSQL database. The browser calls `/api/v1` on the same origin, so a separate CORS configuration is not needed.

## 1. Create the Render resources

1. Push the deployment files to GitHub's `main` branch.
2. Sign in to [Render](https://dashboard.render.com/).
3. Choose **New → Blueprint** and connect `Manohar2026/ai-watch-spaces`.
4. Select the `main` branch and the repository's `render.yaml`.
5. Review the resources and create the Blueprint.

Render creates:
- a Docker web service named `ai-watch-spaces`;
- a PostgreSQL 17 database named `ai-watch-spaces-db`;
- generated values for `JWT_SIGNING_KEY` and `INVITE_CODE_PEPPER`;
- database host, port, name, username, and password references for the web service.

The service and database are configured for Singapore. Keep them in the same region so the app can use the database's private internal network.

The web service uses Render's `PORT` environment variable (default 10000) and checks `/actuator/health/readiness`. The container builds the React app, copies it into Spring Boot's `classpath:/static`, and runs the combined app on one public URL.

## 2. Check the first deploy

Open the web service's **Events** or **Logs** tab. Wait for the Docker build and deploy to finish. Then open the service's `onrender.com` URL.

The site should show the Watch Spaces home page. Check the backend readiness endpoint by opening:

```text
https://YOUR-SERVICE.onrender.com/actuator/health/readiness
```

A healthy response includes `"status":"UP"`.

## 3. Create the first account and seed the sample catalogue

The database starts empty. Register your own account from the deployed website first.

To seed **The First Bloom** sample title and its subtitle tracks, obtain the new account's UUID from the Render database and use it as the sample content uploader:

1. Open the Render Postgres resource and select **Connect**.
2. Copy its **External Database URL**. Use the external URL only for this local one-off query; it includes the TLS requirement.
3. In PowerShell, run the command below, replacing the URL and email with your own values. Do not paste the database URL or password into GitHub, source files, or chat.

```powershell
$databaseUrl = "PASTE_RENDER_EXTERNAL_DATABASE_URL_HERE"

docker run --rm postgres:17 psql "$databaseUrl" -c "SELECT id, email, system_role FROM app_users ORDER BY created_at;"
```

Copy the `id` for the account you registered. Promote that account to ADMIN:

```powershell
$adminEmail = "YOUR_REGISTERED_EMAIL"
docker run --rm postgres:17 psql "$databaseUrl" -c "UPDATE app_users SET system_role = 'ADMIN' WHERE normalized_email = lower('$adminEmail');"
```

The command should report `UPDATE 1`.

4. In the Render web service, open **Environment** and add:
   - `AIWS_SEED_CONTENT_ENABLED` = `true`
   - `AIWS_SEED_CONTENT_UPLOADED_BY` = the account UUID from the query above
5. Save and deploy the environment changes. On startup, the idempotent sample seeder adds The First Bloom and writes its subtitle files to the configured assets directory.
6. Sign out and sign in again. The refreshed access token will include the ADMIN role. The catalogue should show **Add title** and **Remove**.

You can leave sample seeding enabled: it is idempotent and checks the existing sample data on subsequent starts. You can also turn it off after the first successful seed.

## 4. Verify the public app

Test these actions using two separate browsers or devices:
- register and sign in;
- open the catalogue and play the sample video;
- create a watch space and join it from another browser;
- test chat, mute/unmute, and remove participant;
- end the room and confirm viewers return to the catalogue.

Use direct public MP4/WebM media URLs for any additional catalogue entries. A YouTube watch-page URL is not a direct video file URL.

## Free-tier limitations

The Blueprint uses Render's free web service and free Postgres plan so the first demo can be deployed without selecting paid compute. Render currently spins down free web services after 15 minutes without traffic, and waking them can take about a minute. Free Postgres is limited to 1 GB and expires 30 days after creation; it has no free-tier backups. Do not use this plan for data you need to keep. Upgrade the database before the expiry date or export/migrate its data first.

For a continuously available public app, use a paid web service and paid Postgres plan. Confirm current pricing in Render before upgrading.

## Operational notes

- The app uses Render's internal database hostname because the web service and Postgres resource share a region. Do not use the external Postgres URL in the running app.
- Never commit `.env`, production passwords, JWT keys, or invite-code pepper values.
- Database migrations run automatically at application startup through Flyway.
- The free web-service filesystem is ephemeral. The sample seeder is designed to recreate its subtitle files at startup; do not use this local directory for user-uploaded media. The current app accepts public media-file URLs instead of uploading videos.
