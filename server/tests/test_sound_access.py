import base64
import hashlib
from datetime import timedelta
from io import StringIO
from uuid import uuid4

import pytest
from django.core.management import call_command
from django.core.management.base import CommandError
from django.db import connection
from django.db.migrations.executor import MigrationExecutor
from django.utils import timezone

from jay_server.social.models import GroupMembership, Identity, SharedSoundEntitlement, SyncVersion
from jay_server.social.worker import maintain_state


pytestmark = pytest.mark.django_db(transaction=True)


def test_migration_preserves_existing_play_access():
    before = [("social", "0003_label_colors")]
    after = [("social", "0005_identity_purge")]
    executor = MigrationExecutor(connection)
    executor.migrate(before)
    try:
        apps = executor.loader.project_state(before).apps
        scope = apps.get_model("social", "SyncScope").objects.create()
        identity = apps.get_model("social", "Identity").objects.create(id="a" * 64, name="Paid user", token_hash=b"a" * 32, scope=scope)
        granted_at = timezone.now() - timedelta(hours=1)
        expires_at = timezone.now() + timedelta(hours=47)
        apps.get_model("social", "PlayEntitlement").objects.create(identity=identity, verified_at=granted_at, expires_at=expires_at)
    finally:
        MigrationExecutor(connection).migrate(after)
    entitlement = SharedSoundEntitlement.objects.get(identity_id="a" * 64)
    assert entitlement.source == "play"
    assert entitlement.granted_at == granted_at
    assert entitlement.expires_at == expires_at


def test_operator_access_is_scoped_revocable_and_synchronized(client, group, member):
    call_command("grant_sound_access", "1" * 64, stdout=StringIO())
    expected = {"shared_sound_upload": True, "requires_play_entitlement": False, "expires_at": None}
    assert client.get("/v1/identity/capabilities").data == expected
    assert not member.get("/v1/identity/capabilities").data["shared_sound_upload"]
    assert SyncVersion.objects.get(kind="capabilities", key="1" * 64, superseded_at=None).representation == expected
    payload = {"id": str(uuid4()), "membership_id": str(GroupMembership.objects.get(group=group, identity_id="1" * 64).pk), "title": "Review sound", "sha256": "a" * 64, "byte_length": 100, "duration_ms": 1000}
    assert client.post(f"/v1/groups/{group.pk}/sounds/uploads", payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    call_command("grant_sound_access", "1" * 64, revoke=True, stdout=StringIO())
    assert client.get("/v1/identity/capabilities").data == {"shared_sound_upload": False, "requires_play_entitlement": True, "expires_at": None}
    assert client.post(f"/v1/groups/{group.pk}/sounds/uploads", {**payload, "id": str(uuid4())}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 403


def test_operator_access_preserves_group_permissions(client, group, member):
    call_command("grant_sound_access", "2" * 64, stdout=StringIO())
    group.alarm_permission = "leaders"
    group.save(update_fields=["alarm_permission"])
    payload = {"id": str(uuid4()), "membership_id": str(GroupMembership.objects.get(group=group, identity_id="2" * 64).pk), "title": "Review sound", "sha256": "a" * 64, "byte_length": 100, "duration_ms": 1000}
    assert member.get("/v1/identity/capabilities").data["shared_sound_upload"]
    assert member.post(f"/v1/groups/{group.pk}/sounds/uploads", payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 403


def test_operator_profile_skips_play_verification(client, monkeypatch):
    from jay_server.social import providers

    call_command("grant_sound_access", "1" * 64, stdout=StringIO())
    def verify(*args):
        pytest.fail("Operator access must not call Play Integrity")
    monkeypatch.setattr(providers, "verify_play_entitlement", verify)
    result = client.post("/v1/identity/play-entitlement", {"integrity_token": "not-a-play-token"}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert result.status_code == 200
    assert result.data["shared_sound_upload"]
    assert not result.data["requires_play_entitlement"]


@pytest.mark.parametrize("licensed", [True, False])
def test_inflight_play_verification_cannot_overwrite_operator_grant(client, monkeypatch, licensed):
    from jay_server.social import providers

    def verify(*args):
        call_command("grant_sound_access", "1" * 64, stdout=StringIO())
        return licensed
    monkeypatch.setattr(providers, "verify_play_entitlement", verify)
    result = client.post("/v1/identity/play-entitlement", {"integrity_token": "inflight"}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert result.status_code == 200
    assert result.data == {"shared_sound_upload": True, "requires_play_entitlement": False, "expires_at": None}
    assert SharedSoundEntitlement.objects.get(identity_id="1" * 64).source == "operator"


def test_play_access_still_expires_and_can_be_revoked_by_play(client, monkeypatch):
    from jay_server.social import providers

    monkeypatch.setattr(providers, "verify_play_entitlement", lambda *args: True)
    response = client.post("/v1/identity/play-entitlement", {"integrity_token": "licensed"}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 200
    assert response.data["shared_sound_upload"] and response.data["requires_play_entitlement"]
    call_command("grant_sound_access", "1" * 64, revoke=True, stdout=StringIO())
    entitlement = SharedSoundEntitlement.objects.get(identity_id="1" * 64)
    assert entitlement.source == "play"
    entitlement.expires_at = timezone.now() - timedelta(seconds=1)
    entitlement.save()
    assert not client.get("/v1/identity/capabilities").data["shared_sound_upload"]
    monkeypatch.setattr(providers, "verify_play_entitlement", lambda *args: False)
    assert client.post("/v1/identity/play-entitlement", {"integrity_token": "unlicensed"}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 200
    assert not SharedSoundEntitlement.objects.exists()


def test_operator_profile_survives_inactivity_but_can_be_deleted(client, group, member):
    call_command("grant_sound_access", "1" * 64, stdout=StringIO())
    Identity.objects.update(last_seen_at=timezone.now() - timedelta(days=121))
    maintain_state()
    assert Identity.objects.get(pk="1" * 64).retired_at is None
    assert Identity.objects.get(pk="2" * 64).retired_at is not None
    assert client.delete("/v1/identity", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    with pytest.raises(CommandError, match="Active profile not found"):
        call_command("grant_sound_access", "1" * 64, stdout=StringIO())


def test_command_accepts_exported_profile_without_printing_secret(client, monkeypatch):
    from jay_server.social.management.commands import grant_sound_access

    secret = bytes(range(32))
    identity_id = hashlib.sha256(secret).hexdigest()
    assert client.post("/v1/identities/register", {"id": identity_id, "name": "Review", "token": "review-token" * 5, "time_zone": "UTC"}, format="json").status_code == 201
    encoded = base64.urlsafe_b64encode(secret).decode()
    monkeypatch.setattr(grant_sound_access, "getpass", lambda prompt: f"https://jay.poppybit.com/profile#name=Review&key={encoded}")
    output = StringIO()
    call_command("grant_sound_access", stdout=output)
    assert SharedSoundEntitlement.objects.get(identity_id=identity_id).source == "operator"
    assert encoded not in output.getvalue()


@pytest.mark.parametrize("profile", ["not a link", "https://jay.poppybit.com/profile#name=Review", "https://jay.poppybit.com/profile#key=invalid"])
def test_command_rejects_invalid_profiles(monkeypatch, profile):
    from jay_server.social.management.commands import grant_sound_access

    monkeypatch.setattr(grant_sound_access, "getpass", lambda prompt: profile)
    with pytest.raises(CommandError, match="valid exported Jay profile"):
        call_command("grant_sound_access", stdout=StringIO())
