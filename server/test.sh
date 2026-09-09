#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
test_dir=$(mktemp -d /tmp/jay-server-tests.XXXXXX)
trap 'if [[ -f "$test_dir/data/postmaster.pid" ]]; then mise exec postgres@18.6 -- pg_ctl -D "$test_dir/data" -m immediate -w stop; fi; rm -rf "$test_dir"' EXIT

mkdir "$test_dir/socket"
mise exec postgres@18.6 -- initdb -D "$test_dir/data" -U jay -A trust --no-locale --encoding=UTF8 > "$test_dir/initdb.log"
mise exec postgres@18.6 -- pg_ctl -D "$test_dir/data" -l "$test_dir/postgres.log" -o "-k $test_dir/socket -c listen_addresses='' -c max_connections=65 -c shared_buffers=32MB" -w start
mise exec postgres@18.6 -- createdb -h "$test_dir/socket" -U jay jay_test

export DATABASE_URL="postgresql://jay@/jay_test?host=$test_dir/socket"
export LOAD_ENV_FILE=false
export SECRET_KEY=isolated-test-secret-not-for-deployment
export ALLOWED_HOSTS=localhost,127.0.0.1,testserver
export PUBLIC_URL=http://127.0.0.1:8000
export ANDROID_APP_LINKS='{}'
export INVITE_LIFETIME_HOURS=24
export PLAY_ENTITLEMENT_LIFETIME_HOURS=48
export SHARED_SOUND_ACCESS=play
export IDENTITY_INACTIVITY_TIMEOUT_DAYS=120
export FIREBASE_CREDENTIALS_JSON=
export GOOGLE_PLAY_CREDENTIALS_JSON=
export B2_S3_ENDPOINT=
export B2_BUCKET_NAME=
export B2_APPLICATION_KEY_ID=
export B2_APPLICATION_KEY=

mise exec -- .venv/bin/python manage.py migrate --noinput
mise exec -- .venv/bin/python manage.py check
mise exec -- .venv/bin/python manage.py makemigrations --check --dry-run
mise exec -- .venv/bin/python manage.py spectacular --format openapi-json --file "$test_dir/openapi.json" --validate --fail-on-warn
cmp "$test_dir/openapi.json" ../docs/openapi.json
mise exec -- .venv/bin/pytest "$@"
