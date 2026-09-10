from django.core.management.base import BaseCommand, CommandError

from ...identities import retire_identity
from ...models import Identity


class Command(BaseCommand):
    help = "Delete a verified profile through the same retirement process as the app."

    def add_arguments(self, parser):
        parser.add_argument("identity_id")

    def handle(self, *args, **options):
        identity = Identity.objects.filter(pk=options["identity_id"]).first()
        if identity is None:
            raise CommandError("Profile not found.")
        retire_identity(identity)
        self.stdout.write(f"Profile {identity.pk} is retired; associated data cleanup is queued.")
