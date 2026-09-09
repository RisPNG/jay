from django.conf import settings
from django.shortcuts import get_object_or_404
from drf_spectacular.utils import extend_schema
from rest_framework.response import Response
from rest_framework.views import APIView

from ..access import identity_capabilities, require_membership
from ..errors import DomainError
from ..models import DeliveryWork, Group, SharedSound
from ..providers import object_storage_client, signed_sound_upload
from ..synchronization import publish_changes
from ..transactions import mutation
from .representations import SoundRepresentation
from .serializers import SoundUpload


class SoundUploadListView(APIView):
    throttle_scope = "upload"

    @extend_schema(request=SoundUpload, responses={201: SoundRepresentation})
    def post(self, request, group_id):
        serializer = SoundUpload(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data.copy()
        group = get_object_or_404(Group, pk=group_id)
        with mutation(request, data, [group.scope_id]) as receipt:
            require_membership(group, request.user, data["membership_id"], editor=True)
            if not identity_capabilities(request.user)["shared_sound_upload"]:
                raise DomainError("entitlement_required", "A current Play entitlement is required", 403)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            data.pop("membership_id")
            sound, created = SharedSound.objects.get_or_create(pk=data["id"], defaults={
                **data, "group": group, "uploaded_by": request.user,
                "object_key": f"sounds/{data['id']}/verified.flac", "staging_key": f"sounds/{data['id']}/upload.flac",
            })
            if not created:
                if sound.group_id != group.pk or sound.uploaded_by_id != request.user.pk or sound.status != "pending" or sound.sha256 is not None:
                    raise DomainError("sound_exists", "This sound upload has already been defined")
                for name, value in data.items():
                    setattr(sound, name, value)
                sound.save()
            publish_changes(group.scope_id, [("sound", str(sound.pk), "upsert", SoundRepresentation(sound).data, None)], group=group)
            receipt.status, receipt.response = 201, SoundRepresentation(sound).data
        return Response(receipt.response, status=201)


class SoundUploadView(APIView):
    throttle_scope = "upload"

    @extend_schema(responses={200: {"type": "object"}})
    def get(self, request, sound_id):
        sound = get_object_or_404(SharedSound.objects.select_related("group"), pk=sound_id)
        require_membership(sound.group, request.user, editor=True)
        if sound.uploaded_by_id != request.user.pk or not identity_capabilities(request.user)["shared_sound_upload"]:
            raise DomainError("uploader_required", "Only the entitled uploader may upload this sound", 403)
        if sound.status != "pending" or sound.sha256 is None:
            raise DomainError("upload_unavailable", "This upload is not pending")
        return Response(signed_sound_upload(sound))


class SoundCompleteView(APIView):
    @extend_schema(request=None, responses={202: SoundRepresentation})
    def post(self, request, sound_id):
        sound = get_object_or_404(SharedSound.objects.select_related("group"), pk=sound_id)
        with mutation(request, {}, [sound.group.scope_id]) as receipt:
            sound = SharedSound.objects.select_for_update().select_related("group").get(pk=sound_id)
            require_membership(sound.group, request.user, editor=True)
            if sound.uploaded_by_id != request.user.pk or not identity_capabilities(request.user)["shared_sound_upload"]:
                raise DomainError("uploader_required", "Only the entitled uploader may complete this sound", 403)
            if receipt.completed_at:
                return Response(receipt.response, status=receipt.status)
            if sound.status != "pending" or sound.sha256 is None:
                raise DomainError("upload_unavailable", "This upload is not pending")
            sound.status = "verifying"
            sound.save(update_fields=["status"])
            DeliveryWork.objects.create(kind="verify", deduplication_key=f"verify:{sound.pk}", payload={"sound_id": str(sound.pk)})
            publish_changes(sound.group.scope_id, [("sound", str(sound.pk), "upsert", SoundRepresentation(sound).data, None)], group=sound.group)
            receipt.status, receipt.response = 202, SoundRepresentation(sound).data
        return Response(receipt.response, status=202)


class SoundView(APIView):
    @extend_schema(responses=SoundRepresentation)
    def get(self, request, sound_id):
        sound = get_object_or_404(SharedSound.objects.select_related("group"), pk=sound_id)
        require_membership(sound.group, request.user)
        return Response(SoundRepresentation(sound).data)


class SoundDownloadView(APIView):
    @extend_schema(responses={200: {"type": "object", "properties": {"url": {"type": "string", "format": "uri"}, "sha256": {"type": "string"}, "byte_length": {"type": "integer"}}}})
    def get(self, request, sound_id):
        sound = get_object_or_404(SharedSound.objects.select_related("group"), pk=sound_id, status="ready")
        require_membership(sound.group, request.user)
        url = object_storage_client().generate_presigned_url("get_object", Params={"Bucket": settings.B2_BUCKET_NAME, "Key": sound.object_key}, ExpiresIn=300)
        return Response({"url": url, "sha256": sound.sha256, "byte_length": sound.byte_length})
