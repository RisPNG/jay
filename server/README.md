# Jay server

The Jay server synchronizes device identities, groups, alarm revisions, delivery acknowledgements, and snooze or dismissal activity. Android clients communicate with this API; they never connect directly to PostgreSQL.

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
| `INVITE_LIFETIME_HOURS` | Default lifetime of a one-use invitation |
| `FIREBASE_CREDENTIALS_JSON` | Optional Firebase service-account JSON for immediate synchronization pushes |

If Firebase is not configured, synchronization still occurs when Jay launches, when the user requests it, after local group operations, and periodically in the background.

## Render

The repository-root `render.yaml` provisions the Python service and PostgreSQL database. Set `PUBLIC_URL` to the deployed HTTPS URL. To enable immediate delivery, store the Firebase service-account JSON in the secret `FIREBASE_CREDENTIALS_JSON` environment variable.

## Tests

Apply migrations to a test database and run:

```sh
mise exec -- .venv/bin/pytest
```
