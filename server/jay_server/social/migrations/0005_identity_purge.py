from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ('social', '0004_shared_sound_entitlement'),
    ]

    operations = [
        migrations.AddField(
            model_name='identity',
            name='purged_at',
            field=models.DateTimeField(null=True),
        ),
    ]
