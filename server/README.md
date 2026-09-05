# Jay server

The server keeps the group side of Jay in sync: identities, memberships, shared alarms and timers, delivery records, and alarm responses. The Android app talks to this API; it never connects directly to PostgreSQL.

It also tracks when each member is expected to answer an alarm. If no dismissal or snooze arrives before the ringing deadline, the server records an ignored outcome, including when the device is offline. With **Answer as one**, a member's dismissal, snooze, or missed response applies to the group's corresponding occurrences. Devices using the same imported profile are one member and always answer together.

While Jay is open, authenticated server-sent events tell it when something changes. Firebase and periodic synchronisation help it catch up in the background.

## Local development

From the `server/` directory, create a virtual environment and install the server:

```sh
mise exec -- python -m venv .venv
mise exec -- .venv/bin/python -m pip install -e '.[test]'
```

Point `DATABASE_URL` at your local PostgreSQL database, apply the migrations, then start the API:

```sh
export DATABASE_URL='postgresql+psycopg://jay:jay@127.0.0.1:5432/jay'
mise exec -- .venv/bin/alembic upgrade head
mise exec -- .venv/bin/uvicorn jay_server.main:app --reload
```

If you would rather start the API and a separate development database together, run this from the repository root:

```sh
docker compose -f server/compose.yaml up --build
```

The Android emulator can reach this API at `http://10.0.2.2:8000`. Use a debug build for local HTTP testing; release builds require HTTPS.

## Configuration

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | PostgreSQL connection URL used by the API and migrations |
| `PUBLIC_URL` | Public base URL included in generated invitations |
| `ANDROID_APP_LINKS` | JSON object mapping Android package names to lists of SHA-256 signing certificate fingerprints; defaults to `{}` |
| `INVITE_LIFETIME_HOURS` | Default lifetime of a one-use invitation, 24 hours |
| `ALARM_OCCURRENCE_MONITOR_ENABLED` | Processes missing alarm outcomes on this server instance, enabled by default |
| `DEVICE_INACTIVITY_TIMEOUT_DAYS` | Removes identities unseen for this many days together with the groups they solely lead, defaulting to 120; 0 disables the sweep |
| `FIREBASE_CREDENTIALS_JSON` | Optional Firebase service-account JSON for immediate synchronisation pushes |
| `GOOGLE_PLAY_CREDENTIALS_JSON` | Optional Play Integrity service-account JSON for paid-app entitlement verification |
| `SHARED_SOUND_ACCESS` | Shared-sound upload and selection policy: `play` (default) requires a current Play entitlement; `everyone` grants access to every authenticated device, subject to group edit permissions |
| `PLAY_ENTITLEMENT_LIFETIME_HOURS` | Lifetime of a verified Play entitlement, defaulting to 48 hours |
| `B2_S3_ENDPOINT` | Backblaze B2 S3-compatible endpoint |
| `B2_BUCKET_NAME` | Private B2 bucket that stores normalised shared sounds |
| `B2_APPLICATION_KEY_ID` | B2 application key ID scoped to the sound bucket |
| `B2_APPLICATION_KEY` | B2 application key secret scoped to the sound bucket |

If Firebase is not configured, synchronisation still occurs when Jay launches, when the user requests it, after local group operations, and periodically in the background.

## Shared sounds on your own server

If you are hosting Jay yourself and want everyone on your server to use shared sounds, set:

```env
SHARED_SOUND_ACCESS=everyone
```

In Render, add `SHARED_SOUND_ACCESS` with the value `everyone` and redeploy. With the included Compose file, run this from the repository root:

```sh
SHARED_SOUND_ACCESS=everyone docker compose -f server/compose.yaml up --build -d
```

This allows uploads and sound selection without a Play purchase, including from GitHub and debug builds. The app learns what is available during synchronisation, and access on an `everyone` server does not expire or need Play verification.

You still decide who can edit each group, and you still provide the storage. The access setting removes the Play requirement; it does not supply somewhere to store the audio. Google Play credentials are not needed for `everyone` mode.

### Storage for shared sounds

You need your own private storage bucket and credentials. You do not need to operate a storage server yourself: the current setup uses Backblaze B2 through its S3-compatible API, so a managed B2 bucket works. The included Docker Compose setup runs the API and PostgreSQL only; it does not include audio storage.

Configure these variables on the Jay server:

| Variable | What to provide |
| --- | --- |
| `B2_S3_ENDPOINT` | Your bucket's S3-compatible endpoint. |
| `B2_BUCKET_NAME` | The private bucket's name. |
| `B2_APPLICATION_KEY_ID` | The ID of an application key scoped to that bucket. |
| `B2_APPLICATION_KEY` | The corresponding secret key, kept on the server. |

The key needs to support uploading, reading, and deleting sound objects. Android uploads and downloads directly through temporary signed URLs, so the storage endpoint must be reachable by both the server and members' devices. The bucket itself stays private.

For example, you can run the Jay API on your own machine and keep the audio in your B2 bucket. You cover the API hosting and storage costs; users on that server do not need Play access when its policy is `everyone`. Setting `everyone` without configuring storage still leaves uploads unavailable. Other S3-compatible providers are not documented or verified here, so do not assume they are interchangeable with the current B2 configuration.

### Changing the policy

Leave the setting unset, or use `play`, to require verified Play access. If you switch back to `play`, the server checks that requirement on every upload, upload completion, and sound selection, even before the app syncs again. Sounds already selected keep working, and editing something unrelated keeps them in place. Any value other than `play` or `everyone` is rejected at startup.

Deploy the server before the updated Android app, since the app expects the new capability response. There is no database migration for this setting.

## Shared links

Shared links currently use `jay.poppybit.com`. Changing the API address in the app does not change this link domain. For the distributed app to verify links, Android needs to retrieve `https://jay.poppybit.com/.well-known/assetlinks.json` directly over HTTPS, without authentication or redirects.

Set `ANDROID_APP_LINKS` to a JSON object such as `{"com.rispng.jay":["PRODUCTION_SHA256_FINGERPRINT"],"com.rispng.jay.debug":["PRERELEASE_SHA256_FINGERPRINT"]}`, replacing the placeholders with colon-separated SHA-256 certificate fingerprints. Include every certificate used to sign distributed APKs. For Google Play installations, use the app signing certificate from Play Console, not the upload certificate. Omit packages that should not handle links.

In Render, paste the JSON object directly into the environment value without surrounding shell quotes. If you are testing prereleases before publishing on Play, use only `{"com.rispng.jay.debug":["PRERELEASE_SHA256_FINGERPRINT"]}`. You can get the fingerprint from the keystore used by the prerelease workflow with `mise exec -- keytool -list -v -keystore /path/to/jay-prerelease.jks`, entering the password at the prompt, and copy the `SHA256` certificate fingerprint. Production GitHub APKs use the production keystore. Play-installed builds use the certificate listed under Play Console's App signing key certificate. If they differ, include both fingerprints in the `com.rispng.jay` list.

The fingerprint is a public certificate identifier, so it is safe to use here. Keep the private key and password private, and use the certificate for the build you actually distribute, not a temporary local test build. Redeploy after changing the setting.

Once Android has verified the installed app, `/join` and `/profile` links open in Jay. Browser visits go to the Play listing for `com.rispng.jay`, which needs to be available to the person opening it. After installing, they must tap the original link again.

Invitations still expire and can only be used once. A messaging app fetching a link preview does not use up the invitation, and neither does a browser visit.

Profile credentials sit in the URL fragment, the part after `#`, which browsers do not send to the server. The store redirect clears that fragment and suppresses the referrer. Jay still asks before importing a profile. All shared links use HTTPS.

Before publishing, test a signed installation by running `mise exec -- adb shell pm verify-app-links --re-verify com.rispng.jay` and then `mise exec -- adb shell pm get-app-links com.rispng.jay`. Use `com.rispng.jay.debug` for prerelease builds. Check links from a messaging app with Jay installed, both closed and already open, and with Jay uninstalled. Verify profile cancellation leaves the current identity intact. Repeat with the Play-installed build on a testing track before release.

## Render

The `render.yaml` file at the repository root sets up the Python service and PostgreSQL database. Set `PUBLIC_URL` to the deployed HTTPS address.

For prompt background delivery, add the Firebase service-account JSON as the secret `FIREBASE_CREDENTIALS_JSON`. For Play verification, put the service-account JSON from the linked Play Integrity Cloud project in `GOOGLE_PLAY_CREDENTIALS_JSON`. Configure the four B2 variables as Render secrets too. The B2 application key must stay on the server.

## Tests

Use a separate test database, apply its migrations, then run this from `server/`:

```sh
mise exec -- .venv/bin/pytest
```
