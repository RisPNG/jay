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
| `INVITE_LIFETIME_HOURS` | Default lifetime of a one-use invitation, 24 hours |
| `ALARM_OCCURRENCE_MONITOR_ENABLED` | Processes missing alarm outcomes on this server instance, enabled by default |
| `DEVICE_INACTIVITY_TIMEOUT_DAYS` | Removes identities unseen for this many days together with the groups they solely lead, defaulting to 120; 0 disables the sweep |
| `SHARED_SOUND_ACCESS` | Shared-sound upload and selection policy: `play` (default) requires a current Play entitlement; `everyone` grants access to every authenticated device, subject to group edit permissions |
| `B2_S3_ENDPOINT` | Backblaze B2 S3-compatible endpoint |
| `B2_BUCKET_NAME` | Private B2 bucket that stores normalised shared sounds |
| `B2_APPLICATION_KEY_ID` | B2 application key ID scoped to the sound bucket |
| `B2_APPLICATION_KEY` | B2 application key secret scoped to the sound bucket |

Although the other variables are available, they're only relevant to the official jay.poppybit.com deployment (for now). Self-hosting needs neither the Firebase nor the Google Play credentials. If Firebase is not configured, synchronisation still occurs when Jay launches, when the user requests it, after local group operations, and periodically in the background. Google Play credentials are only consulted while `SHARED_SOUND_ACCESS` is `play`; on an `everyone` server they are never used:

| Variable | Purpose |
| --- | --- |
| `ANDROID_APP_LINKS` | JSON object mapping Android package names to lists of SHA-256 signing certificate fingerprints; defaults to `{}` |
| `FIREBASE_CREDENTIALS_JSON` | Optional Firebase service-account JSON for immediate synchronisation pushes |
| `GOOGLE_PLAY_CREDENTIALS_JSON` | Optional Play Integrity service-account JSON for paid-app entitlement verification |
| `PLAY_ENTITLEMENT_LIFETIME_HOURS` | Lifetime of a verified Play entitlement, defaulting to 48 hours |

## Shared sounds on your own server

If you are hosting Jay yourself and want everyone on your server to use shared sounds, set:

```env
SHARED_SOUND_ACCESS=everyone
```

With the included Compose file, run this from the repository root:

```sh
SHARED_SOUND_ACCESS=everyone docker compose -f server/compose.yaml up --build -d
```

This allows uploads and sound selection without a Play purchase, including from GitHub and debug builds. The app learns what is available during synchronisation, and access on an `everyone` server does not expire or need Play verification.

You still decide who can edit each group, and you still provide the storage. The access setting removes the Play requirement; it does not supply somewhere to store the audio. Google Play credentials are not needed for `everyone` mode.

### Storage for shared sounds

You need your own private storage bucket and credentials. You do not need to operate a storage server yourself: this guide is specifically for setup that uses Backblaze B2 through its S3-compatible API, but any storage with a S3-compatible API, although I haven't personally tried them, so take this with a grain of salt. The included Docker Compose setup runs the API and PostgreSQL only; it does not include audio storage.

Once your storage and application key are setup, configure these variables on the Jay API server:

| Variable | What to provide |
| --- | --- |
| `B2_S3_ENDPOINT` | Your bucket's S3-compatible endpoint. |
| `B2_BUCKET_NAME` | The private bucket's name. |
| `B2_APPLICATION_KEY_ID` | The ID of an application key scoped to that bucket. |
| `B2_APPLICATION_KEY` | The corresponding secret key, kept on the server. |

The key needs to support uploading, reading, and deleting sound objects. Android uploads and downloads directly through temporary signed URLs, so the storage endpoint must be reachable by both the server and members' devices. The bucket itself stays private.

For example, you can run the Jay API on your own machine and keep the audio in your B2 bucket. You cover the API hosting and storage costs; users on that server do not need Play access when its policy is `everyone`. Setting `everyone` without configuring storage still leaves uploads unavailable.

## Shared links

Note that shared links are currently hard-coded to use `jay.poppybit.com`, and changing the API address in the app does not change this link domain. For the distributed app to verify links, Android needs to retrieve `https://jay.poppybit.com/.well-known/assetlinks.json` directly over HTTPS, without authentication or redirects.

A self-hosted server needs none of the setup in this section. Its invitations still use `jay.poppybit.com` and carry the server's own address as the `server` parameter, so they open the app and connect back to your server unchanged. The following is only for whoever operates the official `jay.poppybit.com` deployment and publishes the distributed app.

If and when this hard-coded behaviour changes, which it will, I will provide a proper guide.

## Tests

Use a separate test database, apply its migrations, then run this from `server/`:

```sh
mise exec -- .venv/bin/pytest
```
