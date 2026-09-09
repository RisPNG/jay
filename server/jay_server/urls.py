from django.urls import path
from drf_spectacular.views import SpectacularAPIView
from rest_framework.permissions import AllowAny

from .social.api.activity import AlarmActivityView, GroupActivityView, OccurrenceView
from .social.api.alarms import AlarmDeliveryView, AlarmListView, AlarmView
from .social.api.groups import GroupListView, GroupSyncView, GroupView, InvitationJoinView, InvitationListView, MemberView, MembershipPreferencesView, MembershipView
from .social.api.identities import CapabilitiesView, EntitlementView, IdentitySyncView, IdentityView, PushSubscriptionView, RegistrationView
from .social.api.sounds import SoundCompleteView, SoundDownloadView, SoundUploadListView, SoundUploadView, SoundView
from .social.api.timers import TimerListView, TimerView
from .views import AppLinksView, HealthLiveView, HealthReadyView, InstallView, documentation, documentation_asset


urlpatterns = [
    path(".well-known/assetlinks.json", AppLinksView.as_view()),
    path("join", InstallView.as_view()),
    path("profile", InstallView.as_view()),
    path("health", HealthLiveView.as_view()),
    path("health/live", HealthLiveView.as_view()),
    path("health/ready", HealthReadyView.as_view()),
    path("openapi.json", SpectacularAPIView.as_view(authentication_classes=[], permission_classes=[AllowAny])),
    path("docs", documentation),
    path("docs/scalar-1.68.0.js", documentation_asset),
    path("docs/init.js", documentation_asset, {"asset_name": "init.js"}),
    path("v1/identities/register", RegistrationView.as_view()),
    path("v1/identity", IdentityView.as_view()),
    path("v1/identity/push-token", PushSubscriptionView.as_view()),
    path("v1/identity/capabilities", CapabilitiesView.as_view()),
    path("v1/identity/play-entitlement", EntitlementView.as_view()),
    path("v1/groups", GroupListView.as_view()),
    path("v1/groups/join", InvitationJoinView.as_view()),
    path("v1/groups/<uuid:group_id>", GroupView.as_view()),
    path("v1/groups/<uuid:group_id>/membership", MembershipView.as_view()),
    path("v1/groups/<uuid:group_id>/notification-settings", MembershipPreferencesView.as_view()),
    path("v1/groups/<uuid:group_id>/members/<str:member_id>", MemberView.as_view()),
    path("v1/groups/<uuid:group_id>/invites", InvitationListView.as_view()),
    path("v1/groups/<uuid:group_id>/activity", GroupActivityView.as_view()),
    path("v1/groups/<uuid:group_id>/sync", GroupSyncView.as_view()),
    path("v1/groups/<uuid:group_id>/timers", TimerListView.as_view()),
    path("v1/groups/<uuid:group_id>/sounds/uploads", SoundUploadListView.as_view()),
    path("v1/alarms", AlarmListView.as_view()),
    path("v1/alarms/<uuid:alarm_id>", AlarmView.as_view()),
    path("v1/alarms/<uuid:alarm_id>/activity", AlarmActivityView.as_view()),
    path("v1/alarms/<uuid:alarm_id>/occurrence", OccurrenceView.as_view()),
    path("v1/alarms/<uuid:alarm_id>/deliveries", AlarmDeliveryView.as_view()),
    path("v1/timers/<uuid:timer_id>", TimerView.as_view()),
    path("v1/sounds/<uuid:sound_id>", SoundView.as_view()),
    path("v1/sounds/<uuid:sound_id>/upload", SoundUploadView.as_view()),
    path("v1/sounds/<uuid:sound_id>/complete", SoundCompleteView.as_view()),
    path("v1/sounds/<uuid:sound_id>/download", SoundDownloadView.as_view()),
    path("v1/sync", IdentitySyncView.as_view()),
]
