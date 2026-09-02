from alembic import op

revision = "0010_shared_timer_alerts"
down_revision = "0009_shared_timers"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE shared_timers
        ADD COLUMN sound_enabled boolean NOT NULL DEFAULT true,
        ADD COLUMN vibrate boolean NOT NULL DEFAULT true,
        ADD COLUMN vibration_pattern integer[] NOT NULL,
        ADD COLUMN vibration_pattern_name text NOT NULL
        """
    )


def downgrade() -> None:
    op.execute(
        """
        ALTER TABLE shared_timers
        DROP COLUMN sound_enabled,
        DROP COLUMN vibrate,
        DROP COLUMN vibration_pattern,
        DROP COLUMN vibration_pattern_name
        """
    )
