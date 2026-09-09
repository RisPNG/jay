import base64
import hashlib
import json
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest
from contextlib import nullcontext
from jay_server.social.errors import DomainError

from jay_server.social import providers as play_integrity


def test_play_entitlement_verdict_validation(monkeypatch) -> None:
    device_id = "a" * 64
    request_hash = base64.urlsafe_b64encode(
        hashlib.sha256(f"jay-play-entitlement:{device_id}".encode()).digest()
    ).decode().rstrip("=")
    payload = {
        "tokenPayloadExternal": {
            "requestDetails": {
                "requestPackageName": "com.rispng.jay",
                "requestHash": request_hash,
                "timestampMillis": str(int(datetime.now(UTC).timestamp() * 1000)),
            },
            "accountDetails": {"appLicensingVerdict": "LICENSED"},
            "appIntegrity": {
                "appRecognitionVerdict": "PLAY_RECOGNIZED",
                "packageName": "com.rispng.jay",
            },
        }
    }

    monkeypatch.setattr(
        play_integrity.settings,
        "GOOGLE_PLAY_CREDENTIALS_JSON",
        json.dumps({"type": "service_account"}),
    )
    monkeypatch.setattr(
        play_integrity.service_account.Credentials,
        "from_service_account_info",
        lambda info, scopes: object(),
    )
    monkeypatch.setattr(
        play_integrity,
        "AuthorizedSession",
        lambda credentials: nullcontext(SimpleNamespace(
            post=lambda url, json, timeout: SimpleNamespace(
                ok=True,
                json=lambda: payload,
            )
        )),
    )

    assert play_integrity.verify_play_entitlement("token", device_id) is True
    payload["tokenPayloadExternal"]["requestDetails"]["requestHash"] = "wrong"
    with pytest.raises(DomainError) as exception:
        play_integrity.verify_play_entitlement("token", device_id)
    assert exception.value.status_code == 403
