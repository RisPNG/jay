from alembic import op


revision = "0013_device_removal"
down_revision = "0012_drop_sound_enabled"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE devices
        ADD COLUMN last_seen_at timestamptz NOT NULL DEFAULT now();

        CREATE TABLE retired_devices (
            id text PRIMARY KEY,
            retired_at timestamptz NOT NULL DEFAULT now()
        );

        ALTER TABLE groups ALTER COLUMN created_by DROP NOT NULL;
        ALTER TABLE groups DROP CONSTRAINT groups_created_by_fkey;
        ALTER TABLE groups ADD CONSTRAINT groups_created_by_fkey
            FOREIGN KEY (created_by) REFERENCES devices(id) ON DELETE SET NULL;

        ALTER TABLE group_invites ALTER COLUMN created_by DROP NOT NULL;
        ALTER TABLE group_invites DROP CONSTRAINT group_invites_created_by_fkey;
        ALTER TABLE group_invites ADD CONSTRAINT group_invites_created_by_fkey
            FOREIGN KEY (created_by) REFERENCES devices(id) ON DELETE SET NULL;
        ALTER TABLE group_invites DROP CONSTRAINT group_invites_consumed_by_fkey;
        ALTER TABLE group_invites ADD CONSTRAINT group_invites_consumed_by_fkey
            FOREIGN KEY (consumed_by) REFERENCES devices(id) ON DELETE SET NULL;

        ALTER TABLE shared_alarms ALTER COLUMN created_by DROP NOT NULL;
        ALTER TABLE shared_alarms DROP CONSTRAINT shared_alarms_created_by_fkey;
        ALTER TABLE shared_alarms ADD CONSTRAINT shared_alarms_created_by_fkey
            FOREIGN KEY (created_by) REFERENCES devices(id) ON DELETE SET NULL;
        ALTER TABLE shared_alarms ALTER COLUMN updated_by DROP NOT NULL;
        ALTER TABLE shared_alarms DROP CONSTRAINT shared_alarms_updated_by_fkey;
        ALTER TABLE shared_alarms ADD CONSTRAINT shared_alarms_updated_by_fkey
            FOREIGN KEY (updated_by) REFERENCES devices(id) ON DELETE SET NULL;

        ALTER TABLE shared_timers ALTER COLUMN started_by DROP NOT NULL;
        ALTER TABLE shared_timers DROP CONSTRAINT shared_timers_started_by_fkey;
        ALTER TABLE shared_timers ADD CONSTRAINT shared_timers_started_by_fkey
            FOREIGN KEY (started_by) REFERENCES devices(id) ON DELETE SET NULL;

        ALTER TABLE shared_sounds ALTER COLUMN uploaded_by DROP NOT NULL;
        ALTER TABLE shared_sounds DROP CONSTRAINT shared_sounds_uploaded_by_fkey;
        ALTER TABLE shared_sounds ADD CONSTRAINT shared_sounds_uploaded_by_fkey
            FOREIGN KEY (uploaded_by) REFERENCES devices(id) ON DELETE SET NULL;

        ALTER TABLE changes DROP CONSTRAINT changes_subject_device_id_fkey;
        ALTER TABLE changes ADD CONSTRAINT changes_subject_device_id_fkey
            FOREIGN KEY (subject_device_id) REFERENCES devices(id) ON DELETE SET NULL;
        ALTER TABLE changes DROP CONSTRAINT changes_recipient_device_id_fkey;
        ALTER TABLE changes ADD CONSTRAINT changes_recipient_device_id_fkey
            FOREIGN KEY (recipient_device_id) REFERENCES devices(id) ON DELETE SET NULL;
        """
    )


def downgrade() -> None:
    op.execute(
        """
        ALTER TABLE changes DROP CONSTRAINT changes_recipient_device_id_fkey;
        ALTER TABLE changes ADD CONSTRAINT changes_recipient_device_id_fkey
            FOREIGN KEY (recipient_device_id) REFERENCES devices(id);
        ALTER TABLE changes DROP CONSTRAINT changes_subject_device_id_fkey;
        ALTER TABLE changes ADD CONSTRAINT changes_subject_device_id_fkey
            FOREIGN KEY (subject_device_id) REFERENCES devices(id);

        ALTER TABLE shared_sounds DROP CONSTRAINT shared_sounds_uploaded_by_fkey;
        ALTER TABLE shared_sounds ADD CONSTRAINT shared_sounds_uploaded_by_fkey
            FOREIGN KEY (uploaded_by) REFERENCES devices(id);
        ALTER TABLE shared_sounds ALTER COLUMN uploaded_by SET NOT NULL;

        ALTER TABLE shared_timers DROP CONSTRAINT shared_timers_started_by_fkey;
        ALTER TABLE shared_timers ADD CONSTRAINT shared_timers_started_by_fkey
            FOREIGN KEY (started_by) REFERENCES devices(id);
        ALTER TABLE shared_timers ALTER COLUMN started_by SET NOT NULL;

        ALTER TABLE shared_alarms DROP CONSTRAINT shared_alarms_updated_by_fkey;
        ALTER TABLE shared_alarms ADD CONSTRAINT shared_alarms_updated_by_fkey
            FOREIGN KEY (updated_by) REFERENCES devices(id);
        ALTER TABLE shared_alarms ALTER COLUMN updated_by SET NOT NULL;
        ALTER TABLE shared_alarms DROP CONSTRAINT shared_alarms_created_by_fkey;
        ALTER TABLE shared_alarms ADD CONSTRAINT shared_alarms_created_by_fkey
            FOREIGN KEY (created_by) REFERENCES devices(id);
        ALTER TABLE shared_alarms ALTER COLUMN created_by SET NOT NULL;

        ALTER TABLE group_invites DROP CONSTRAINT group_invites_consumed_by_fkey;
        ALTER TABLE group_invites ADD CONSTRAINT group_invites_consumed_by_fkey
            FOREIGN KEY (consumed_by) REFERENCES devices(id);
        ALTER TABLE group_invites DROP CONSTRAINT group_invites_created_by_fkey;
        ALTER TABLE group_invites ADD CONSTRAINT group_invites_created_by_fkey
            FOREIGN KEY (created_by) REFERENCES devices(id);
        ALTER TABLE group_invites ALTER COLUMN created_by SET NOT NULL;

        ALTER TABLE groups DROP CONSTRAINT groups_created_by_fkey;
        ALTER TABLE groups ADD CONSTRAINT groups_created_by_fkey
            FOREIGN KEY (created_by) REFERENCES devices(id);
        ALTER TABLE groups ALTER COLUMN created_by SET NOT NULL;

        DROP TABLE retired_devices;

        ALTER TABLE devices DROP COLUMN last_seen_at;
        """
    )
