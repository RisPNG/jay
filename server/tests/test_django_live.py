import asyncio
import json

import pytest
from asgiref.testing import ApplicationCommunicator
from channels.db import database_sync_to_async
from django.db import DatabaseError, transaction

from jay_server.asgi import Application
from jay_server.social.live import EventConsumer, GroupRingConsumer, LiveBroker
from jay_server.social.models import GroupMembership
from jay_server.social.synchronization import publish_changes


pytestmark = pytest.mark.django_db(transaction=True)


def test_live_invalidation_follows_commits_and_disconnect_cleans_up(client, group):
    def publish_timer():
        with transaction.atomic():
            publish_changes(group.scope_id, [("timer", "changed", "upsert", {"id": "changed"}, None)], group=group)

    async def scenario():
        broker = LiveBroker()
        await broker.start()
        stream = ApplicationCommunicator(EventConsumer.as_asgi(), {
            "type": "http", "method": "GET", "path": "/v1/events", "jay.broker": broker,
            "headers": [(b"host", b"testserver"), (b"authorization", ("Bearer " + "secret" * 10).encode()), (b"x-jay-identity-id", b"1" * 64)],
        })
        try:
            await asyncio.wait_for(broker.ready.wait(), timeout=5)
            await stream.send_input({"type": "http.request", "body": b"", "more_body": False})
            assert (await stream.receive_output(timeout=5))["status"] == 200
            assert b'"all":true' in (await stream.receive_output(timeout=5))["body"]
            await database_sync_to_async(publish_timer, thread_sensitive=False)()
            change = await stream.receive_output(timeout=5)
            assert str(group.scope_id).encode() in change["body"]
            assert b"changed" not in change["body"]
            await stream.send_input({"type": "http.disconnect"})
            await stream.wait(timeout=5)
            assert not broker.subscriptions
            assert not broker.scopes
        finally:
            await broker.stop()
            stream.stop()

    asyncio.run(scenario())


@pytest.mark.parametrize("path", [
    "/v1/groups/not-a-uuid/ring",
    "/v1/groups/11111111-1111-4111-8111-111111111111/extra/ring",
])
@pytest.mark.parametrize("limit,status", [("requests", 503), ("body", 413)])
def test_malformed_ring_routes_retain_http_limits(settings, path, limit, status):
    async def scenario():
        application = Application()
        application.active_requests = settings.MAX_HTTP_REQUESTS if limit == "requests" else 0
        stream = ApplicationCommunicator(application, {"type": "http", "method": "POST", "path": path, "headers": []})
        try:
            await stream.send_input({"type": "http.request", "body": b"x" * (settings.DATA_UPLOAD_MAX_MEMORY_SIZE + 1) if limit == "body" else b""})
            assert (await stream.receive_output(timeout=5))["status"] == status
            await stream.receive_output(timeout=5)
            await stream.wait(timeout=5)
        finally:
            stream.stop()
            await application.broker.stop()

    asyncio.run(scenario())


def test_group_ring_uses_live_connection_capacity(client, group, settings):
    settings.MAX_EVENT_CONNECTIONS = 1

    async def scenario():
        application = Application()
        application.active_requests = settings.MAX_HTTP_REQUESTS
        receiver = ApplicationCommunicator(application, {
            "type": "http", "method": "GET", "path": "/v1/events",
            "headers": [(b"host", b"testserver"), (b"authorization", ("Bearer " + "secret" * 10).encode()), (b"x-jay-identity-id", b"1" * 64)],
        })
        hold = ApplicationCommunicator(application, {
            "type": "http", "method": "POST", "path": f"/v1/groups/{group.pk}/ring",
            "headers": [(b"host", b"testserver")],
        })
        try:
            await receiver.send_input({"type": "http.request", "body": b""})
            assert (await receiver.receive_output(timeout=5))["status"] == 200
            await receiver.receive_output(timeout=5)
            application.active_requests = 0
            await hold.send_input({"type": "http.request", "body": b""})
            assert (await hold.receive_output(timeout=5))["status"] == 503
            await hold.receive_output(timeout=5)
            await hold.wait(timeout=5)
            await receiver.send_input({"type": "http.disconnect"})
            await receiver.wait(timeout=5)
            assert not application.broker.subscriptions
            assert not application.broker.ringers
        finally:
            hold.stop()
            receiver.stop()
            await application.broker.stop()

    asyncio.run(scenario())


def test_group_ring_database_failure_does_not_disclose_internal_details(monkeypatch):
    private_detail = "internal database host and query details"

    def unavailable(*args, **kwargs):
        raise DatabaseError(private_detail)

    monkeypatch.setattr("jay_server.social.live.publish_group_ring", unavailable)

    async def scenario():
        application = Application()
        stream = ApplicationCommunicator(application, {
            "type": "http", "method": "POST", "path": "/v1/groups/11111111-1111-4111-8111-111111111111/ring",
            "headers": [(b"host", b"testserver")],
        })
        try:
            await stream.send_input({"type": "http.request", "body": b""})
            assert (await stream.receive_output(timeout=5))["status"] == 503
            body = (await stream.receive_output(timeout=5))["body"].decode()
            assert private_detail not in body
            assert json.loads(body)["code"] == "ring_unavailable"
            assert json.loads(body)["detail"]
            await stream.wait(timeout=5)
            assert not application.broker.ringers
        finally:
            stream.stop()
            await application.broker.stop()

    asyncio.run(scenario())


def test_group_ring_stops_when_editor_permission_is_revoked(client, group, member):
    def revoke():
        group.alarm_permission = "leaders"
        group.save(update_fields=["alarm_permission"])

    async def scenario():
        broker = LiveBroker()
        stream = ApplicationCommunicator(GroupRingConsumer.as_asgi(), {
            "type": "http", "method": "POST", "path": f"/v1/groups/{group.pk}/ring", "jay.broker": broker,
            "url_route": {"kwargs": {"group_id": group.pk}},
            "headers": [(b"host", b"testserver"), (b"authorization", ("Bearer " + "another-secret" * 5).encode()), (b"x-jay-identity-id", b"2" * 64)],
        })
        try:
            await stream.send_input({"type": "http.request", "body": b""})
            assert (await stream.receive_output(timeout=5))["status"] == 200
            await stream.receive_output(timeout=5)
            await database_sync_to_async(revoke, thread_sensitive=False)()
            response = await stream.receive_output(timeout=5)
            assert response["more_body"] is False
            await stream.send_input({"type": "http.disconnect"})
            await stream.wait(timeout=5)
            assert not broker.ringers
        finally:
            stream.stop()
            await broker.stop()

    asyncio.run(scenario())


async def receive_ring(stream, active):
    while True:
        response = await stream.receive_output(timeout=5)
        body = response.get("body", b"").decode()
        for frame in body.split("\n\n"):
            if frame.startswith("event: ring\n"):
                event = json.loads(frame.split("data: ", 1)[1])
                if event["active"] == active:
                    return event, body


def test_group_ring_reaches_connected_members_and_stops_on_release(client, group, member):
    async def scenario():
        sender_application = Application()
        receiver_broker, sender_broker = LiveBroker(), sender_application.broker
        await receiver_broker.start()
        await sender_broker.start()
        stream = ApplicationCommunicator(EventConsumer.as_asgi(), {
            "type": "http", "method": "GET", "path": "/v1/events", "jay.broker": receiver_broker,
            "headers": [(b"host", b"testserver"), (b"authorization", ("Bearer " + "secret" * 10).encode()), (b"x-jay-identity-id", b"1" * 64)],
        })
        hold = ApplicationCommunicator(sender_application, {
            "type": "http", "method": "POST", "path": f"/v1/groups/{group.pk}/ring",
            "headers": [(b"host", b"testserver"), (b"authorization", ("Bearer " + "another-secret" * 5).encode()), (b"x-jay-identity-id", b"2" * 64)],
        })
        try:
            await asyncio.wait_for(receiver_broker.ready.wait(), timeout=5)
            await asyncio.wait_for(sender_broker.ready.wait(), timeout=5)
            await stream.send_input({"type": "http.request", "body": b""})
            assert (await stream.receive_output(timeout=5))["status"] == 200
            await stream.receive_output(timeout=5)
            await hold.send_input({"type": "http.request", "body": b""})
            assert (await hold.receive_output(timeout=5))["status"] == 200
            await hold.receive_output(timeout=5)
            started, body = await receive_ring(stream, True)
            assert started["group_id"] == str(group.pk)
            assert started["lease_seconds"] == 5
            assert "event: sync" not in body
            renewed, _ = await receive_ring(stream, True)
            assert renewed["session_id"] == started["session_id"]
            await hold.send_input({"type": "http.disconnect"})
            await hold.wait(timeout=5)
            stopped, _ = await receive_ring(stream, False)
            assert stopped["session_id"] == started["session_id"]
            assert not sender_broker.ringers
            await stream.send_input({"type": "http.disconnect"})
            await stream.wait(timeout=5)
        finally:
            hold.stop()
            stream.stop()
            await sender_broker.stop()
            await receiver_broker.stop()

    asyncio.run(scenario())


@pytest.mark.parametrize("role,permission,removed,status", [
    ("member", "leaders", False, 403),
    ("member", "everyone", True, 404),
    ("leader", "leaders", False, 200),
])
def test_group_ring_requires_current_editor_permission(client, group, member, role, permission, removed, status):
    group.alarm_permission = permission
    group.save(update_fields=["alarm_permission"])
    membership = GroupMembership.objects.get(group=group, identity_id="2" * 64)
    membership.role = role
    if removed:
        from django.utils import timezone
        membership.removed_at = timezone.now()
    membership.save()

    async def scenario():
        broker = LiveBroker()
        stream = ApplicationCommunicator(GroupRingConsumer.as_asgi(), {
            "type": "http", "method": "POST", "path": f"/v1/groups/{group.pk}/ring", "jay.broker": broker,
            "url_route": {"kwargs": {"group_id": group.pk}},
            "headers": [(b"host", b"testserver"), (b"authorization", ("Bearer " + "another-secret" * 5).encode()), (b"x-jay-identity-id", b"2" * 64)],
        })
        try:
            await stream.send_input({"type": "http.request", "body": b""})
            assert (await stream.receive_output(timeout=5))["status"] == status
            if status == 200:
                await stream.receive_output(timeout=5)
                await stream.send_input({"type": "http.disconnect"})
            await stream.wait(timeout=5)
            assert not broker.ringers
        finally:
            stream.stop()
            await broker.stop()

    asyncio.run(scenario())
