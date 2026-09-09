import asyncio

import pytest
from asgiref.testing import ApplicationCommunicator
from channels.db import database_sync_to_async
from django.db import transaction

from jay_server.social.live import EventConsumer, LiveBroker
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
