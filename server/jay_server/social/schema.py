from drf_spectacular.extensions import OpenApiAuthenticationExtension, OpenApiSerializerExtension
from drf_spectacular.openapi import AutoSchema
from drf_spectacular.plumbing import build_media_type_object
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter


class IdentityAuthenticationScheme(OpenApiAuthenticationExtension):
    target_class = "jay_server.social.authentication.IdentityAuthentication"
    name = ["bearerIdentity", "identityId"]

    def get_security_definition(self, auto_schema):
        return [
            {"type": "http", "scheme": "bearer"},
            {"type": "apiKey", "in": "header", "name": "X-Jay-Identity-ID"},
        ]


class DeletedResourceSchema(OpenApiSerializerExtension):
    target_class = "jay_server.social.api.representations.DeletedResourceRepresentation"

    def map_serializer(self, auto_schema, direction):
        return {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"], "additionalProperties": False}


class JaySchema(AutoSchema):
    def _get_request_body(self, direction="request"):
        if self.method != "DELETE":
            return super()._get_request_body(direction)
        serializer = self.get_request_serializer()
        if serializer is None:
            return None
        schema, required = self._get_request_for_media_type(serializer, direction)
        if schema is None:
            return None
        examples = self._get_examples(serializer, direction, "application/json")
        body = {"content": {"application/json": build_media_type_object(schema, examples)}}
        if required:
            body["required"] = True
        return body

    def get_override_parameters(self):
        parameters = super().get_override_parameters()
        if not self.path.endswith("/identities/register"):
            parameters.append(OpenApiParameter("X-Jay-Installation", OpenApiTypes.STR, OpenApiParameter.HEADER, description="Private installation credential bound to a verified paid Play installation; never part of a profile export."))
        if self.method in {"POST", "PUT", "PATCH", "DELETE"} and not self.path.endswith("/identities/register"):
            parameters.append(OpenApiParameter("Idempotency-Key", OpenApiTypes.UUID, OpenApiParameter.HEADER, required=True, description="Stable UUID for this exact saved operation; reuse on every retry."))
        if self.method == "GET" and self.path.endswith("/sync"):
            parameters.append(OpenApiParameter("cursor", OpenApiTypes.STR, OpenApiParameter.QUERY, description="Opaque cursor from the preceding page or completed checkpoint."))
        if self.method == "GET" and self.path.endswith("/activity"):
            parameters.extend([
                OpenApiParameter("before", OpenApiTypes.STR, OpenApiParameter.QUERY),
                OpenApiParameter("limit", OpenApiTypes.INT, OpenApiParameter.QUERY, description="Page size from 1 through 100; default 50."),
            ])
        return parameters

    def get_response_serializers(self):
        from .api.representations import ErrorRepresentation

        responses = super().get_response_serializers()
        if not isinstance(responses, dict):
            responses = {200: responses}
        return {**{status: ErrorRepresentation for status in [400, 401, 403, 404, 409, 429, 503]}, **responses}
