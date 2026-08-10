import json
import logging

import firebase_admin
from firebase_admin import credentials, messaging

from jay_server.config import settings


logger = logging.getLogger(__name__)


def send_group_sync(tokens: list[str]) -> None:
    if not tokens or settings.firebase_credentials_json is None:
        return
    try:
        try:
            firebase_admin.get_app()
        except ValueError:
            firebase_admin.initialize_app(
                credentials.Certificate(json.loads(settings.firebase_credentials_json))
            )
        messaging.send_each(
            [
                messaging.Message(
                    data={"kind": "sync"},
                    token=token,
                    android=messaging.AndroidConfig(priority="high"),
                )
                for token in tokens
            ]
        )
    except Exception:
        logger.exception("Unable to notify group devices")
