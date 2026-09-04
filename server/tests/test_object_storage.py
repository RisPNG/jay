import hashlib

import pytest
from fastapi import HTTPException

from jay_server import object_storage


class StoredBody:
    def __init__(self, content: bytes):
        self.content = content

    def iter_chunks(self, chunk_size: int):
        yield self.content

    def close(self):
        pass


class StoredObject:
    def __init__(self, content: bytes, sha256: str):
        self.content = content
        self.sha256 = sha256

    def head_object(self, **_):
        return {
            "ContentLength": len(self.content),
            "ContentType": "audio/flac",
            "Metadata": {"sha256": self.sha256},
        }

    def get_object(self, **_):
        return {"Body": StoredBody(self.content)}


def flac_header(sample_rate: int = 48_000) -> bytes:
    stream_info = bytearray(34)
    packed = (
        (sample_rate << 44)
        | (0 << 41)
        | (15 << 36)
        | 48_000
    )
    stream_info[10:18] = packed.to_bytes(8)
    return b"fLaC" + bytes((0x80, 0, 0, 34)) + stream_info


def test_uploaded_sound_must_be_canonical_mono_flac(monkeypatch) -> None:
    content = flac_header()
    sha256 = hashlib.sha256(content).hexdigest()
    monkeypatch.setattr(
        object_storage,
        "object_storage_client",
        lambda: StoredObject(content, sha256),
    )

    object_storage.validate_sound_upload("sound", sha256, len(content), 1000)


def test_uploaded_sound_rejects_the_wrong_sample_rate(monkeypatch) -> None:
    content = flac_header(44_100)
    sha256 = hashlib.sha256(content).hexdigest()
    monkeypatch.setattr(
        object_storage,
        "object_storage_client",
        lambda: StoredObject(content, sha256),
    )

    with pytest.raises(HTTPException) as error:
        object_storage.validate_sound_upload("sound", sha256, len(content), 1088)

    assert error.value.status_code == 409
