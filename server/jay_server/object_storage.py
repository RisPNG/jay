import hashlib
from functools import lru_cache
from urllib.parse import urlparse

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError
from fastapi import HTTPException, status

from jay_server.config import settings


@lru_cache
def object_storage_client():
    if not all(
        (
            settings.b2_s3_endpoint,
            settings.b2_bucket_name,
            settings.b2_application_key_id,
            settings.b2_application_key,
        )
    ):
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Shared sound storage is not configured",
        )
    endpoint = settings.b2_s3_endpoint
    if "://" not in endpoint:
        endpoint = f"https://{endpoint}"
    host_parts = urlparse(endpoint).hostname.split(".")
    region = host_parts[1] if len(host_parts) > 2 and host_parts[0] == "s3" else None
    return boto3.client(
        "s3",
        endpoint_url=endpoint,
        region_name=region,
        aws_access_key_id=settings.b2_application_key_id,
        aws_secret_access_key=settings.b2_application_key,
        config=Config(signature_version="s3v4"),
    )


def create_sound_upload(object_key: str, sha256: str, byte_length: int) -> dict:
    params = {
        "Bucket": settings.b2_bucket_name,
        "Key": object_key,
        "ContentType": "audio/flac",
        "ContentLength": byte_length,
        "Metadata": {"sha256": sha256},
    }
    return {
        "url": object_storage_client().generate_presigned_url(
            "put_object",
            Params=params,
            ExpiresIn=900,
        ),
        "headers": {
            "Content-Type": "audio/flac",
            "Content-Length": str(byte_length),
            "x-amz-meta-sha256": sha256,
        },
    }


def validate_sound_upload(
    object_key: str,
    sha256: str,
    byte_length: int,
    duration_ms: int,
) -> None:
    try:
        response = object_storage_client().get_object(
            Bucket=settings.b2_bucket_name,
            Key=object_key,
        )
    except ClientError as exception:
        raise HTTPException(status.HTTP_409_CONFLICT, "Shared sound upload is incomplete") from exception
    digest = hashlib.sha256()
    header = bytearray()
    try:
        if (
            response.get("ContentLength") != byte_length
            or response.get("ContentType") != "audio/flac"
            or response.get("Metadata", {}).get("sha256") != sha256
        ):
            raise HTTPException(status.HTTP_409_CONFLICT, "Shared sound upload does not match")
        for chunk in response["Body"].iter_chunks(chunk_size=1024 * 1024):
            digest.update(chunk)
            if len(header) < 42:
                header.extend(chunk[: 42 - len(header)])
        if digest.hexdigest() != sha256:
            raise HTTPException(status.HTTP_409_CONFLICT, "Shared sound upload does not match")
        if (
            len(header) < 42
            or header[:4] != b"fLaC"
            or header[4] & 0x7F != 0
            or int.from_bytes(header[5:8]) != 34
        ):
            raise HTTPException(status.HTTP_409_CONFLICT, "Shared sound is not a valid FLAC file")
        stream_info = int.from_bytes(header[18:26])
        sample_rate = (stream_info >> 44) & 0xFFFFF
        channel_count = ((stream_info >> 41) & 0x7) + 1
        bits_per_sample = ((stream_info >> 36) & 0x1F) + 1
        total_samples = stream_info & ((1 << 36) - 1)
        actual_duration_ms = total_samples * 1000 // sample_rate if sample_rate else 0
        if (
            sample_rate != 48_000
            or channel_count != 1
            or bits_per_sample != 16
            or total_samples == 0
            or actual_duration_ms > 300_000
            or abs(actual_duration_ms - duration_ms) > 1
        ):
            raise HTTPException(status.HTTP_409_CONFLICT, "Shared sound format does not match")
    finally:
        response["Body"].close()


def create_sound_download(object_key: str) -> str:
    return object_storage_client().generate_presigned_url(
        "get_object",
        Params={"Bucket": settings.b2_bucket_name, "Key": object_key},
        ExpiresIn=300,
    )


def delete_sound_object(object_key: str) -> None:
    object_storage_client().delete_object(
        Bucket=settings.b2_bucket_name,
        Key=object_key,
    )
