import base64
import binascii
import hashlib
from getpass import getpass
from urllib.parse import parse_qs, urlsplit

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from django.utils import timezone

from ...access import identity_capabilities
from ...models import Identity, SharedSoundEntitlement, SyncScope
from ...synchronization import publish_changes


class Command(BaseCommand):
    help = "Grant or revoke operator-managed shared-sound access for an existing profile."

    def add_arguments(self, parser):
        parser.add_argument("identity_id", nargs="?")
        parser.add_argument("--revoke", action="store_true")

    def handle(self, *args, **options):
        identity_id = options["identity_id"]
        if identity_id is None:
            try:
                link = urlsplit(getpass("Exported Jay profile link (hidden): ").strip())
                if link.scheme != "https" or link.netloc != "jay.poppybit.com" or link.path != "/profile":
                    raise ValueError
                secret = base64.b64decode(parse_qs(link.fragment)["key"][0], altchars=b"-_", validate=True)
                if len(secret) != 32:
                    raise ValueError
            except (ValueError, KeyError, binascii.Error):
                raise CommandError("Enter a valid exported Jay profile link.") from None
            identity_id = hashlib.sha256(secret).hexdigest()
        with transaction.atomic():
            identity = Identity.objects.select_for_update().filter(pk=identity_id, retired_at=None).first()
            if identity is None:
                raise CommandError("Active profile not found. Open Jay and synchronize this profile with this server first.")
            SyncScope.objects.select_for_update().get(pk=identity.scope_id)
            if options["revoke"]:
                SharedSoundEntitlement.objects.filter(identity=identity, source=SharedSoundEntitlement.Source.OPERATOR).delete()
            else:
                SharedSoundEntitlement.objects.update_or_create(
                    identity=identity,
                    defaults={"source": SharedSoundEntitlement.Source.OPERATOR, "granted_at": timezone.now(), "expires_at": None},
                )
            publish_changes(identity.scope_id, [("capabilities", identity.pk, "upsert", identity_capabilities(identity), None)])
        action = "Revoked" if options["revoke"] else "Granted"
        self.stdout.write(f"{action} operator shared-sound access for {identity.pk}.")
