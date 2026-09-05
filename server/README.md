# Jay server

The Jay server synchronizes device identities, groups, alarm revisions, delivery acknowledgements, and alarm outcomes. It tracks each member's expected alarm occurrences so a missing dismissal or snooze becomes an ignored outcome after the ringing deadline even when that device is offline. A group may answer as one, carrying any member's dismissal, snooze, or missed response to every member's current occurrence; devices that share an imported profile are one member and always answer as one. Authenticated server-sent events make changes appear immediately while Jay is open; Firebase and periodic synchronization recover changes while it is in the background. Android clients communicate with this API and never connect directly to PostgreSQL.

## Local development

Create a virtual environment and install the server:

```sh
mise exec -- python -m venv .venv
mise exec -- .venv/bin/python -m pip install -e '.[test]'
```

Set `DATABASE_URL` to a PostgreSQL database, apply migrations, and run the API:

```sh
export DATABASE_URL='postgresql+psycopg://jay:jay@127.0.0.1:5432/jay'
mise exec -- .venv/bin/alembic upgrade head
mise exec -- .venv/bin/uvicorn jay_server.main:app --reload
```

Alternatively, start the server and an isolated PostgreSQL database together:

```sh
docker compose -f server/compose.yaml up --build
```

The Android emulator can reach this local API at `http://10.0.2.2:8000`. Cleartext HTTP is accepted only by debug builds; release builds require HTTPS.

## Configuration

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | PostgreSQL connection URL used by the API and migrations |
| `PUBLIC_URL` | Public base URL included in generated invitations |
| `ANDROID_APP_LINKS` | JSON object mapping Android package names to lists of SHA-256 signing certificate fingerprints; defaults to `{}` |
| `INVITE_LIFETIME_HOURS` | Default lifetime of a one-use invitation, 24 hours |
| `ALARM_OCCURRENCE_MONITOR_ENABLED` | Processes missing alarm outcomes on this server instance, enabled by default |
| `DEVICE_INACTIVITY_TIMEOUT_DAYS` | Removes identities unseen for this many days together with the groups they solely lead, defaulting to 120; 0 disables the sweep |
| `FIREBASE_CREDENTIALS_JSON` | Optional Firebase service-account JSON for immediate synchronization pushes |
| `GOOGLE_PLAY_CREDENTIALS_JSON` | Optional Play Integrity service-account JSON for paid-app entitlement verification |
| `PLAY_ENTITLEMENT_LIFETIME_HOURS` | Lifetime of a verified Play entitlement, defaulting to 48 hours |
| `B2_S3_ENDPOINT` | Backblaze B2 S3-compatible endpoint |
| `B2_BUCKET_NAME` | Private B2 bucket that stores normalized shared sounds |
| `B2_APPLICATION_KEY_ID` | B2 application key ID scoped to the sound bucket |
| `B2_APPLICATION_KEY` | B2 application key secret scoped to the sound bucket |

If Firebase is not configured, synchronization still occurs when Jay launches, when the user requests it, after local group operations, and periodically in the background.

## Shared links

Jay shares HTTPS links on `jay.poppybit.com`. Serve this API at that domain so Android can retrieve `https://jay.poppybit.com/.well-known/assetlinks.json` directly over HTTPS without authentication or redirects. Set `ANDROID_APP_LINKS` to a JSON object such as `{"com.rispng.jay":["PRODUCTION_SHA256_FINGERPRINT"],"com.rispng.jay.debug":["PRERELEASE_SHA256_FINGERPRINT"]}`, replacing the placeholders with colon-separated SHA-256 certificate fingerprints. Include every certificate used to sign distributed APKs. For Google Play installations, use the app signing certificate from Play Console, not the upload certificate. Omit packages that should not handle links.

Add this variable to the Render server service's environment, pasting the JSON object directly without surrounding shell quotes. For prerelease testing before Play publication, use only `{"com.rispng.jay.debug":["PRERELEASE_SHA256_FINGERPRINT"]}`. Obtain the fingerprint from the keystore used by the prerelease workflow with `mise exec -- keytool -list -v -keystore /path/to/jay-prerelease.jks`, entering the password at the prompt, and copy the `SHA256` certificate fingerprint. Production GitHub APKs use the production keystore; Play-installed builds use the certificate under Play Console's App signing key certificate. If those certificates differ, include both fingerprints in the `com.rispng.jay` list. Fingerprints are public certificate identifiers, not private keys or passwords. Do not use fingerprints from temporary local test builds. Redeploy after changing the setting.

Installed, verified builds open `/join` and `/profile` links in Jay. Browser requests redirect to the Google Play listing for `com.rispng.jay`; that listing must be available to the recipient. After installing, tap the original link again to join or import. Invitations still expire and can be used only once. Link previews and browser requests never redeem invitations.

Profile credentials are stored in the URL fragment, which is not sent to the server. The store redirect explicitly clears the fragment and suppresses the referrer. Jay asks for confirmation before importing a profile. Shared links use HTTPS only.

Test a signed installation before publishing by running `mise exec -- adb shell pm verify-app-links --re-verify com.rispng.jay` and then `mise exec -- adb shell pm get-app-links com.rispng.jay`. Use `com.rispng.jay.debug` for prerelease builds. Check links from a messaging app with Jay installed, both closed and already open, and with Jay uninstalled. Verify profile cancellation leaves the current identity intact. Repeat with the Play-installed build on a testing track before release.

## Render

The repository-root `render.yaml` provisions the Python service and PostgreSQL database. Set `PUBLIC_URL` to the deployed HTTPS URL. To enable immediate delivery, store the Firebase service-account JSON in the secret `FIREBASE_CREDENTIALS_JSON` environment variable. To verify paid Play installations, store the service-account JSON from the linked Play Integrity Cloud project in `GOOGLE_PLAY_CREDENTIALS_JSON`. Configure the four B2 variables as Render secrets; the application key must stay server-side.

## Tests

Apply migrations to a test database and run:

```sh
mise exec -- .venv/bin/pytest
```
