from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ("social", "0006_installation_access"),
    ]

    operations = [
        migrations.AlterField(
            model_name="sharedalarm",
            name="label_color",
            field=models.IntegerField(default=0),
        ),
        migrations.AlterField(
            model_name="sharedtimer",
            name="label_color",
            field=models.IntegerField(default=0),
        ),
    ]
