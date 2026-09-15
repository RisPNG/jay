from django.db import migrations, models
import django.db.models.deletion
import django.utils.timezone


def retain_operator_grants(apps, schema_editor):
    apps.get_model("social", "SharedSoundEntitlement").objects.exclude(source="operator").delete()


def restore_operator_grants(apps, schema_editor):
    apps.get_model("social", "SharedSoundEntitlement").objects.update(source="operator", expires_at=None)


class Migration(migrations.Migration):
    dependencies = [("social", "0005_identity_purge")]

    operations = [
        migrations.RunPython(retain_operator_grants, migrations.RunPython.noop),
        migrations.RemoveConstraint(model_name="sharedsoundentitlement", name="sound_entitlement_expiry_valid"),
        migrations.RunPython(migrations.RunPython.noop, restore_operator_grants),
        migrations.RemoveField(model_name="sharedsoundentitlement", name="source"),
        migrations.RemoveField(model_name="sharedsoundentitlement", name="expires_at"),
        migrations.RenameModel(old_name="SharedSoundEntitlement", new_name="ProfileSoundGrant"),
        migrations.CreateModel(name="PlayInstallation", fields=[
            ("credential_hash", models.CharField(max_length=64, primary_key=True, serialize=False)),
            ("verified_at", models.DateTimeField(default=django.utils.timezone.now)),
            ("expires_at", models.DateTimeField()),
        ]),
        migrations.AddField(model_name="sharedsound", name="installation", field=models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, to="social.playinstallation")),
    ]
