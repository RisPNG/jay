from alembic import op


revision = "0012_drop_sound_enabled"
down_revision = "0011_shared_sounds"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE shared_alarms DROP COLUMN sound_enabled;
        ALTER TABLE shared_timers DROP COLUMN sound_enabled;
        """
    )


def downgrade() -> None:
    op.execute(
        """
        ALTER TABLE shared_alarms
        ADD COLUMN sound_enabled boolean NOT NULL DEFAULT false;
        UPDATE shared_alarms SET sound_enabled = (sound_mode != 'off');
        ALTER TABLE shared_alarms ALTER COLUMN sound_enabled DROP DEFAULT;

        ALTER TABLE shared_timers
        ADD COLUMN sound_enabled boolean NOT NULL DEFAULT true;
        UPDATE shared_timers SET sound_enabled = (sound_mode != 'off');
        """
    )
