from alembic import op

revision = "0001_social_alarms"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TYPE group_alarm_permission AS ENUM ('everyone', 'leaders');
        CREATE TYPE group_member_role AS ENUM ('member', 'leader');
        CREATE TYPE alarm_activity_kind AS ENUM ('snoozed', 'dismissed');

        CREATE TABLE devices (
            id text PRIMARY KEY,
            name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 64),
            token_hash bytea NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now()
        );

        CREATE TABLE groups (
            id uuid PRIMARY KEY,
            name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
            alarm_permission group_alarm_permission NOT NULL,
            notify_snoozed boolean NOT NULL DEFAULT false,
            notify_dismissed boolean NOT NULL DEFAULT true,
            created_by text NOT NULL REFERENCES devices(id),
            created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now()
        );

        CREATE TABLE group_members (
            group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            device_id text NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            role group_member_role NOT NULL,
            joined_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (group_id, device_id)
        );

        CREATE TABLE group_invites (
            id uuid PRIMARY KEY,
            group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            token_hash bytea NOT NULL UNIQUE,
            created_by text NOT NULL REFERENCES devices(id),
            expires_at timestamptz NOT NULL,
            consumed_at timestamptz,
            consumed_by text REFERENCES devices(id),
            created_at timestamptz NOT NULL DEFAULT now()
        );

        CREATE TABLE shared_alarms (
            id uuid PRIMARY KEY,
            group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            revision integer NOT NULL DEFAULT 1 CHECK (revision > 0),
            time bigint NOT NULL CHECK (time >= 0),
            label text,
            enabled boolean NOT NULL,
            days smallint[] NOT NULL,
            vibrate boolean NOT NULL,
            repeat boolean NOT NULL,
            snooze_enabled boolean NOT NULL,
            snooze_minutes integer NOT NULL CHECK (snooze_minutes > 0),
            sound_enabled boolean NOT NULL,
            vibration_pattern integer[] NOT NULL,
            vibration_pattern_name text NOT NULL,
            deleted boolean NOT NULL DEFAULT false,
            created_by text NOT NULL REFERENCES devices(id),
            updated_by text NOT NULL REFERENCES devices(id),
            created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now(),
            CHECK (days <@ ARRAY[0, 1, 2, 3, 4, 5, 6]::smallint[])
        );

        CREATE TABLE alarm_deliveries (
            alarm_id uuid NOT NULL REFERENCES shared_alarms(id) ON DELETE CASCADE,
            device_id text NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            revision integer NOT NULL,
            delivered_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (alarm_id, device_id)
        );

        CREATE TABLE alarm_activity (
            id uuid PRIMARY KEY,
            alarm_id uuid NOT NULL REFERENCES shared_alarms(id) ON DELETE CASCADE,
            group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            alarm_revision integer NOT NULL,
            device_id text NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            kind alarm_activity_kind NOT NULL,
            occurred_at timestamptz NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now()
        );

        CREATE TABLE changes (
            sequence bigserial PRIMARY KEY,
            group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            entity_type text NOT NULL,
            entity_id text NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now()
        );

        CREATE INDEX group_members_device_id_idx ON group_members(device_id);
        CREATE INDEX shared_alarms_group_id_idx ON shared_alarms(group_id);
        CREATE INDEX alarm_activity_group_id_created_at_idx ON alarm_activity(group_id, created_at DESC);
        CREATE INDEX changes_group_id_sequence_idx ON changes(group_id, sequence);
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DROP TABLE changes;
        DROP TABLE alarm_activity;
        DROP TABLE alarm_deliveries;
        DROP TABLE shared_alarms;
        DROP TABLE group_invites;
        DROP TABLE group_members;
        DROP TABLE groups;
        DROP TABLE devices;
        DROP TYPE alarm_activity_kind;
        DROP TYPE group_member_role;
        DROP TYPE group_alarm_permission;
        """
    )

