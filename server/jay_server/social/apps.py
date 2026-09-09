from django.apps import AppConfig


class SocialConfig(AppConfig):
    name = "jay_server.social"

    def ready(self):
        from . import schema
