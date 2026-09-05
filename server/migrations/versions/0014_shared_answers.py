from alembic import op


revision = "0014_shared_answers"
down_revision = "0013_device_removal"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE groups ADD COLUMN shared_answers boolean NOT NULL DEFAULT false;

        ALTER TABLE alarm_occurrences DROP CONSTRAINT alarm_occurrences_status_check;
        ALTER TABLE alarm_occurrences ADD CONSTRAINT alarm_occurrences_status_check
        CHECK (status = ANY (ARRAY[
            'pending'::text, 'snoozed'::text, 'dismissed'::text,
            'ignored'::text, 'canceled'::text
        ]));

        CREATE TABLE device_push_tokens (
            token text PRIMARY KEY,
            device_id text NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            updated_at timestamptz NOT NULL DEFAULT now()
        );

        INSERT INTO device_push_tokens (token, device_id, updated_at)
        SELECT push_token, id, updated_at FROM devices WHERE push_token IS NOT NULL;

        ALTER TABLE devices DROP COLUMN push_token;
        """
    )


def downgrade() -> None:
    op.execute(
        """
        ALTER TABLE devices ADD COLUMN push_token text;
        CREATE UNIQUE INDEX devices_push_token_idx ON devices(push_token)
        WHERE push_token IS NOT NULL;
        UPDATE devices SET push_token = latest.token FROM (
            SELECT DISTINCT ON (device_id) device_id, token
            FROM device_push_tokens
            ORDER BY device_id, updated_at DESC
        ) latest WHERE devices.id = latest.device_id;
        DROP TABLE device_push_tokens;

        UPDATE alarm_occurrences SET status = 'dismissed' WHERE status = 'snoozed';
        ALTER TABLE alarm_occurrences DROP CONSTRAINT alarm_occurrences_status_check;
        ALTER TABLE alarm_occurrences ADD CONSTRAINT alarm_occurrences_status_check
        CHECK (status = ANY (ARRAY[
            'pending'::text, 'dismissed'::text, 'ignored'::text, 'canceled'::text
        ]));

        ALTER TABLE groups DROP COLUMN shared_answers;
        """
    )
