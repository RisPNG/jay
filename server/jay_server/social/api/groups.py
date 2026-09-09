import hashlib
import hmac
import secrets
from datetime import timedelta
from urllib.parse import urlencode

from django.conf import settings
from django.shortcuts import get_object_or_404
from django.utils import timezone
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import require_membership
from ..errors import DomainError
from ..groups import publish_membership, remove_membership
from ..models import DeliveryWork, Group, GroupInvitation, GroupMembership, Identity, SharedAlarm, SyncScope
from ..synchronization import publish_changes, synchronize_scope
from ..transactions import accept_saved_state, mutation
from .representations import ScopeSyncRepresentation, GroupRepresentation, MembershipAccessRepresentation, MembershipRepresentation
from .serializers import MembershipCommand, GroupCreate, GroupUpdate, InvitationCreate, InvitationJoin, MembershipPreferences, RoleUpdate


class GroupListView(APIView):
    @extend_schema(request=GroupCreate, responses={201: GroupRepresentation})
    def post(self, request):
        serializer = GroupCreate(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data.copy()
        with mutation(request, data, [request.user.scope_id]) as receipt:
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            membership_id = data.pop("membership_id")
            group = Group.objects.create(**data, scope=SyncScope.objects.create(id=data["id"]), created_by=request.user, save_id=receipt.operation_id)
            member = GroupMembership.objects.create(id=membership_id, group=group, identity=request.user, role="leader", saved_at=data["saved_at"], save_id=receipt.operation_id, preferences_saved_at=data["saved_at"], preferences_save_id=receipt.operation_id)
            publish_changes(group.scope_id, [("group", str(group.id), "upsert", GroupRepresentation(group).data, None)], group=group, actor=request.user, activity={"entity_type": "group", "entity_id": str(group.id), "action": "created"})
            publish_membership(member, request.user, "upsert")
            receipt.status, receipt.response = 201, GroupRepresentation(group).data
        return Response(receipt.response, status=201)


class GroupView(APIView):
    @extend_schema(request=GroupUpdate, responses=GroupRepresentation)
    def put(self, request, group_id):
        serializer = GroupUpdate(data=request.data)
        serializer.is_valid(raise_exception=True)
        group = get_object_or_404(Group, pk=group_id)
        data = serializer.validated_data.copy()
        with mutation(request, data, [group.scope_id]) as receipt:
            group = Group.objects.select_for_update().get(pk=group_id)
            require_membership(group, request.user, data["membership_id"], leader=True)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            accept_saved_state(group, data.pop("saved_at"), receipt.operation_id, GroupRepresentation(group).data)
            data.pop("membership_id")
            reschedule = any(getattr(group, name) != data[name] for name in ["alarm_time_basis", "alarm_time_zone", "shared_answers"])
            for name, value in data.items():
                setattr(group, name, value)
            group.save()
            if reschedule:
                from django.db.models import F
                SharedAlarm.objects.filter(group=group, deleted_at=None).update(revision=F("revision") + 1, inactive_cycle_streak=0, last_evaluated_cycle_date=None)
                DeliveryWork.objects.create(kind="reschedule", deduplication_key=f"reschedule:group:{receipt.operation_id}", payload={"group_id": str(group.id)})
            publish_changes(group.scope_id, [("group", str(group.id), "upsert", GroupRepresentation(group).data, None)], group=group, actor=request.user, activity={"entity_type": "group", "entity_id": str(group.id), "action": "updated"})
            receipt.status, receipt.response = 200, GroupRepresentation(group).data
        return Response(receipt.response)

    @extend_schema(request=MembershipCommand, responses={204: None})
    def delete(self, request, group_id):
        serializer = MembershipCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        group = get_object_or_404(Group, pk=group_id)
        with mutation(request, data, [group.scope_id]) as receipt:
            if receipt.completed_at:
                return Response(status=receipt.status)
            group = Group.objects.select_for_update().get(pk=group_id)
            require_membership(group, request.user, data["membership_id"], leader=True)
            group.deleted_at = timezone.now()
            group.save(update_fields=["deleted_at"])
            publish_changes(group.scope_id, [("group", str(group.id), "delete", {"id": str(group.id)}, None)], group=group, actor=request.user)
            DeliveryWork.objects.get_or_create(deduplication_key=f"delete_group:{group.id}", defaults={"kind": "delete_group", "payload": {"group_id": str(group.id)}})
            receipt.status, receipt.response = 204, None
        return Response(status=204)


class MembershipView(APIView):
    @extend_schema(request=MembershipCommand, responses={204: None})
    def delete(self, request, group_id):
        serializer = MembershipCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        group = get_object_or_404(Group, pk=group_id)
        with mutation(request, data, [group.scope_id, request.user.scope_id]) as receipt:
            if receipt.completed_at:
                return Response(status=receipt.status)
            group = Group.objects.select_for_update().get(pk=group_id)
            member = require_membership(group, request.user, data["membership_id"])
            remove_membership(member, request.user)
            receipt.status, receipt.response = 204, None
        return Response(status=204)


class MemberView(APIView):
    @extend_schema(request=RoleUpdate, responses=MembershipRepresentation)
    def patch(self, request, group_id, member_id):
        serializer = RoleUpdate(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        group = get_object_or_404(Group, pk=group_id)
        member = get_object_or_404(GroupMembership.objects.select_related("identity", "group"), group=group, identity_id=member_id, removed_at=None)
        with mutation(request, data, [group.scope_id, member.identity.scope_id], [member.identity_id]) as receipt:
            group = Group.objects.select_for_update().get(pk=group_id)
            require_membership(group, request.user, data["membership_id"], leader=True)
            member = GroupMembership.objects.select_related("identity", "group").get(pk=member.pk)
            if member.removed_at:
                raise DomainError("not_found", "Member not found", 404)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            accept_saved_state(member, data["saved_at"], receipt.operation_id, MembershipRepresentation(member).data)
            if data["role"] != "leader" and member.role == "leader" and not GroupMembership.objects.filter(group=group, role="leader", removed_at=None).exclude(pk=member.pk).exists():
                raise DomainError("last_leader", "The group must retain a leader")
            member.role = data["role"]
            member.save()
            publish_membership(member, request.user, "upsert")
            receipt.status, receipt.response = 200, MembershipRepresentation(member).data
        return Response(receipt.response)

    @extend_schema(request=MembershipCommand, responses={204: None})
    def delete(self, request, group_id, member_id):
        serializer = MembershipCommand(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        group = get_object_or_404(Group, pk=group_id)
        target = get_object_or_404(Identity, pk=member_id)
        with mutation(request, data, [group.scope_id, target.scope_id], [target.pk]) as receipt:
            if receipt.completed_at:
                return Response(status=receipt.status)
            group = Group.objects.select_for_update().get(pk=group_id)
            require_membership(group, request.user, data["membership_id"], leader=True)
            if target.pk == request.user.pk:
                raise DomainError("use_leave", "Use leave to remove your own membership", 400)
            member = get_object_or_404(GroupMembership.objects.select_related("identity", "group"), group=group, identity=target, removed_at=None)
            remove_membership(member, request.user)
            receipt.status, receipt.response = 204, None
        return Response(status=204)


class MembershipPreferencesView(APIView):
    @extend_schema(request=MembershipPreferences, responses=MembershipAccessRepresentation)
    def put(self, request, group_id):
        serializer = MembershipPreferences(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        group = get_object_or_404(Group, pk=group_id)
        with mutation(request, data, [group.scope_id, request.user.scope_id]) as receipt:
            member = require_membership(group, request.user, data["membership_id"])
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            if (data["saved_at"], receipt.operation_id.int) <= (member.preferences_saved_at, member.preferences_save_id.int):
                raise DomainError("superseded", "Newer preferences are available", current=MembershipAccessRepresentation(member).data)
            member.preferences_saved_at = data["saved_at"]
            member.preferences_save_id = receipt.operation_id
            member.notify_membership = data["notify_membership"]
            member.notify_administrative = data["notify_administrative"]
            member.save()
            receipt.status, receipt.response = 200, MembershipAccessRepresentation(member).data
            publish_changes(request.user.scope_id, [("membership", str(member.id), "upsert", receipt.response, None)])
        return Response(receipt.response)


class InvitationListView(APIView):
    @extend_schema(request=InvitationCreate, responses={201: {"type": "object"}})
    def post(self, request, group_id):
        serializer = InvitationCreate(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        group = get_object_or_404(Group, pk=group_id)
        with mutation(request, data, [group.scope_id]) as receipt:
            require_membership(group, request.user, data["membership_id"])
            token = hmac.new(settings.SECRET_KEY.encode(), f"invite:{request.user.pk}:{data['id']}".encode(), hashlib.sha256).hexdigest()
            if receipt.completed_at:
                response = {**receipt.response, "token": token, "url": f"https://jay.poppybit.com/join?{urlencode({'token': token, 'server': settings.PUBLIC_URL})}"}
                return Response(response, status=receipt.status)
            invite = GroupInvitation.objects.create(id=data["id"], group=group, created_by=request.user, token_hash=hashlib.sha256(token.encode()).digest(), expires_at=timezone.now() + timedelta(hours=data.get("expires_in_hours", settings.INVITE_LIFETIME_HOURS)))
            receipt.status, receipt.response = 201, {"id": str(invite.pk), "expires_at": invite.expires_at.isoformat()}
        return Response({**receipt.response, "token": token, "url": f"https://jay.poppybit.com/join?{urlencode({'token': token, 'server': settings.PUBLIC_URL})}"}, status=201)


class InvitationJoinView(APIView):
    @extend_schema(request=InvitationJoin, responses={200: MembershipAccessRepresentation})
    def post(self, request):
        serializer = InvitationJoin(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        invite = get_object_or_404(GroupInvitation.objects.select_related("group"), token_hash=hashlib.sha256(data["token"].encode()).digest())
        with mutation(request, data, [invite.group.scope_id, request.user.scope_id]) as receipt:
            if receipt.completed_at:
                require_membership(invite.group, request.user)
                return Response(receipt.response, status=receipt.status)
            group = Group.objects.select_for_update().get(pk=invite.group_id)
            invite = GroupInvitation.objects.select_for_update().get(pk=invite.pk)
            if group.deleted_at or invite.expires_at <= timezone.now() or invite.consumed_at:
                raise DomainError("invite_unavailable", "This invitation is expired or already used")
            member = GroupMembership.objects.select_related("identity", "group").filter(group=group, identity=request.user, removed_at=None).first()
            if member is None:
                member = GroupMembership.objects.create(group=group, identity=request.user)
                publish_membership(member, request.user, "upsert")
                DeliveryWork.objects.create(kind="reschedule", deduplication_key=f"reschedule:membership:{member.pk}", payload={"group_id": str(group.pk), "identity_id": request.user.pk})
            invite.consumed_at, invite.consumed_by = timezone.now(), request.user
            invite.save(update_fields=["consumed_at", "consumed_by"])
            receipt.status, receipt.response = 200, MembershipAccessRepresentation(member).data
        return Response(receipt.response)


class GroupSyncView(APIView):
    @extend_schema(responses=ScopeSyncRepresentation)
    def get(self, request, group_id):
        group = get_object_or_404(Group, pk=group_id)
        member = require_membership(group, request.user)
        return Response(synchronize_scope(SyncScope.objects.get(pk=group.scope_id), request.user, member.pk, request.query_params.get("cursor")))
