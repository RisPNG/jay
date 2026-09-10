# Jay privacy policy

Last updated: 10 September 2026

Jay is developed and its default service at `jay.poppybit.com` is operated by **RIS PENG**. For privacy questions or requests, contact **[ris@poppybit.com](mailto:ris@poppybit.com)**.

This policy explains the Android app and the default hosted service. If you choose another server in Settings, that server's operator is responsible for its hosting, access, logging and retention practices.

## Information Jay uses

Jay does not ask you to register with an email address or password. It generates a profile with a random secret and an identifier. The secret stays on your device unless you export your profile; the app derives a server-specific authentication token from it. The server stores a hash of that token. A profile is not anonymous: it connects your membership and actions across the devices on which you import it.

| Information | Purpose |
| --- | --- |
| Generated profile identifier, profile name, authentication-token hash, time zone and last-seen time | Authenticate requests, identify group members, schedule shared alarms in the correct time zone and maintain inactive profiles. You can use the generated name instead of your real name. |
| Group names, memberships, roles, invitation records and notification preferences | Manage group access, invitations, editing permissions and notifications. |
| Shared alarm and timer labels, schedules, settings, changes, delivery acknowledgements and responses | Synchronize group clocks and show the activity, dismissal, snooze and missed-alarm behavior that members use. |
| Audio you select for a shared alarm or timer, its title and file metadata | Prepare, store, verify and deliver the shared sound to group members. The app converts selected audio to FLAC before uploading it. |
| Push registration tokens and Firebase installation identifiers | Deliver background synchronization notifications to the correct installation. |
| Google Play integrity tokens and license-verification results | Verify eligibility to upload shared sounds. Jay stores the entitlement's source, grant time and expiry, where applicable. Jay does not receive your payment-card details through this verification. |
| Network-request and operational information, such as IP addresses, request paths, times, response statuses and errors where logged by the service or provider | Deliver requests, diagnose failures, protect the service and operate its infrastructure. |

Personal alarms, saved personal timers, world-clock choices and app settings are stored locally rather than synchronized as group content. Android backup and device transfer can copy app data according to your device settings. Jay excludes the profile-secret preferences from those mechanisms; exporting a profile is a separate action you control.

Jay does not include advertising, Firebase Analytics or Crashlytics. Its network traffic and operational records are still data processing; the absence of advertising or an analytics SDK does not mean that the app sends no data.

## Who can receive information

Other members can see the names, shared content, activity and responses relevant to their group. Exported profile links let their holder act as that profile. Invitation links let their holder join the relevant group while the invitation remains valid. Keep those links private.

The default service uses these providers:

- **Render:** hosts the API, background workers and PostgreSQL database in Singapore, including operational logs and database recovery backups. See [Render's privacy policy](https://render.com/privacy).
- **Backblaze B2:** stores and delivers shared audio using the configured US East storage endpoint. Audio transfers go directly between devices and storage using temporary signed URLs. See [Backblaze's privacy policy](https://www.backblaze.com/company/privacy).
- **Google Firebase Cloud Messaging:** processes installation identifiers and push tokens to deliver synchronization notifications. The server sends a synchronization signal and scope identifier in the push message; the app retrieves group updates separately. See [Firebase privacy and security](https://firebase.google.com/support/privacy).
- **Google Play Integrity:** evaluates app authenticity and licensing so the server can verify shared-sound access. See [Play Integrity](https://developer.android.com/google/play/integrity/overview) and [Google's privacy policy](https://policies.google.com/privacy).

These services may process information outside your country. The server operator and service providers can process the information needed to run the service. Shared content is not end-to-end encrypted.

## Security

The default service uses HTTPS. Server requests are authenticated, and group access and editing permissions are checked before access to shared resources. Audio is held in private storage and accessed through temporary signed URLs. Access is still possible for anyone who obtains a valid profile credential or an unexpired signed URL, so do not publish those links.

## Retention and deletion

Use **Settings → Jay social → Reset identity** to retire the current profile and start with a new one. This requires a connection to the selected server. To request deletion without using the app, follow [Delete your Jay profile and data](delete-profile.md).

Retirement immediately disables the old profile's access. Background cleanup removes its memberships. If it was a group's only leader, that group is deleted even if other members remain, including its shared alarms, timers and stored sounds. Shared content is preserved only in groups that still have another leader. Stored-file deletion and backup expiry follow the cleanup rules below.

The default service uses the following retention periods and cleanup rules:

| Data | Retention or cleanup |
| --- | --- |
| Active profiles and shared group content | Kept while needed to provide the service. Profiles inactive for 120 days are retired automatically. Profiles with operator-granted sound access, such as dedicated app-review profiles, are exempt from automatic inactivity retirement; explicit deletion still applies. |
| Retired profile details and associated personal history | Cleaned up after the 30-day synchronization retention period and completion of group-removal work. A minimal retired-profile record remains to prevent old credentials and offline requests from recreating the deleted profile. |
| Deleted alarms, timers and groups, and obsolete synchronization records | Eligible for cleanup after 30 days. Group cleanup completes only after memberships have been removed. |
| Failed, pending or unused shared audio | Pending uploads expire after 24 hours. Unused sounds become eligible for cleanup after 24 hours; referenced group sounds remain available while in use. |
| Old or deleted audio-file versions in Backblaze | The bucket's lifecycle rule makes noncurrent versions eligible for deletion after one day. Provider lifecycle processing is asynchronous. |
| Render application logs | Available in the dashboard for seven days under the current hosting plan. |
| Database recovery backups | The current point-in-time recovery window is three days. Render retains logical exports for seven days if exports are created; downloading a separate copy creates an additional copy that needs its own deletion. |

Cleanup runs in background jobs, so eligibility times are not guarantees of deletion at an exact instant. Copies other members have downloaded or independently backed up may remain on their devices. Resetting a profile does not erase your personal clock data, remove the installed app, or delete all Android backups.

Firebase manages installation identifiers separately from Jay's profile database. Resetting a Jay profile does not delete its Firebase installation identifier: the app continues as a new profile on the same installation. Google's Firebase policy describes its own processing and retention, including removal after an installation-deletion request. Changing to a self-hosted server does not itself disable Firebase in an app build that includes it.

## Your choices and requests

You can change your profile name and server in Settings, leave groups, control available notification preferences, and manage the content you have permission to edit. Do not put sensitive information into shared labels or sounds unless you intend the group to receive it.

For access, correction or deletion requests, email [ris@poppybit.com](mailto:ris@poppybit.com). We use the information you provide to handle the request and may need to verify which profile belongs to you before acting. Do not post profile links or authentication information in public GitHub issues. A request concerning another operator's server must be directed to that operator.

This policy will be updated when Jay's data practices change. The date above identifies the current version.
