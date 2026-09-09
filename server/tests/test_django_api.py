from datetime import timedelta
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from uuid import uuid4

import pytest
from django.db import close_old_connections, connection, transaction
from django.test import override_settings
from django.utils import timezone
from rest_framework.test import APIClient

from jay_server.social.models import DeliveryWork, Group, GroupMembership, Identity, SharedSound, SharedTimer, SyncScope, SyncVersion
from jay_server.social.synchronization import publish_changes


pytestmark = pytest.mark.django_db(transaction=True)


def test_registration_cannot_replace_credentials(client):
    before = bytes(Identity.objects.get(pk="1" * 64).token_hash)
    response = client.post("/v1/identities/register", {"id": "1" * 64, "name": "Mallory", "token": "different" * 8, "time_zone": "UTC"}, format="json")
    assert response.status_code == 409
    assert bytes(Identity.objects.get(pk="1" * 64).token_hash) == before


def test_timer_retry_and_payload_binding(client, group, timer_payload):
    key = str(uuid4())
    response = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": key})
    assert response.status_code == 201, response.data
    repeated = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": key})
    assert repeated.data == response.data
    assert SharedTimer.objects.count() == 1
    changed = client.post(f"/v1/groups/{group.pk}/timers", {**timer_payload, "label": "Changed"}, format="json", headers={"Idempotency-Key": key})
    assert changed.status_code == 409
    assert changed.data["code"] == "idempotency_mismatch"


def test_latest_saved_timer_state_wins_without_accumulating(client, group, timer_payload):
    created = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    timer = SharedTimer.objects.get(pk=timer_payload.pop("id"))
    expected = timer.expires_at + timedelta(minutes=1)
    first_saved = timezone.now() - timedelta(seconds=5)
    for saved in [first_saved, first_saved + timedelta(seconds=1)]:
        response = client.put(f"/v1/timers/{timer.pk}", {**timer_payload, "saved_at": saved.isoformat(), "expires_at": expected.isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
        assert response.status_code == 200, response.data
    timer.refresh_from_db()
    assert timer.expires_at == expected
    rejected = client.put(f"/v1/timers/{timer.pk}", {**timer_payload, "saved_at": first_saved.isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert rejected.status_code == 409
    assert rejected.data["code"] == "superseded"
    timer.refresh_from_db()
    assert timer.expires_at == expected


def test_deleted_timer_is_not_resurrected(client, group, timer_payload):
    response = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201
    timer_id = timer_payload.pop("id")
    assert client.delete(f"/v1/timers/{timer_id}", {"membership_id": timer_payload["membership_id"]}, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 204
    edit = client.put(f"/v1/timers/{timer_id}", {**timer_payload, "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert edit.status_code == 404
    assert SharedTimer.objects.get(pk=timer_id).deleted_at is not None


def test_snapshot_and_delta_preserve_typed_state(client, group, timer_payload):
    snapshot = client.get(f"/v1/groups/{group.pk}/sync")
    assert snapshot.status_code == 200, snapshot.data
    assert snapshot.data["mode"] == "snapshot"
    assert not snapshot.data["has_more"]
    assert {item["kind"] for item in snapshot.data["items"]} == {"group", "member"}
    response = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201
    delta = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": snapshot.data["next_cursor"]})
    assert delta.status_code == 200, delta.data
    assert delta.data["mode"] == "delta"
    assert [item["kind"] for item in delta.data["items"]] == ["timer"]
    assert delta.data["items"][0]["data"]["id"] == timer_payload["id"]


def test_publication_rolls_back_with_domain_state(group):
    before = SyncVersion.objects.filter(scope=group.scope).count()
    with pytest.raises(RuntimeError):
        with transaction.atomic():
            publish_changes(group.scope_id, [("timer", str(uuid4()), "upsert", {"id": str(uuid4())}, None)], group=group)
            raise RuntimeError("rollback")
    assert SyncVersion.objects.filter(scope=group.scope).count() == before


def test_sound_cascade_enqueues_cleanup_atomically(group):
    sound = SharedSound.objects.create(group=group, uploaded_by=Identity.objects.first(), title="Sound", object_key="sounds/final.flac", staging_key="sounds/upload.flac")
    with pytest.raises(RuntimeError):
        with transaction.atomic():
            SharedSound.objects.filter(pk=sound.pk).delete()
            assert DeliveryWork.objects.filter(kind="delete_sound").count() == 2
            raise RuntimeError("rollback")
    assert SharedSound.objects.filter(pk=sound.pk).exists()
    assert not DeliveryWork.objects.filter(kind="delete_sound").exists()
    SharedSound.objects.filter(pk=sound.pk).delete()
    assert set(DeliveryWork.objects.filter(kind="delete_sound").values_list("payload__object_key", flat=True)) == {"sounds/final.flac", "sounds/upload.flac"}


def test_delayed_publication_cannot_be_skipped(client, group):
    checkpoint = client.get(f"/v1/groups/{group.pk}/sync").data["next_cursor"]
    published = Event()
    release = Event()
    second_started = Event()
    first_key, second_key = str(uuid4()), str(uuid4())

    def delayed_publication():
        close_old_connections()
        try:
            with transaction.atomic():
                publish_changes(group.scope_id, [("timer", first_key, "upsert", {"id": first_key}, None)], group=group)
                published.set()
                assert release.wait(5)
        finally:
            close_old_connections()

    def following_publication():
        close_old_connections()
        try:
            second_started.set()
            with transaction.atomic():
                publish_changes(group.scope_id, [("timer", second_key, "upsert", {"id": second_key}, None)], group=group)
        finally:
            close_old_connections()

    with ThreadPoolExecutor(max_workers=2) as executor:
        first = executor.submit(delayed_publication)
        try:
            assert published.wait(5)
            second = executor.submit(following_publication)
            assert second_started.wait(5)
            interim = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": checkpoint})
            assert interim.status_code == 200
            assert interim.data["items"] == []
        finally:
            release.set()
        first.result(timeout=5)
        second.result(timeout=5)
    final = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": interim.data["next_cursor"]})
    assert {row["key"] for row in final.data["items"]} == {first_key, second_key}


@override_settings(SYNC_PAGE_SIZE=1)
def test_snapshot_pagination_keeps_a_fixed_view(client, group, timer_payload):
    created = client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201
    first = client.get(f"/v1/groups/{group.pk}/sync").data
    assert first["has_more"]
    timer_id = timer_payload.pop("id")
    updated = client.put(f"/v1/timers/{timer_id}", {**timer_payload, "label": "Later", "saved_at": timezone.now().isoformat()}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert updated.status_code == 200
    items, page = first["items"], first
    while page["has_more"]:
        page = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": page["next_cursor"]}).data
        items.extend(page["items"])
    assert next(row for row in items if row["kind"] == "timer")["data"]["label"] == "Tea"
    delta = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": page["next_cursor"]}).data
    assert delta["items"][0]["data"]["label"] == "Later"


@override_settings(SYNC_PAGE_SIZE=1)
def test_snapshot_progresses_after_retention_floor_advances(client, group):
    SyncScope.objects.filter(pk=group.scope_id).update(retention_floor=1)
    first = client.get(f"/v1/groups/{group.pk}/sync").data
    assert first["has_more"]
    second = client.get(f"/v1/groups/{group.pk}/sync", {"cursor": first["next_cursor"]}).data
    assert second["items"][0]["key"] != first["items"][0]["key"]
    assert not second["has_more"]


@pytest.mark.parametrize("field,value", [("expires_at", "2026-09-09T12:00:00"), ("duration_seconds", "300"), ("vibration_pattern", [False, "100"])])
def test_commands_reject_ambiguous_types(client, group, timer_payload, field, value):
    response = client.post(f"/v1/groups/{group.pk}/timers", {**timer_payload, field: value}, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 400
    assert not SharedTimer.objects.exists()


def test_push_coalescing_preserves_a_change_during_delivery(group):
    from jay_server.social.worker import claim_work, finish_work

    DeliveryWork.objects.exclude(deduplication_key=f"push:{group.scope_id}").delete()
    owner = uuid4()
    work = claim_work("push", owner)
    assert work is not None
    with transaction.atomic():
        publish_changes(group.scope_id, [("timer", str(uuid4()), "upsert", {}, None)], group=group)
    finish_work(work, owner)
    assert DeliveryWork.objects.filter(kind="push").count() == 1
    next_work = claim_work("push", owner)
    assert next_work.payload["revision"] > work.payload["revision"]
    finish_work(next_work, owner)
    assert not DeliveryWork.objects.filter(kind="push").exists()


def test_deleted_sound_id_cannot_be_recreated(group):
    from django.db import IntegrityError

    sound_id = uuid4()
    SharedSound.objects.create(id=sound_id, group=group, title="Removed", object_key="removed/final", staging_key="removed/staging")
    SharedSound.objects.filter(pk=sound_id).delete()
    with pytest.raises(IntegrityError), transaction.atomic():
        SharedSound.objects.create(id=sound_id, group=group, title="Reused", object_key="reuse/final", staging_key="reuse/staging")


def test_openapi_references_resolve(client):
    import json
    from pathlib import Path
    from drf_spectacular.generators import SchemaGenerator
    from drf_spectacular.renderers import OpenApiJsonRenderer
    from drf_spectacular.validation import validate_schema

    schema = SchemaGenerator().get_schema(public=True)
    validate_schema(schema)
    assert json.loads(OpenApiJsonRenderer().render(schema)) == json.loads((Path(__file__).parents[2] / "docs/openapi.json").read_text())
    unresolved = [schema]
    while unresolved:
        entry = unresolved.pop()
        if isinstance(entry, dict):
            if "$ref" in entry:
                target = schema
                for name in entry["$ref"].removeprefix("#/").split("/"):
                    target = target[name]
            unresolved.extend(entry.values())
        elif isinstance(entry, list):
            unresolved.extend(entry)


def test_concurrent_duplicate_timer_create_has_one_effect(client, group, timer_payload):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier
    from django.db import connections
    from rest_framework.test import APIClient

    key = str(uuid4())
    gate = Barrier(2)
    def create_timer(attempt):
        api = APIClient()
        api.credentials(HTTP_AUTHORIZATION="Bearer " + "secret" * 10, HTTP_X_JAY_IDENTITY_ID="1" * 64)
        try:
            gate.wait(timeout=5)
            response = api.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": key})
            return response.status_code, response.data
        finally:
            connections.close_all()
    with ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(executor.map(create_timer, [1, 2]))
    assert responses[0] == responses[1]
    assert responses[0][0] == 201
    assert SharedTimer.objects.filter(pk=timer_payload["id"]).count() == 1


def test_sync_response_matches_its_published_schema(client, group, timer_payload):
    import json
    from pathlib import Path
    from jsonschema import Draft7Validator

    assert client.post(f"/v1/groups/{group.pk}/timers", timer_payload, format="json", headers={"Idempotency-Key": str(uuid4())}).status_code == 201
    schema = json.loads((Path(__file__).parents[2] / "docs/openapi.json").read_text())
    pending = [schema]
    while pending:
        entry = pending.pop()
        if isinstance(entry, dict):
            if entry.pop("nullable", False):
                original = entry.copy()
                entry.clear()
                entry["anyOf"] = [original, {"type": "null"}]
            pending.extend(entry.values())
        elif isinstance(entry, list):
            pending.extend(entry)
    contract = {"$ref": "#/components/schemas/ScopeSyncRepresentation", "components": schema["components"]}
    for path in ["/v1/sync", f"/v1/groups/{group.pk}/sync"]:
        response = client.get(path)
        assert response.status_code == 200
        Draft7Validator(contract).validate(response.json())


def test_snapshot_limits_resolved_occurrence_history(client, group):
    now = timezone.now()
    with transaction.atomic():
        publish_changes(group.scope_id, [
            ("occurrence", "pending", "upsert", {"id": "pending", "state": "pending", "resolved_at": None}, None),
            ("occurrence", "recent", "upsert", {"id": "recent", "state": "dismissed", "resolved_at": now.isoformat()}, None),
            ("occurrence", "old", "upsert", {"id": "old", "state": "ignored", "resolved_at": (now - timedelta(days=3)).isoformat()}, None),
            ("occurrence", "canceled", "upsert", {"id": "canceled", "state": "canceled", "resolved_at": now.isoformat()}, None),
        ], group=group)
    response = client.get(f"/v1/groups/{group.pk}/sync")
    assert response.status_code == 200
    assert {item["key"] for item in response.data["items"] if item["kind"] == "occurrence"} == {"pending", "recent"}


def test_api_contract_exposes_the_supported_operations():
    import json
    from pathlib import Path

    schema = json.loads((Path(__file__).parents[2] / "docs/openapi.json").read_text())
    expected = {
        ("delete", "/v1/alarms/{alarm_id}"),
        ("delete", "/v1/groups/{group_id}"),
        ("delete", "/v1/groups/{group_id}/members/{member_id}"),
        ("delete", "/v1/groups/{group_id}/membership"),
        ("delete", "/v1/identity"),
        ("delete", "/v1/timers/{timer_id}"),
        ("get", "/.well-known/assetlinks.json"),
        ("get", "/health"),
        ("get", "/health/live"),
        ("get", "/health/ready"),
        ("get", "/join"),
        ("get", "/profile"),
        ("get", "/v1/alarms/{alarm_id}/activity"),
        ("get", "/v1/events"),
        ("get", "/v1/groups/{group_id}/activity"),
        ("get", "/v1/groups/{group_id}/sync"),
        ("get", "/v1/identity/capabilities"),
        ("get", "/v1/sounds/{sound_id}"),
        ("get", "/v1/sounds/{sound_id}/download"),
        ("get", "/v1/sounds/{sound_id}/upload"),
        ("get", "/v1/sync"),
        ("patch", "/v1/groups/{group_id}/members/{member_id}"),
        ("patch", "/v1/identity"),
        ("post", "/v1/alarms"),
        ("post", "/v1/alarms/{alarm_id}/activity"),
        ("post", "/v1/alarms/{alarm_id}/deliveries"),
        ("post", "/v1/groups"),
        ("post", "/v1/groups/join"),
        ("post", "/v1/groups/{group_id}/invites"),
        ("post", "/v1/groups/{group_id}/sounds/uploads"),
        ("post", "/v1/groups/{group_id}/timers"),
        ("post", "/v1/identities/register"),
        ("post", "/v1/identity/play-entitlement"),
        ("post", "/v1/sounds/{sound_id}/complete"),
        ("put", "/v1/alarms/{alarm_id}"),
        ("put", "/v1/alarms/{alarm_id}/occurrence"),
        ("put", "/v1/groups/{group_id}"),
        ("put", "/v1/groups/{group_id}/notification-settings"),
        ("put", "/v1/identity/push-token"),
        ("put", "/v1/timers/{timer_id}"),
    }
    actual = {(method, path) for path, methods in schema["paths"].items() for method in methods if method in {"get", "post", "put", "patch", "delete"}}
    actual.update({("get", "/join"), ("get", "/profile")})
    assert actual == expected
