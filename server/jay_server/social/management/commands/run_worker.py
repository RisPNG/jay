import asyncio

from django.core.management.base import BaseCommand

from ...worker import Worker


class Command(BaseCommand):
    def handle(self, *args, **options):
        asyncio.run(Worker().run())
