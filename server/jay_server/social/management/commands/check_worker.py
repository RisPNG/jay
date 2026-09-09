from datetime import timedelta

from django.core.management.base import BaseCommand, CommandError
from django.utils import timezone

from ...models import AlarmOccurrence, DeliveryWork, WorkerHeartbeat


class Command(BaseCommand):
    def add_arguments(self, parser):
        parser.add_argument("--instances", type=int, default=1)

    def handle(self, *args, **options):
        now = timezone.now()
        classes = {"deadlines", "maintenance", "push", "verify", "delete_sound"}
        instances = {}
        for instance, kind in WorkerHeartbeat.objects.filter(progressed_at__gte=now - timedelta(seconds=30)).values_list("instance_id", "work_class"):
            instances.setdefault(instance, set()).add(kind)
        healthy = sum(classes <= progress for progress in instances.values())
        failed = DeliveryWork.objects.filter(failed_at__isnull=False).count()
        overdue = AlarmOccurrence.objects.filter(state="pending", deadline_at__lt=now - timedelta(seconds=3)).count()
        delayed = DeliveryWork.objects.filter(kind="push", created_at__lt=now - timedelta(seconds=5)).count()
        if healthy < options["instances"] or failed or overdue or delayed:
            raise CommandError(f"healthy_instances={healthy}/{options['instances']} failed={failed} overdue={overdue} delayed_push={delayed}")
        self.stdout.write("Workers are making progress")
