from django.utils import timezone

from .api.representations import MembershipAccessRepresentation, MembershipRepresentation
from .errors import DomainError
from .models import AlarmOccurrence, DeliveryWork, GroupMembership
from .synchronization import publish_changes


def publish_membership(member, actor, action):
    removed = action == "delete"
    publish_changes(member.identity.scope_id, [("membership", str(member.id), action, {"id": str(member.id), "group_id": str(member.group_id)} if removed else MembershipAccessRepresentation(member).data, None)])
    publish_changes(
        member.group.scope_id,
        [("member", str(member.id), action, {"id": str(member.id)} if removed else MembershipRepresentation(member).data, None)],
        group=member.group, actor=actor,
        activity={"entity_type": "membership", "entity_id": str(member.id), "action": "left" if removed else "updated", "subject": member.identity, "subject_label": member.identity.name},
    )


def remove_membership(member, actor):
    active = GroupMembership.objects.filter(group=member.group, removed_at=None)
    if member.role == "leader" and active.count() > 1 and not active.exclude(pk=member.pk).filter(role="leader").exists():
        raise DomainError("last_leader", "The group must retain a leader")
    member.removed_at = timezone.now()
    member.save(update_fields=["removed_at"])
    AlarmOccurrence.objects.filter(group=member.group, identity=member.identity, state="pending").update(state="canceled", resolved_at=timezone.now())
    publish_membership(member, actor, "delete")
    if not active.exists():
        member.group.deleted_at = timezone.now()
        member.group.save(update_fields=["deleted_at"])
        DeliveryWork.objects.get_or_create(deduplication_key=f"delete_group:{member.group_id}", defaults={"kind": "delete_group", "payload": {"group_id": str(member.group_id)}})
