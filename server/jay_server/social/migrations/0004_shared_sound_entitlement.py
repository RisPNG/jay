from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ('social', '0003_label_colors'),
    ]

    operations = [
        migrations.RenameModel(
            old_name='PlayEntitlement',
            new_name='SharedSoundEntitlement',
        ),
        migrations.RenameField(
            model_name='sharedsoundentitlement',
            old_name='verified_at',
            new_name='granted_at',
        ),
        migrations.AddField(
            model_name='sharedsoundentitlement',
            name='source',
            field=models.CharField(choices=[('play', 'Play'), ('operator', 'Operator')], default='play', max_length=8),
        ),
        migrations.AlterField(
            model_name='sharedsoundentitlement',
            name='expires_at',
            field=models.DateTimeField(null=True),
        ),
        migrations.AddConstraint(
            model_name='sharedsoundentitlement',
            constraint=models.CheckConstraint(condition=models.Q(source='play', expires_at__isnull=False) | models.Q(source='operator', expires_at__isnull=True), name='sound_entitlement_expiry_valid'),
        ),
    ]
