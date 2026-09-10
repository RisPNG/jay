from django.db import models
from django.utils import timezone

from .common import SavedState


class Identity(SavedState):
    id = models.CharField(primary_key=True, max_length=64)
    name = models.CharField(max_length=64)
    token_hash = models.BinaryField(max_length=32)
    time_zone = models.CharField(max_length=100, default="UTC")
    last_seen_at = models.DateTimeField(default=timezone.now, db_index=True)
    retired_at = models.DateTimeField(null=True)
    purged_at = models.DateTimeField(null=True)
    scope = models.OneToOneField("SyncScope", on_delete=models.PROTECT, related_name="identity")

    @property
    def is_authenticated(self):
        return self.retired_at is None

    class Meta:
        constraints = [
            models.CheckConstraint(condition=models.Q(id__regex=r"^[a-f0-9]{64}$"), name="identity_id_hex"),
            models.CheckConstraint(condition=~models.Q(name=""), name="identity_name_nonempty"),
        ]


class PushSubscription(models.Model):
    token = models.CharField(primary_key=True, max_length=4096)
    identity = models.ForeignKey(Identity, on_delete=models.CASCADE, related_name="push_subscriptions")
    updated_at = models.DateTimeField(default=timezone.now)


class SharedSoundEntitlement(models.Model):
    class Source(models.TextChoices):
        PLAY = "play"
        OPERATOR = "operator"

    identity = models.OneToOneField(Identity, primary_key=True, on_delete=models.CASCADE)
    source = models.CharField(max_length=8, choices=Source.choices, default=Source.PLAY)
    granted_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField(null=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=models.Q(source="play", expires_at__isnull=False) | models.Q(source="operator", expires_at__isnull=True),
                name="sound_entitlement_expiry_valid",
            ),
        ]
