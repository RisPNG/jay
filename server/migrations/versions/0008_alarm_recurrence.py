from alembic import op


revision = "0008_alarm_recurrence"
down_revision = "0007_social_activity"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE shared_alarms
            ADD COLUMN start_date bigint NOT NULL
                DEFAULT (CURRENT_DATE - DATE '1970-01-01'),
            ADD COLUMN repeat_interval integer NOT NULL DEFAULT 1
                CHECK (repeat_interval > 0),
            ADD COLUMN repeat_unit text NOT NULL DEFAULT 'WEEK'
                CHECK (repeat_unit IN ('DAY', 'WEEK', 'MONTH', 'YEAR')),
            ADD COLUMN repeat_anchor text NOT NULL DEFAULT 'DAY_OF_MONTH'
                CHECK (repeat_anchor IN ('DAY_OF_MONTH', 'DAY_OF_WEEK')),
            ADD COLUMN repeat_duration integer
                CHECK (repeat_duration > 0),
            ADD COLUMN repeat_duration_unit text NOT NULL DEFAULT 'DAY'
                CHECK (repeat_duration_unit IN ('DAY', 'WEEK', 'MONTH', 'YEAR')),
            ADD COLUMN end_date bigint,
            ADD COLUMN end_occurrences integer
                CHECK (end_occurrences > 0),
            ADD COLUMN advanced boolean NOT NULL DEFAULT false
        """
    )
    op.execute(
        """
        UPDATE shared_alarms
        SET end_occurrences = 1,
            days = ARRAY[0, 1, 2, 3, 4, 5, 6]::smallint[]
        WHERE repeat = false
        """
    )
    op.execute("ALTER TABLE shared_alarms DROP COLUMN repeat")


def downgrade() -> None:
    op.execute(
        "ALTER TABLE shared_alarms ADD COLUMN repeat boolean NOT NULL DEFAULT true"
    )
    op.execute(
        "UPDATE shared_alarms SET repeat = false WHERE end_occurrences = 1"
    )
    op.execute(
        """
        ALTER TABLE shared_alarms
            DROP COLUMN start_date,
            DROP COLUMN repeat_interval,
            DROP COLUMN repeat_unit,
            DROP COLUMN repeat_anchor,
            DROP COLUMN repeat_duration,
            DROP COLUMN repeat_duration_unit,
            DROP COLUMN end_date,
            DROP COLUMN end_occurrences,
            DROP COLUMN advanced
        """
    )
