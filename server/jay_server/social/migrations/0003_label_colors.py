from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('social', '0002_resource_deletion'),
    ]

    operations = [
        migrations.AddField(
            model_name='sharedalarm',
            name='label_color',
            field=models.IntegerField(default=-1),
        ),
        migrations.AddField(
            model_name='sharedtimer',
            name='label_color',
            field=models.IntegerField(default=-1),
        ),
    ]
