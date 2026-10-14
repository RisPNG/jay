import uuid

import django.db.models.deletion
from django.db import migrations, models, transaction


def initialize_timer_runs(apps, schema_editor):
    from jay_server.social.synchronization import publish_changes

    timers = apps.get_model("social", "SharedTimer")
    scopes = apps.get_model("social", "SyncScope")
    versions = apps.get_model("social", "SyncVersion")
    for timer in timers.objects.select_related("group").iterator():
        with transaction.atomic():
            scopes.objects.select_for_update().get(pk=timer.group.scope_id)
            timer.run_id = timer.pk
            timer.save(update_fields=["run_id"])
            if timer.deleted_at is None:
                previous = versions.objects.filter(scope_id=timer.group.scope_id, kind="timer", key=str(timer.pk)).order_by("-revision", "-ordinal").first()
                if previous is not None:
                    data = {**previous.representation, "run_id": str(timer.run_id), "timeout_minutes": timer.timeout_minutes, "repeat_enabled": timer.repeat_enabled, "repeat_count": timer.repeat_count, "repeat_index": timer.repeat_index}
                    publish_changes(timer.group.scope_id, [("timer", str(timer.pk), "upsert", data, None)], group=timer.group)


class Migration(migrations.Migration):
    dependencies = [("social", "0009_alarm_restart")]

    operations = [
        migrations.AddField(model_name="sharedtimer", name="run_id", field=models.UUIDField(null=True)),
        migrations.AddField(model_name="sharedtimer", name="timeout_minutes", field=models.PositiveSmallIntegerField(default=10)),
        migrations.AddField(model_name="sharedtimer", name="repeat_enabled", field=models.BooleanField(default=False)),
        migrations.AddField(model_name="sharedtimer", name="repeat_count", field=models.PositiveSmallIntegerField(default=0)),
        migrations.AddField(model_name="sharedtimer", name="repeat_index", field=models.PositiveIntegerField(default=0)),
        migrations.AddConstraint(model_name="sharedtimer", constraint=models.CheckConstraint(condition=models.Q(timeout_minutes__range=(1, 120)), name="timer_timeout_valid")),
        migrations.AddConstraint(model_name="sharedtimer", constraint=models.CheckConstraint(condition=models.Q(repeat_count__lte=100), name="timer_repeat_count_valid")),
        migrations.AddConstraint(model_name="sharedtimer", constraint=models.CheckConstraint(condition=models.Q(repeat_enabled=False, repeat_index=0) | models.Q(repeat_enabled=True, repeat_count=0) | models.Q(repeat_enabled=True, repeat_index__lte=models.F("repeat_count")), name="timer_repeat_index_valid")),
        migrations.CreateModel(name="TimerOccurrence", fields=[
            ("id", models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
            ("run_id", models.UUIDField()),
            ("repeat_index", models.PositiveIntegerField()),
            ("trigger_at", models.DateTimeField()),
            ("deadline_at", models.DateTimeField()),
            ("state", models.CharField(choices=[("dismissed", "Dismissed")], default="dismissed", max_length=16)),
            ("resolved_at", models.DateTimeField()),
            ("timer", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="occurrences", to="social.sharedtimer")),
            ("group", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to="social.group")),
            ("identity", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to="social.identity")),
            ("membership", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to="social.groupmembership")),
        ], options={
            "constraints": [
                models.UniqueConstraint(fields=["timer", "membership", "run_id", "repeat_index"], name="timer_occurrence_member_unique"),
                models.CheckConstraint(condition=models.Q(deadline_at__gt=models.F("trigger_at")), name="timer_occurrence_deadline_valid"),
                models.CheckConstraint(condition=models.Q(state="dismissed"), name="timer_occurrence_state_valid"),
            ],
            "indexes": [models.Index(fields=["resolved_at"], name="timer_occurrence_resolved_idx")],
        }),
        migrations.RunPython(initialize_timer_runs, migrations.RunPython.noop),
        migrations.AlterField(model_name="sharedtimer", name="run_id", field=models.UUIDField(default=uuid.uuid4)),
    ]
