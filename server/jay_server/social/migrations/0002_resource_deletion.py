from django.db import migrations, models
from django.utils import timezone


class Migration(migrations.Migration):
    dependencies = [("social", "0001_initial")]

    operations = [
        migrations.CreateModel(
            name="DeletedResource",
            fields=[
                ("id", models.UUIDField(primary_key=True, serialize=False)),
                ("kind", models.CharField(max_length=32)),
                ("deleted_at", models.DateTimeField(default=timezone.now)),
            ],
        ),
        migrations.RunSQL(
            """
            CREATE FUNCTION social_enqueue_sound_deletion() RETURNS trigger AS $$
            BEGIN
                INSERT INTO social_deletedresource (id, kind, deleted_at)
                VALUES (OLD.id, 'sound', now()) ON CONFLICT DO NOTHING;
                INSERT INTO social_deliverywork
                    (id, kind, deduplication_key, payload, available_at, created_at, attempts)
                VALUES
                    (gen_random_uuid(), 'delete_sound', 'delete_sound:' || OLD.object_key,
                     jsonb_build_object('object_key', OLD.object_key), now(), now(), 0),
                    (gen_random_uuid(), 'delete_sound', 'delete_sound:' || OLD.staging_key,
                     jsonb_build_object('object_key', OLD.staging_key), now() + interval '15 minutes', now(), 0)
                ON CONFLICT (deduplication_key) DO NOTHING;
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql;
            CREATE TRIGGER social_sound_deletion
                BEFORE DELETE ON social_sharedsound
                FOR EACH ROW EXECUTE FUNCTION social_enqueue_sound_deletion();
            CREATE FUNCTION social_reject_deleted_resource() RETURNS trigger AS $$
            BEGIN
                IF EXISTS (SELECT 1 FROM social_deletedresource WHERE id = NEW.id) THEN
                    RAISE EXCEPTION 'Deleted resource ID cannot be reused' USING ERRCODE = '23505';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            CREATE TRIGGER social_sound_creation BEFORE INSERT ON social_sharedsound
                FOR EACH ROW EXECUTE FUNCTION social_reject_deleted_resource();
            CREATE FUNCTION social_record_resource_deletion() RETURNS trigger AS $$
            BEGIN
                INSERT INTO social_deletedresource (id, kind, deleted_at)
                VALUES (OLD.id, TG_ARGV[0], now()) ON CONFLICT DO NOTHING;
                RETURN OLD;
            END;
            $$ LANGUAGE plpgsql;
            CREATE TRIGGER social_alarm_deletion BEFORE DELETE ON social_sharedalarm
                FOR EACH ROW EXECUTE FUNCTION social_record_resource_deletion('alarm');
            CREATE TRIGGER social_timer_deletion BEFORE DELETE ON social_sharedtimer
                FOR EACH ROW EXECUTE FUNCTION social_record_resource_deletion('timer');
            CREATE TRIGGER social_group_deletion BEFORE DELETE ON social_group
                FOR EACH ROW EXECUTE FUNCTION social_record_resource_deletion('group');
            CREATE TRIGGER social_alarm_creation BEFORE INSERT ON social_sharedalarm
                FOR EACH ROW EXECUTE FUNCTION social_reject_deleted_resource();
            CREATE TRIGGER social_timer_creation BEFORE INSERT ON social_sharedtimer
                FOR EACH ROW EXECUTE FUNCTION social_reject_deleted_resource();
            CREATE TRIGGER social_group_creation BEFORE INSERT ON social_group
                FOR EACH ROW EXECUTE FUNCTION social_reject_deleted_resource();
            """,
            "DROP TRIGGER social_alarm_creation ON social_sharedalarm; DROP TRIGGER social_timer_creation ON social_sharedtimer; DROP TRIGGER social_group_creation ON social_group; DROP TRIGGER social_alarm_deletion ON social_sharedalarm; DROP TRIGGER social_timer_deletion ON social_sharedtimer; DROP TRIGGER social_group_deletion ON social_group; DROP FUNCTION social_record_resource_deletion(); DROP TRIGGER social_sound_creation ON social_sharedsound; DROP FUNCTION social_reject_deleted_resource(); DROP TRIGGER social_sound_deletion ON social_sharedsound; DROP FUNCTION social_enqueue_sound_deletion();",
        ),
    ]
