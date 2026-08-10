import hashlib
import hmac

from fastapi import Header, HTTPException, status

from jay_server.database import transaction


def authenticated_device(
    authorization: str = Header(), x_jay_device_id: str = Header()
) -> dict:
    scheme, separator, token = authorization.partition(" ")
    if separator != " " or scheme.lower() != "bearer":
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid authorization header")

    with transaction() as connection:
        device = connection.execute(
            "SELECT id, name, token_hash FROM devices WHERE id = %s",
            (x_jay_device_id,),
        ).fetchone()

    if device is None or not hmac.compare_digest(
        device["token_hash"], hashlib.sha256(token.encode()).digest()
    ):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid device credentials")
    return {"id": device["id"], "name": device["name"]}

