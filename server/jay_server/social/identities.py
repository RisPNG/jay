from django.db import transaction
from django.utils import timezone

from .models import DeliveryWork, Identity, PushSubscription, SharedSoundEntitlement


def retire_identity(identity):
    with transaction.atomic():
        identity = Identity.objects.select_for_update().get(pk=identity.pk)
        if identity.retired_at is not None:
            return
        identity.retired_at = timezone.now()
        identity.save(update_fields=["retired_at"])
        PushSubscription.objects.filter(identity=identity).delete()
        SharedSoundEntitlement.objects.filter(identity=identity).delete()
        DeliveryWork.objects.get_or_create(
            kind="retire", deduplication_key=f"retire:{identity.pk}",
            defaults={"payload": {"identity_id": identity.pk}},
        )
