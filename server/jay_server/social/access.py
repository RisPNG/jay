from django.conf import settings
from django.utils import timezone

from .errors import DomainError
from .models import Group, GroupMembership, SharedSoundEntitlement, SharedSound, SoundMode


def require_membership(group, identity, generation=None, leader=False, editor=False):
    group = Group.objects.get(pk=group.pk)
    if group.deleted_at is not None:
        raise DomainError("not_found", "Group not found", 404)
    member = GroupMembership.objects.select_related("identity", "group").filter(
        group=group, identity=identity, removed_at=None,
    ).first()
    if member is None:
        raise DomainError("not_found", "Group not found", 404)
    if generation is not None and member.pk != generation:
        raise DomainError("membership_changed", "This operation belongs to a previous membership", 403)
    if leader and member.role != "leader":
        raise DomainError("leader_required", "A group leader is required", 403)
    if editor and group.alarm_permission == "leaders" and member.role != "leader":
        raise DomainError("editor_required", "Only group leaders may edit shared items", 403)
    return member


def identity_capabilities(identity):
    if settings.SHARED_SOUND_ACCESS == "everyone":
        return {"shared_sound_upload": True, "requires_play_entitlement": False, "expires_at": None}
    entitlement = SharedSoundEntitlement.objects.filter(identity=identity).first()
    return {
        "shared_sound_upload": entitlement is not None and (entitlement.expires_at is None or entitlement.expires_at > timezone.now()),
        "requires_play_entitlement": entitlement is None or entitlement.source == SharedSoundEntitlement.Source.PLAY,
        "expires_at": entitlement.expires_at.isoformat() if entitlement and entitlement.expires_at else None,
    }


def select_shared_sound(group, identity, selection, current_sound_id=None):
    if selection["mode"] != SoundMode.SHARED:
        return None
    if selection["sound_id"] != current_sound_id and not identity_capabilities(identity)["shared_sound_upload"]:
        raise DomainError("entitlement_required", "A current Play entitlement is required", 403)
    sound = SharedSound.objects.filter(pk=selection["sound_id"], group=group).first()
    if sound is None and selection.get("title"):
        sound = SharedSound.objects.create(
            id=selection["sound_id"], group=group, uploaded_by=identity, title=selection["title"],
            object_key=f"sounds/{selection['sound_id']}/verified.flac",
            staging_key=f"sounds/{selection['sound_id']}/upload.flac",
        )
    if sound is None or sound.status == SharedSound.State.DELETING:
        raise DomainError("not_found", "Shared sound not found", 404)
    return sound
