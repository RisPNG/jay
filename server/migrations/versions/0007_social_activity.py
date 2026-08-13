from alembic import op

revision = "0007_social_activity"
down_revision = "0006_alarm_change_time"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE groups
        ADD COLUMN notify_alarm_changes boolean NOT NULL DEFAULT true,
        ADD COLUMN notify_ignored boolean NOT NULL DEFAULT true;

        ALTER TABLE group_members
        ADD COLUMN notify_membership boolean NOT NULL DEFAULT true,
        ADD COLUMN notify_administrative boolean NOT NULL DEFAULT true;

        ALTER TABLE devices
        ADD COLUMN time_zone text;

        ALTER TABLE changes
        DROP CONSTRAINT changes_action_check,
        ADD COLUMN subject_device_id text REFERENCES devices(id),
        ADD COLUMN recipient_device_id text REFERENCES devices(id),
        ADD COLUMN group_label text,
        ADD COLUMN actor_label text,
        ADD COLUMN subject_label text,
        ADD COLUMN details jsonb;

        UPDATE changes c
        SET entity_type = 'outcome',
            entity_id = a.alarm_id::text,
            actor_device_id = a.device_id,
            subject_device_id = a.device_id,
            action = a.kind::text,
            entity_label = sa.label,
            entity_time = sa.time,
            details = jsonb_build_object(
                'activity_id', a.id,
                'alarm_revision', a.alarm_revision
            )
        FROM alarm_activity a
        JOIN shared_alarms sa ON sa.id = a.alarm_id
        WHERE c.entity_type = 'activity' AND c.entity_id = a.id::text;

        WITH membership_events AS (
            SELECT sequence,
                row_number() OVER (
                    PARTITION BY group_id, entity_id ORDER BY sequence
                ) AS event_number
            FROM changes
            WHERE entity_type = 'membership' AND action IS NULL
        )
        UPDATE changes c
        SET action = CASE
                WHEN membership_events.event_number % 2 = 1 THEN 'joined'
                ELSE 'left'
            END,
            subject_device_id = c.entity_id,
            entity_label = d.name
        FROM devices d, membership_events
        WHERE c.entity_type = 'membership'
          AND c.action IS NULL
          AND d.id = c.entity_id
          AND membership_events.sequence = c.sequence;

        UPDATE changes c
        SET entity_type = 'administrative',
            action = 'recorded',
            subject_device_id = c.entity_id,
            entity_label = d.name
        FROM devices d
        WHERE c.entity_type = 'member'
          AND c.action IS NULL
          AND d.id = c.entity_id;

        UPDATE changes c
        SET action = CASE
            WHEN c.sequence = (
                SELECT min(c2.sequence) FROM changes c2 WHERE c2.group_id = c.group_id
            ) THEN 'created'
            ELSE 'updated'
        END,
        entity_label = g.name
        FROM groups g
        WHERE c.entity_type = 'group'
          AND c.action IS NULL
          AND g.id = c.group_id;

        UPDATE changes SET action = 'recorded' WHERE action IS NULL;

        UPDATE changes c
        SET group_label = (SELECT name FROM groups WHERE id = c.group_id),
            actor_label = (SELECT name FROM devices WHERE id = c.actor_device_id),
            subject_label = (SELECT name FROM devices WHERE id = c.subject_device_id);

        INSERT INTO changes (
            group_id, entity_type, entity_id, actor_device_id, action,
            group_label, actor_label, details, created_at
        )
        SELECT invite.group_id, 'administrative', invite.id::text,
            invite.created_by, 'invitation_created', groups.name, devices.name,
            jsonb_build_object('expires_at', invite.expires_at), invite.created_at
        FROM group_invites invite
        JOIN groups ON groups.id = invite.group_id
        JOIN devices ON devices.id = invite.created_by;

        INSERT INTO changes (
            group_id, entity_type, entity_id, actor_device_id, action, entity_label,
            entity_time, subject_device_id, group_label, subject_label, details
        )
        SELECT a.group_id, 'delivery', ad.alarm_id::text, ad.device_id, 'delivered',
            a.label, a.time, ad.device_id, g.name, d.name,
            jsonb_build_object('alarm_revision', ad.revision)
        FROM alarm_deliveries ad
        JOIN shared_alarms a ON a.id = ad.alarm_id
        JOIN groups g ON g.id = a.group_id
        JOIN devices d ON d.id = ad.device_id;

        ALTER TYPE alarm_activity_kind ADD VALUE IF NOT EXISTS 'ignored';

        ALTER TABLE alarm_activity
        ADD COLUMN occurrence_id text,
        ADD COLUMN reason text;

        CREATE TABLE alarm_occurrences (
            alarm_id uuid NOT NULL REFERENCES shared_alarms(id) ON DELETE CASCADE,
            group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
            alarm_revision integer NOT NULL,
            device_id text NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            occurrence_id text NOT NULL,
            trigger_at timestamptz NOT NULL,
            deadline_at timestamptz NOT NULL,
            status text NOT NULL DEFAULT 'pending' CHECK (
                status IN ('pending', 'dismissed', 'ignored', 'canceled')
            ),
            resolved_at timestamptz,
            created_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (alarm_id, device_id, occurrence_id)
        );

        ALTER TABLE alarm_deliveries DROP CONSTRAINT alarm_deliveries_pkey;
        ALTER TABLE alarm_deliveries
        ADD PRIMARY KEY (alarm_id, device_id, revision);

        CREATE INDEX changes_group_id_created_at_idx
        ON changes(group_id, created_at DESC, sequence DESC);
        CREATE INDEX alarm_activity_alarm_id_occurred_at_idx
        ON alarm_activity(alarm_id, occurred_at DESC);
        CREATE INDEX alarm_occurrences_pending_deadline_idx
        ON alarm_occurrences(deadline_at)
        WHERE status = 'pending';
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DELETE FROM alarm_deliveries current_delivery
        USING alarm_deliveries newer_delivery
        WHERE current_delivery.alarm_id = newer_delivery.alarm_id
          AND current_delivery.device_id = newer_delivery.device_id
          AND current_delivery.revision < newer_delivery.revision;

        ALTER TABLE alarm_deliveries DROP CONSTRAINT alarm_deliveries_pkey;
        ALTER TABLE alarm_deliveries
        ADD PRIMARY KEY (alarm_id, device_id);

        DROP INDEX alarm_activity_alarm_id_occurred_at_idx;
        DROP INDEX changes_group_id_created_at_idx;

        DELETE FROM alarm_activity WHERE kind = 'ignored';
        DROP TABLE alarm_occurrences;
        ALTER TABLE alarm_activity
        DROP COLUMN reason,
        DROP COLUMN occurrence_id;

        DELETE FROM changes WHERE action NOT IN (
            'created', 'edited', 'enabled', 'disabled', 'deleted'
        );
        ALTER TABLE changes
        DROP COLUMN details,
        DROP COLUMN subject_label,
        DROP COLUMN actor_label,
        DROP COLUMN group_label,
        DROP COLUMN recipient_device_id,
        DROP COLUMN subject_device_id,
        ADD CONSTRAINT changes_action_check CHECK (
            action IS NULL OR action IN (
                'created', 'edited', 'enabled', 'disabled', 'deleted'
            )
        );

        ALTER TABLE group_members
        DROP COLUMN notify_administrative,
        DROP COLUMN notify_membership;

        ALTER TABLE devices DROP COLUMN time_zone;

        ALTER TABLE groups
        DROP COLUMN notify_ignored,
        DROP COLUMN notify_alarm_changes;
        """
    )
