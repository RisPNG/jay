from django.core.management.base import BaseCommand
from django.utils import timezone

from ...models import DeliveryWork, SharedSound


class Command(BaseCommand):
    help = "Retry shared-sound verification jobs stopped by the nullable-join error."

    def handle(self, *args, **options):
        retried = 0
        failed = DeliveryWork.objects.filter(kind="verify", error_code="NotSupportedError", failed_at__isnull=False)
        for work in failed.iterator(chunk_size=100):
            sound_id = work.payload["sound_id"]
            if work.deduplication_key != f"verify:{sound_id}" or not SharedSound.objects.filter(pk=sound_id, status="verifying").exists():
                continue
            now = timezone.now()
            retried += DeliveryWork.objects.filter(pk=work.pk, error_code="NotSupportedError", failed_at__isnull=False).update(
                failed_at=None, error_code=None, lease_owner=None, lease_until=None, available_at=now, created_at=now, attempts=0,
            )
        self.stdout.write(f"Queued {retried} shared-sound verification jobs.")
