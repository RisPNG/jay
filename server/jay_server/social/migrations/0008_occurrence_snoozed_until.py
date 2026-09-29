from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("social", "0007_default_label_color")]

    operations = [
        migrations.AddField(
            model_name="alarmoccurrence",
            name="snoozed_until",
            field=models.DateTimeField(null=True),
        ),
    ]
