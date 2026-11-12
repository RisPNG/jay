from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("social", "0014_identity_client_protocol")]

    operations = [
        migrations.AddField(
            model_name="pushsubscription",
            name="group_ring_enabled",
            field=models.BooleanField(default=False, db_default=False),
        ),
    ]
