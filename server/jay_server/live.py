import asyncio
import json
import logging
from contextlib import suppress
from typing import AsyncIterator

from psycopg import AsyncConnection

from jay_server.config import settings


logger = logging.getLogger(__name__)


class LiveChangeBroker:
    def __init__(self) -> None:
        self.listener_task: asyncio.Task | None = None
        self.subscribers: set[tuple[str, asyncio.Queue[None]]] = set()

    async def start(self) -> None:
        self.listener_task = asyncio.create_task(self.listen())

    async def stop(self) -> None:
        if self.listener_task is not None:
            self.listener_task.cancel()
            with suppress(asyncio.CancelledError):
                await self.listener_task
            self.listener_task = None

    async def listen(self) -> None:
        database_url = settings.database_url.replace(
            "postgresql+psycopg://", "postgresql://"
        )
        while True:
            try:
                async with await AsyncConnection.connect(
                    database_url, autocommit=True
                ) as listener_connection, await AsyncConnection.connect(
                    database_url, autocommit=True
                ) as lookup_connection:
                    await listener_connection.execute("LISTEN jay_changes")
                    while True:
                        async for notification in listener_connection.notifies(timeout=30):
                            change = json.loads(notification.payload)
                            members = await lookup_connection.execute(
                                "SELECT device_id FROM group_members WHERE group_id = %s",
                                (change["group_id"],),
                            )
                            device_ids = {row[0] async for row in members}
                            if change["entity_type"] == "membership":
                                device_ids.add(change["entity_id"])
                            self.publish(device_ids)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Jay live-change listener disconnected")
                await asyncio.sleep(2)

    def publish(self, device_ids: set[str]) -> None:
        for device_id, queue in self.subscribers:
            if device_id in device_ids and queue.empty():
                queue.put_nowait(None)

    async def events(self, device_id: str) -> AsyncIterator[str]:
        queue: asyncio.Queue[None] = asyncio.Queue(maxsize=1)
        subscription = (device_id, queue)
        self.subscribers.add(subscription)
        try:
            yield "event: sync\ndata: connected\n\n"
            while True:
                try:
                    await asyncio.wait_for(queue.get(), timeout=15)
                    yield "event: sync\ndata: changed\n\n"
                except TimeoutError:
                    yield ": keep-alive\n\n"
        finally:
            self.subscribers.discard(subscription)


live_changes = LiveChangeBroker()
