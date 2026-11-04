from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("social", "0013_hourly_alarms")]

    operations = [
        migrations.AddField(
            model_name="identity",
            name="client_protocol",
            field=models.PositiveSmallIntegerField(default=2, db_default=2),
        ),
    ]
