from django.db import migrations, models, transaction


def initialize_alarm_timing(apps, schema_editor):
    from jay_server.social.synchronization import publish_changes

    alarms = apps.get_model("social", "SharedAlarm")
    occurrences = apps.get_model("social", "AlarmOccurrence")
    scopes = apps.get_model("social", "SyncScope")
    versions = apps.get_model("social", "SyncVersion")
    alarms.objects.update(timeout_minutes=10)
    for alarm in alarms.objects.filter(deleted_at=None, group__deleted_at=None).select_related("group").iterator():
        with transaction.atomic():
            scopes.objects.select_for_update().get(pk=alarm.group.scope_id)
            changes = []
            previous = versions.objects.filter(scope_id=alarm.group.scope_id, kind="alarm", key=str(alarm.pk)).order_by("-revision", "-ordinal").first()
            if previous is not None:
                data = {**previous.representation, "timeout_minutes": alarm.timeout_minutes, "missed_restart_enabled": alarm.missed_restart_enabled, "missed_restart_minutes": alarm.missed_restart_minutes}
                changes.append(("alarm", str(alarm.pk), "upsert", data, None))
            for occurrence in occurrences.objects.filter(alarm_id=alarm.pk, state="pending").iterator():
                previous = versions.objects.filter(scope_id=alarm.group.scope_id, kind="occurrence", key=str(occurrence.pk)).order_by("-revision", "-ordinal").first()
                if previous is not None:
                    data = {**previous.representation, "deadline_at": occurrence.deadline_at.isoformat(), "snoozed_at": None}
                    changes.append(("occurrence", str(occurrence.pk), "upsert", data, occurrence.identity_id))
                if len(changes) >= 100:
                    publish_changes(alarm.group.scope_id, changes, group=alarm.group)
                    changes = []
            if changes:
                publish_changes(alarm.group.scope_id, changes, group=alarm.group)


class Migration(migrations.Migration):
    dependencies = [("social", "0008_occurrence_snoozed_until")]

    operations = [
        migrations.AddField(
            model_name="sharedalarm",
            name="timeout_minutes",
            field=models.PositiveSmallIntegerField(default=5),
        ),
        migrations.AddField(
            model_name="sharedalarm",
            name="missed_restart_enabled",
            field=models.BooleanField(default=False),
        ),
        migrations.AddField(
            model_name="sharedalarm",
            name="missed_restart_minutes",
            field=models.PositiveSmallIntegerField(default=10),
        ),
        migrations.AddField(
            model_name="alarmoccurrence",
            name="snoozed_at",
            field=models.DateTimeField(null=True),
        ),
        migrations.AddConstraint(
            model_name="sharedalarm",
            constraint=models.CheckConstraint(condition=models.Q(timeout_minutes__range=(1, 1440)), name="alarm_timeout_valid"),
        ),
        migrations.AddConstraint(
            model_name="sharedalarm",
            constraint=models.CheckConstraint(condition=models.Q(missed_restart_minutes__range=(1, 1440)), name="alarm_restart_delay_valid"),
        ),
        migrations.RemoveConstraint(
            model_name="alarmoccurrence",
            name="occurrence_deadline_after_trigger",
        ),
        migrations.AddConstraint(
            model_name="alarmoccurrence",
            constraint=models.CheckConstraint(condition=~models.Q(state="pending") | models.Q(snoozed_until=None, deadline_at__gt=models.F("trigger_at")) | models.Q(snoozed_until__isnull=False, deadline_at__gt=models.F("snoozed_until")), name="occurrence_deadline_after_ring"),
        ),
        migrations.RunPython(initialize_alarm_timing, migrations.RunPython.noop),
    ]
