from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("social", "0012_alarm_snooze_limit")]

    operations = [
        migrations.AddField(
            model_name="sharedalarm",
            name="start_with_later_offset",
            field=models.BooleanField(db_default=False, default=False),
        ),
        migrations.AddField(
            model_name="sharedalarm",
            name="last_evaluated_cycle_index",
            field=models.PositiveBigIntegerField(null=True),
        ),
        migrations.AddField(
            model_name="alarmoccurrence",
            name="cycle_index",
            field=models.PositiveBigIntegerField(null=True),
        ),
        migrations.AlterField(
            model_name="sharedalarm",
            name="repeat_unit",
            field=models.CharField(choices=[("HOUR", "Hour"), ("DAY", "Day"), ("WEEK", "Week"), ("MONTH", "Month"), ("YEAR", "Year")], max_length=8),
        ),
        migrations.RemoveConstraint(
            model_name="sharedalarm",
            name="alarm_repeat_unit_valid",
        ),
        migrations.AddConstraint(
            model_name="sharedalarm",
            constraint=models.CheckConstraint(condition=models.Q(repeat_unit__in=["HOUR", "DAY", "WEEK", "MONTH", "YEAR"]), name="alarm_repeat_unit_valid"),
        ),
        migrations.AddIndex(
            model_name="alarmoccurrence",
            index=models.Index(condition=models.Q(cycle_index__isnull=False), fields=["alarm", "alarm_revision", "cycle_index"], name="occurrence_hour_cycle_idx"),
        ),
    ]
