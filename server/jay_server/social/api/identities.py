import hashlib
import hmac
from datetime import UTC, datetime, timedelta

from django.conf import settings
from django.db import IntegrityError, transaction
from django.utils import timezone
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import identity_capabilities
from ..errors import DomainError
from ..models import DeliveryWork, GroupMembership, Identity, PlayEntitlement, PushSubscription, SyncScope
from ..synchronization import publish_changes, synchronize_scope
from ..transactions import accept_saved_state, mutation
from .representations import ScopeSyncRepresentation, IdentityRepresentation, MembershipRepresentation
from .serializers import EntitlementRequest, IdentityRegistration, IdentityUpdate, PushTokenUpdate


class RegistrationView(APIView):
    authentication_classes = []
    permission_classes = []
    throttle_scope = "registration"

    @extend_schema(request=IdentityRegistration, responses={201: IdentityRepresentation})
    def post(self, request):
        serializer = IdentityRegistration(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        digest = hashlib.sha256(data["token"].encode()).digest()
        with transaction.atomic():
            identity = Identity.objects.select_for_update().filter(pk=data["id"]).first()
            if identity is None:
                try:
                    with transaction.atomic():
                        scope = SyncScope.objects.create()
                        identity = Identity.objects.create(id=data["id"], name=data["name"], time_zone=data["time_zone"], token_hash=digest, scope=scope, saved_at=datetime(1970, 1, 1, tzinfo=UTC))
                except IntegrityError:
                    identity = Identity.objects.select_for_update().get(pk=data["id"])
                else:
                    publish_changes(scope.id, [("identity", identity.pk, "upsert", IdentityRepresentation(identity).data, None)])
            if identity.retired_at is not None:
                raise DomainError("identity_retired", "This identity was removed; generate a new profile")
            if not hmac.compare_digest(bytes(identity.token_hash), digest):
                raise DomainError("identity_registered", "This identity is already registered")
            if identity.time_zone != data["time_zone"]:
                identity.time_zone = data["time_zone"]
                identity.save(update_fields=["time_zone"])
                DeliveryWork.objects.get_or_create(
                    deduplication_key=f"reschedule:identity:{identity.pk}:{data['time_zone']}",
                    defaults={"kind": "reschedule", "payload": {"identity_id": identity.pk}},
                )
        return Response(IdentityRepresentation(identity).data, status=201)


class IdentityView(APIView):
    @extend_schema(request=IdentityUpdate, responses=IdentityRepresentation)
    def patch(self, request):
        serializer = IdentityUpdate(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        with mutation(request, data, [request.user.scope_id], exclusive_identity=True, include_membership_scopes=True) as receipt:
            identity = Identity.objects.get(pk=request.user.pk)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            accept_saved_state(identity, data["saved_at"], receipt.operation_id, IdentityRepresentation(identity).data)
            identity.name = data["name"]
            identity.save()
            publish_changes(identity.scope_id, [("identity", identity.pk, "upsert", IdentityRepresentation(identity).data, None)])
            for member in GroupMembership.objects.select_related("group").filter(identity=identity, removed_at=None, group__deleted_at=None):
                member.identity = identity
                publish_changes(member.group.scope_id, [("member", str(member.pk), "upsert", MembershipRepresentation(member).data, None)], group=member.group, actor=identity)
            receipt.status, receipt.response = 200, IdentityRepresentation(identity).data
        return Response(receipt.response)

    @extend_schema(request=None, responses={204: None})
    def delete(self, request):
        with mutation(request, {}, [request.user.scope_id], exclusive_identity=True) as receipt:
            if receipt.completed_at:
                return Response(status=receipt.status)
            Identity.objects.filter(pk=request.user.pk).update(retired_at=timezone.now())
            PushSubscription.objects.filter(identity=request.user).delete()
            DeliveryWork.objects.create(kind="retire", deduplication_key=f"retire:{request.user.pk}", payload={"identity_id": request.user.pk})
            receipt.status, receipt.response = 204, None
        return Response(status=204)


class PushSubscriptionView(APIView):
    @extend_schema(request=PushTokenUpdate, responses={204: None})
    def put(self, request):
        serializer = PushTokenUpdate(data=request.data)
        serializer.is_valid(raise_exception=True)
        with mutation(request, serializer.validated_data) as receipt:
            if not receipt.completed_at:
                PushSubscription.objects.update_or_create(token=serializer.validated_data["token"], defaults={"identity": request.user, "updated_at": timezone.now()})
                receipt.status, receipt.response = 204, None
        return Response(status=204)


class CapabilitiesView(APIView):
    @extend_schema(responses={200: {"type": "object", "properties": {"shared_sound_upload": {"type": "boolean"}, "requires_play_entitlement": {"type": "boolean"}, "expires_at": {"type": "string", "format": "date-time", "nullable": True}}, "required": ["shared_sound_upload", "requires_play_entitlement", "expires_at"]}})
    def get(self, request):
        return Response(identity_capabilities(request.user))


class EntitlementView(APIView):
    @extend_schema(request=EntitlementRequest, responses={200: {"type": "object"}})
    def post(self, request):
        from ..providers import verify_play_entitlement

        serializer = EntitlementRequest(data=request.data)
        serializer.is_valid(raise_exception=True)
        if settings.SHARED_SOUND_ACCESS == "everyone":
            return Response(identity_capabilities(request.user))
        with mutation(request, serializer.validated_data, reserve=True) as receipt:
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
        licensed = verify_play_entitlement(serializer.validated_data["integrity_token"], request.user.pk)
        with mutation(request, serializer.validated_data, [request.user.scope_id]) as receipt:
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            if licensed:
                PlayEntitlement.objects.update_or_create(identity=request.user, defaults={"verified_at": timezone.now(), "expires_at": timezone.now() + timedelta(hours=settings.PLAY_ENTITLEMENT_LIFETIME_HOURS)})
            else:
                PlayEntitlement.objects.filter(identity=request.user).delete()
            receipt.status, receipt.response = 200, identity_capabilities(request.user)
            publish_changes(request.user.scope_id, [("capabilities", request.user.pk, "upsert", receipt.response, None)])
        return Response(receipt.response)


class IdentitySyncView(APIView):
    @extend_schema(responses=ScopeSyncRepresentation)
    def get(self, request):
        return Response(synchronize_scope(SyncScope.objects.get(pk=request.user.scope_id), request.user, None, request.query_params.get("cursor")))
