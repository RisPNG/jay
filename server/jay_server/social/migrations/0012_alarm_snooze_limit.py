from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("social", "0011_timer_timeout_default")]

    operations = [
        migrations.AddField(model_name="sharedalarm", name="snooze_limit", field=models.PositiveSmallIntegerField(default=0)),
        migrations.AddField(model_name="alarmoccurrence", name="snooze_count", field=models.PositiveIntegerField(default=0)),
        migrations.AddConstraint(model_name="sharedalarm", constraint=models.CheckConstraint(condition=models.Q(snooze_limit__range=(0, 100)), name="alarm_snooze_limit_valid")),
    ]
