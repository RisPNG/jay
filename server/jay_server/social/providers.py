import base64
import hashlib
import json
import time
from datetime import UTC, datetime, timedelta
from functools import lru_cache
from urllib.parse import urlparse

import boto3
from botocore.config import Config
from django.conf import settings
from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

from .errors import DomainError


@lru_cache
def object_storage_client():
    if not all([settings.B2_S3_ENDPOINT, settings.B2_BUCKET_NAME, settings.B2_APPLICATION_KEY_ID, settings.B2_APPLICATION_KEY]):
        raise DomainError("storage_unavailable", "Shared sound storage is not configured", 503)
    endpoint = settings.B2_S3_ENDPOINT
    if "://" not in endpoint:
        endpoint = f"https://{endpoint}"
    host = urlparse(endpoint).hostname.split(".")
    return boto3.client(
        "s3", endpoint_url=endpoint,
        region_name=host[1] if len(host) > 2 and host[0] == "s3" else None,
        aws_access_key_id=settings.B2_APPLICATION_KEY_ID, aws_secret_access_key=settings.B2_APPLICATION_KEY,
        config=Config(signature_version="s3v4", connect_timeout=5, read_timeout=20, retries={"max_attempts": 0}),
    )


def signed_sound_upload(sound):
    return {
        "id": str(sound.pk),
        "url": object_storage_client().generate_presigned_url("put_object", Params={
            "Bucket": settings.B2_BUCKET_NAME, "Key": sound.staging_key,
            "ContentType": "audio/flac", "ContentLength": sound.byte_length,
            "Metadata": {"sha256": sound.sha256},
        }, ExpiresIn=900),
        "headers": {"Content-Type": "audio/flac", "Content-Length": str(sound.byte_length), "x-amz-meta-sha256": sound.sha256},
    }


def validate_sound_object(sound, stopping=None):
    if stopping is not None and stopping.is_set():
        raise DomainError("worker_stopping", "Sound verification will resume", 503)
    response = object_storage_client().get_object(Bucket=settings.B2_BUCKET_NAME, Key=sound.object_key)
    digest, header = hashlib.sha256(), bytearray()
    total = 0
    deadline = time.monotonic() + 300
    try:
        if response.get("ContentLength") != sound.byte_length or response.get("ContentType") != "audio/flac" or response.get("Metadata", {}).get("sha256") != sound.sha256:
            raise DomainError("sound_mismatch", "Uploaded sound metadata does not match")
        for chunk in response["Body"].iter_chunks(chunk_size=1024 * 1024):
            if time.monotonic() > deadline or (stopping is not None and stopping.is_set()):
                raise DomainError("storage_timeout", "Sound verification exceeded its deadline", 503)
            total += len(chunk)
            if total > sound.byte_length:
                raise DomainError("sound_mismatch", "Uploaded sound length does not match")
            digest.update(chunk)
            if len(header) < 42:
                header.extend(chunk[:42 - len(header)])
        if total != sound.byte_length or digest.hexdigest() != sound.sha256:
            raise DomainError("sound_mismatch", "Uploaded sound hash or length does not match")
        if len(header) != 42 or header[:4] != b"fLaC" or header[4] & 0x7F != 0 or int.from_bytes(header[5:8]) != 34:
            raise DomainError("sound_format", "Shared sound is not a valid FLAC file")
        stream = int.from_bytes(header[18:26])
        rate, channels, bits, samples = (stream >> 44) & 0xFFFFF, ((stream >> 41) & 7) + 1, ((stream >> 36) & 31) + 1, stream & ((1 << 36) - 1)
        duration = samples * 1000 // rate if rate else 0
        if rate != 48000 or channels != 1 or bits != 16 or samples == 0 or duration > 300000 or abs(duration - sound.duration_ms) > 1:
            raise DomainError("sound_format", "Shared sound format or duration does not match")
    finally:
        response["Body"].close()


def verify_play_entitlement(integrity_token, identity_id):
    if not settings.GOOGLE_PLAY_CREDENTIALS_JSON:
        raise DomainError("play_unavailable", "Google Play verification is not configured", 503)
    credentials = service_account.Credentials.from_service_account_info(json.loads(settings.GOOGLE_PLAY_CREDENTIALS_JSON), scopes=["https://www.googleapis.com/auth/playintegrity"])
    with AuthorizedSession(credentials) as session:
        response = session.post("https://playintegrity.googleapis.com/v1/com.rispng.jay:decodeIntegrityToken", json={"integrity_token": integrity_token}, timeout=(5, 20))
    if not response.ok:
        raise DomainError("play_unavailable", "Google Play could not verify this request", 503)
    try:
        payload = response.json()["tokenPayloadExternal"]
        request = payload["requestDetails"]
        requested_at = datetime.fromtimestamp(int(request["timestampMillis"]) / 1000, UTC)
        package, received_hash = request["requestPackageName"], request["requestHash"]
    except (KeyError, TypeError, ValueError, OverflowError):
        raise DomainError("play_verdict_invalid", "Google Play returned an invalid verdict", 503) from None
    expected_hash = base64.urlsafe_b64encode(hashlib.sha256(f"jay-play-entitlement:{identity_id}".encode()).digest()).decode().rstrip("=")
    now = datetime.now(UTC)
    if package != "com.rispng.jay" or received_hash != expected_hash or requested_at < now - timedelta(minutes=5) or requested_at > now + timedelta(minutes=1):
        raise DomainError("play_binding_invalid", "The verdict does not match this identity request", 403)
    app = payload.get("appIntegrity", {})
    return payload.get("accountDetails", {}).get("appLicensingVerdict") == "LICENSED" and app.get("appRecognitionVerdict") == "PLAY_RECOGNIZED" and app.get("packageName") == "com.rispng.jay"
