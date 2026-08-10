import base64
import hashlib
import json
from datetime import UTC, datetime, timedelta

from fastapi import HTTPException, status
from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

from jay_server.config import settings


def verify_play_entitlement(integrity_token: str, device_id: str) -> bool:
    if settings.google_play_credentials_json is None:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Google Play verification is not configured",
        )
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(settings.google_play_credentials_json),
        scopes=["https://www.googleapis.com/auth/playintegrity"],
    )
    response = AuthorizedSession(credentials).post(
        "https://playintegrity.googleapis.com/v1/com.rispng.jay:decodeIntegrityToken",
        json={"integrity_token": integrity_token},
        timeout=15,
    )
    if not response.ok:
        raise HTTPException(
            status.HTTP_502_BAD_GATEWAY,
            "Google Play could not verify the integrity token",
        )
    try:
        payload = response.json()["tokenPayloadExternal"]
        request = payload["requestDetails"]
        expected_hash = base64.urlsafe_b64encode(
            hashlib.sha256(f"jay-play-entitlement:{device_id}".encode()).digest()
        ).decode().rstrip("=")
        requested_at = datetime.fromtimestamp(
            int(request["timestampMillis"]) / 1000, UTC
        )
    except (KeyError, TypeError, ValueError):
        raise HTTPException(
            status.HTTP_502_BAD_GATEWAY,
            "Google Play returned an invalid integrity verdict",
        ) from None
    now = datetime.now(UTC)
    if (
        request["requestPackageName"] != "com.rispng.jay"
        or request["requestHash"] != expected_hash
        or requested_at < now - timedelta(minutes=5)
        or requested_at > now + timedelta(minutes=1)
    ):
        raise HTTPException(
            status.HTTP_403_FORBIDDEN,
            "The integrity verdict does not match this device request",
        )
    app_integrity = payload.get("appIntegrity", {})
    return (
        payload.get("accountDetails", {}).get("appLicensingVerdict") == "LICENSED"
        and app_integrity.get("appRecognitionVerdict") == "PLAY_RECOGNIZED"
        and app_integrity.get("packageName") == "com.rispng.jay"
    )
