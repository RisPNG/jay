# Jay server

The server keeps the group side of Jay in sync: identities, memberships, shared alarms and timers, delivery records, and alarm responses. The Android app talks to this API, which handles the database work in PostgreSQL.

It also tracks when each member is expected to answer an alarm. If no dismissal or snooze arrives before the ringing deadline, the server records an ignored outcome, including when the device is offline. With **Answer as one**, a member's dismissal, snooze, or missed response applies to the group's corresponding occurrences. Devices using the same imported profile are one member and always answer together.

While Jay is open, authenticated server-sent events tell it when something changes. Firebase and periodic synchronisation help it catch up in the background.

## Local development

For local development, a virtual environment keeps the server dependencies together. From the `server/` directory:

```sh
mise exec -- python -m venv .venv
mise exec -- .venv/bin/python -m pip install -e '.[test]'
```

The API and migrations use `DATABASE_URL` to find PostgreSQL. With a local database available, the setup looks like this:

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

The variables below are currently relevant to the official jay.poppybit.com deployment. You can self-host without Firebase or Google Play credentials. Without Firebase, Jay still synchronises on launch, on manual refresh, after local group operations, and periodically in the background. Google Play credentials are used only when `SHARED_SOUND_ACCESS` is `play`; an `everyone` server does not use them:

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

You still decide who can edit each group, and you still provide the storage. The access setting makes shared sounds available without Play verification, it does not supply somewhere to store the audio. Google Play credentials are not needed for `everyone` mode.

### Storage for shared sounds

The included Docker Compose setup earlier only runs the API and PostgreSQL, but audio storage is setup separately. Shared sounds need a private storage bucket and credentials. The project uses Backblaze B2 through its S3-compatible API, so that is the setup this guide covers. Other S3-compatible storage may work too, but it has not been tested or covered.

Once the storage and application key are set up, these variables connect the Jay API server to the bucket:

| Variable | What to provide |
| --- | --- |
| `B2_S3_ENDPOINT` | Your bucket's S3-compatible endpoint. |
| `B2_BUCKET_NAME` | The private bucket's name. |
| `B2_APPLICATION_KEY_ID` | The ID of an application key scoped to that bucket. |
| `B2_APPLICATION_KEY` | The corresponding secret key, kept on the server. |

The key needs to support uploading, reading, and deleting sound objects. Android uploads and downloads directly through temporary signed URLs, so the storage endpoint must be reachable by both the server and members' devices. The bucket itself stays private.

For example, you can run the Jay API on your own machine and keep the audio in your B2 bucket. You cover the API hosting and storage costs; users on that server do not need Play access when its policy is `everyone`. Setting `everyone` without configuring storage still leaves uploads unavailable.

## Shared links

Shared links currently use the hard-coded domain `jay.poppybit.com`, even when you change the API address in the app. For the distributed app to verify links, Android needs to retrieve `https://jay.poppybit.com/.well-known/assetlinks.json` directly over HTTPS, without authentication or redirects.

For a self-hosted server, there is no extra link setup. Invitations will still use `jay.poppybit.com` but carry your server's address in the `server` parameter, so they open the app and connect back to your server. Hosting the verification file is part of operating the official domain and publishing the distributed app.

I intend to make this configurable and will add a guide when that is available.

## Tests

A separate PostgreSQL database keeps test data away from the data you use during development. With `DATABASE_URL` pointing to that database and its migrations applied, run this from `server/`:

```sh
mise exec -- .venv/bin/pytest
```
