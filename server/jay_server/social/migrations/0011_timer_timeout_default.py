from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("social", "0010_timer_repetitions")]

    operations = [
        migrations.AlterField(model_name="sharedtimer", name="timeout_minutes", field=models.PositiveSmallIntegerField(default=5)),
    ]
