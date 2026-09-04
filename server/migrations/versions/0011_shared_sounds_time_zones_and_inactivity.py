from alembic import op


revision = "0011_shared_sounds"
down_revision = "0010_shared_timer_alerts"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE groups
        ADD COLUMN alarm_time_basis text NOT NULL DEFAULT 'member_local'
            CHECK (alarm_time_basis IN ('member_local', 'group_time_zone')),
        ADD COLUMN alarm_time_zone text NOT NULL DEFAULT 'UTC';

        CREATE TABLE shared_sounds (
            id uuid PRIMARY KEY,
            group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            object_key text NOT NULL UNIQUE,
            uploaded_by text NOT NULL REFERENCES devices(id),
            title text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
            sha256 text NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
            byte_length bigint NOT NULL CHECK (byte_length > 0),
            duration_ms integer NOT NULL CHECK (duration_ms BETWEEN 1 AND 300000),
            status text NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending', 'ready')),
            created_at timestamptz NOT NULL DEFAULT now(),
            ready_at timestamptz
        );

        CREATE TABLE sound_deletions (
            object_key text PRIMARY KEY,
            created_at timestamptz NOT NULL DEFAULT now(),
            attempts integer NOT NULL DEFAULT 0,
            last_attempt_at timestamptz
        );

        CREATE FUNCTION enqueue_shared_sound_deletion() RETURNS trigger AS $$
        BEGIN
            INSERT INTO sound_deletions (object_key)
            VALUES (OLD.object_key)
            ON CONFLICT (object_key) DO NOTHING;
            RETURN OLD;
        END;
        $$ LANGUAGE plpgsql;

        CREATE TRIGGER shared_sound_deletion
        BEFORE DELETE ON shared_sounds
        FOR EACH ROW EXECUTE FUNCTION enqueue_shared_sound_deletion();

        ALTER TABLE shared_alarms
        ADD COLUMN sound_mode text NOT NULL DEFAULT 'member_default'
            CHECK (sound_mode IN ('off', 'member_default', 'shared')),
        ADD COLUMN sound_id uuid REFERENCES shared_sounds(id) ON DELETE SET NULL,
        ADD COLUMN inactive_cycle_streak integer NOT NULL DEFAULT 0
            CHECK (inactive_cycle_streak BETWEEN 0 AND 3),
        ADD COLUMN last_evaluated_cycle_date date;

        UPDATE shared_alarms
        SET sound_mode = CASE WHEN sound_enabled THEN 'member_default' ELSE 'off' END;

        ALTER TABLE shared_timers
        ADD COLUMN sound_mode text NOT NULL DEFAULT 'member_default'
            CHECK (sound_mode IN ('off', 'member_default', 'shared')),
        ADD COLUMN sound_id uuid REFERENCES shared_sounds(id) ON DELETE SET NULL;

        UPDATE shared_timers
        SET sound_mode = CASE WHEN sound_enabled THEN 'member_default' ELSE 'off' END;

        ALTER TABLE alarm_occurrences ADD COLUMN cycle_date date;
        UPDATE alarm_occurrences
        SET cycle_date = trigger_at::date;
        ALTER TABLE alarm_occurrences ALTER COLUMN cycle_date SET NOT NULL;

        CREATE INDEX shared_sounds_group_id_idx ON shared_sounds(group_id);
        CREATE INDEX shared_alarms_sound_id_idx ON shared_alarms(sound_id);
        CREATE INDEX shared_timers_sound_id_idx ON shared_timers(sound_id);
        CREATE INDEX alarm_occurrences_cycle_idx
        ON alarm_occurrences(alarm_id, alarm_revision, cycle_date);
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DROP INDEX alarm_occurrences_cycle_idx;
        ALTER TABLE alarm_occurrences DROP COLUMN cycle_date;

        DROP INDEX shared_timers_sound_id_idx;
        ALTER TABLE shared_timers DROP COLUMN sound_id, DROP COLUMN sound_mode;

        DROP INDEX shared_alarms_sound_id_idx;
        ALTER TABLE shared_alarms
        DROP COLUMN last_evaluated_cycle_date,
        DROP COLUMN inactive_cycle_streak,
        DROP COLUMN sound_id,
        DROP COLUMN sound_mode;

        DROP INDEX shared_sounds_group_id_idx;
        DROP TRIGGER shared_sound_deletion ON shared_sounds;
        DROP FUNCTION enqueue_shared_sound_deletion;
        DROP TABLE shared_sounds;
        DROP TABLE sound_deletions;

        ALTER TABLE groups DROP COLUMN alarm_time_zone, DROP COLUMN alarm_time_basis;
        """
    )
