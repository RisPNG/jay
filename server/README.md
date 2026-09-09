# Jay server

The server keeps the group side of Jay in sync: identities, memberships, shared alarms and timers, delivery records, and alarm responses. Django REST Framework handles JSON requests and the Django ORM owns PostgreSQL state. Channels handles authenticated live streams. A separate worker handles alarm deadlines, push delivery and audio verification.

It also tracks when each member is expected to answer an alarm. If no dismissal or snooze arrives before the ringing deadline, the server records an ignored outcome, including when the device is offline. With **Answer as one**, a member's dismissal, snooze, or missed response applies to the group's corresponding occurrences. Devices using the same imported profile are one member and always answer together.

While Jay is open, authenticated server-sent events tell it when something changes. Firebase and periodic synchronisation help it catch up in the background.

## Local development

The checked-in locks target Linux x86-64 and Python 3.11.14. Other self-host platforms require matching verified dependency artifacts. For local development, a virtual environment keeps the server dependencies together. From the `server/` directory:

```sh
mise exec -- python -m venv .venv
mise exec -- .venv/bin/python -m pip install --require-hashes -r requirements-test.lock
mise exec -- .venv/bin/python -m pip install --no-deps -e .
```

The API and migrations use `DATABASE_URL` to find PostgreSQL. With a local database available, the setup looks like this:

```sh
export DATABASE_URL='postgresql://jay:jay@127.0.0.1:5432/jay'
export SECRET_KEY='a-development-only-secret'
mise exec -- .venv/bin/python manage.py migrate --noinput
mise exec -- .venv/bin/uvicorn jay_server.asgi:application --reload
```

Run `mise exec -- .venv/bin/python manage.py run_worker` in another terminal. The web process does not run background jobs. `.env` loading is opt-in: set `LOAD_ENV_FILE=true` to read `server/.env`; exported variables take precedence. The example file lists configuration without credentials.

If you would rather start the API and a separate development database together, run this from the repository root:

```sh
mise exec -- docker compose -f server/compose.yaml up --build
```

Set `SECRET_KEY` before starting Compose. Its migration service completes before web and worker start. For an initial installation, use an empty PostgreSQL database and apply its schema with `manage.py migrate`.

The Android emulator can reach this API at `http://10.0.2.2:8000`. Use a debug build for local HTTP testing; release builds require HTTPS.

## Configuration

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | PostgreSQL connection URL used by the API and migrations |
| `PUBLIC_URL` | Public base URL included in generated invitations |
| `INVITE_LIFETIME_HOURS` | Default lifetime of a one-use invitation, 24 hours |
| `SECRET_KEY` | Required signing secret, identical on web and worker replicas |
| `ALLOWED_HOSTS` | Comma-separated public hostnames; include the Render hostname and custom domain |
| `TRUST_PROXY` | Trust the HTTPS forwarding header only when requests pass through a trusted proxy |
| `DATABASE_POOL_SIZE` | Maximum pooled connections per process; size against the database connection budget |
| `IDENTITY_INACTIVITY_TIMEOUT_DAYS` | Removes identities unseen for this many days together with the groups they solely lead, defaulting to 120; 0 disables the sweep |
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
SHARED_SOUND_ACCESS=everyone mise exec -- docker compose -f server/compose.yaml up --build -d
```

This allows uploads and sound selection without a Play purchase, including from GitHub and debug builds. The app learns what is available during synchronisation, and access on an `everyone` server does not expire or need Play verification.

You still decide who can edit each group, and you still provide the storage. The access setting makes shared sounds available without Play verification, it does not supply somewhere to store the audio. Google Play credentials are not needed for `everyone` mode.

### Storage for shared sounds

The included Docker Compose setup earlier runs web, worker, migrations and PostgreSQL, but audio storage is setup separately. Shared sounds need a private storage bucket and credentials. The project uses Backblaze B2 through its S3-compatible API, so that is the setup this guide covers. Other S3-compatible storage may work too, but it has not been tested or covered.

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

After installing the test dependencies above, install PostgreSQL for the test runner:

```sh
mise install postgres@18.6
```

From the repository root, run:

```sh
mise exec -- bash server/test.sh
```

The runner creates a temporary PostgreSQL cluster on a private Unix socket, applies all migrations, and removes the cluster when the tests finish. It disables `.env` loading and explicitly isolates all database and provider settings. Tests never use your development database or service credentials. CI uses the same runner.

Pass pytest arguments to run a smaller selection:

```sh
mise exec -- bash server/test.sh -q tests/test_django_api.py
```


## API

`/docs` serves the pinned, self-hosted Scalar reference; `/openapi.json` is generated from DRF serializers. Authentication uses `Authorization: Bearer …` and `X-Jay-Identity-ID`. Every mutation except registration requires a UUID `Idempotency-Key`. Group-scoped changes carry the current `membership_id`, including DELETE bodies. Group creation supplies both its UUID and the creator membership UUID. Configuration writes include a timezone-aware `saved_at`; greater `(saved_at, operation UUID)` wins. Deletes remain terminal.

`GET /v1/sync` returns the identity scope. Membership entries identify group scopes at `GET /v1/groups/{id}/sync`. Treat cursors as opaque strings and stage incomplete snapshots. Alarm delivery acknowledgement follows successful local scheduling and is independent of sound readiness. An audio upload is defined once, uploaded to staging using a renewable signed URL, completed with HTTP 202 and verified by the worker before downloads become available.

The [architecture guide](../docs/architecture.md) describes ordering, synchronization and worker boundaries. The checked-in [API schema](../docs/openapi.json) is validated against the generated schema by the test runner.

## Deployment

Run the web process and background worker from the same application version, with the same database, signing secret and provider settings. Use HTTPS, set `DEBUG=false`, configure `ALLOWED_HOSTS`, and enable `TRUST_PROXY` only behind a trusted proxy. PostgreSQL must provide a direct or session-pooled connection for the live listener's `LISTEN` subscription.

From `server/`, install the runtime dependencies and apply migrations once before starting the application processes:

```sh
mise exec -- .venv/bin/python -m pip install --require-hashes -r requirements.lock
mise exec -- .venv/bin/python -m pip install --no-deps .
mise exec -- .venv/bin/python manage.py migrate --noinput
```

Run these as separate supervised processes, adjusting the bind address and port for your environment:

```sh
mise exec -- .venv/bin/uvicorn jay_server.asgi:application --host 0.0.0.0 --port 8000
mise exec -- .venv/bin/python manage.py run_worker
```

Use `/health/live` for web liveness, `/health/ready` for database and schema readiness, and `manage.py check_worker` for worker progress and failures. Stop processes gracefully, keep database backups, and test restoration. Rolling deployments require schema compatibility between overlapping versions.

## Performance

Size web and worker processes from measured traffic and resource use. Account for all process pools, live-listener connections and deployment overlap when setting the database connection budget. Long-lived streams and group fan-out affect capacity beyond ordinary request rates.

Load-test representative group sizes, concurrent connections, shared edits, deadline bursts and offline reconnections in an isolated environment. Measure response and synchronization latency, errors, worker backlog, CPU, memory and database connections, including recovery after failures. Check audio transfer and playback separately on real devices: metadata scheduling must proceed while audio is pending, with the default ringtone available when needed. Passing local tests alone does not establish production capacity.
