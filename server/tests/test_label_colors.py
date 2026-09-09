from datetime import timedelta
from uuid import uuid4

import pytest
from django.utils import timezone

from jay_server.social.models import SharedAlarm, SharedTimer


pytestmark = pytest.mark.django_db(transaction=True)


@pytest.mark.parametrize("kind", ["alarm", "timer"])
@pytest.mark.parametrize("color", [-1, -16777216, -15584170])
def test_label_colors_survive_saves_and_group_sync(client, group, alarm_payload, timer_payload, kind, color):
    payload = dict(alarm_payload if kind == "alarm" else timer_payload, label_color=color)
    collection = "/v1/alarms" if kind == "alarm" else f"/v1/groups/{group.pk}/timers"
    created = client.post(collection, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert created.status_code == 201, created.data
    assert created.data["label_color"] == color
    model = SharedAlarm if kind == "alarm" else SharedTimer
    assert model.objects.get(pk=payload["id"]).label_color == color

    payload.pop("id")
    payload.pop("group_id", None)
    payload["saved_at"] = (timezone.now() - timedelta(seconds=1)).isoformat()
    payload["label_color"] = -1193047
    endpoint = f"/v1/{kind}s/{created.data['id']}"
    changed = client.put(endpoint, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert changed.status_code == 200, changed.data
    assert changed.data["label_color"] == -1193047

    payload.pop("label_color")
    payload["saved_at"] = timezone.now().isoformat()
    legacy = client.put(endpoint, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert legacy.status_code == 200, legacy.data
    assert legacy.data["label_color"] == -1193047
    snapshot = client.get(f"/v1/groups/{group.pk}/sync")
    assert snapshot.status_code == 200, snapshot.data
    item = next(item for item in snapshot.data["items"] if item["kind"] == kind and item["key"] == created.data["id"])
    assert item["data"]["label_color"] == -1193047


@pytest.mark.parametrize("kind", ["alarm", "timer"])
def test_legacy_items_default_to_snow(client, group, alarm_payload, timer_payload, kind):
    payload = alarm_payload if kind == "alarm" else timer_payload
    endpoint = "/v1/alarms" if kind == "alarm" else f"/v1/groups/{group.pk}/timers"
    response = client.post(endpoint, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 201, response.data
    assert response.data["label_color"] == -1


@pytest.mark.parametrize("kind", ["alarm", "timer"])
@pytest.mark.parametrize("color", [0, -16777217, None])
def test_label_colors_reject_nonopaque_or_missing_values(client, group, alarm_payload, timer_payload, kind, color):
    payload = dict(alarm_payload if kind == "alarm" else timer_payload, label_color=color)
    endpoint = "/v1/alarms" if kind == "alarm" else f"/v1/groups/{group.pk}/timers"
    response = client.post(endpoint, payload, format="json", headers={"Idempotency-Key": str(uuid4())})
    assert response.status_code == 400, response.data
