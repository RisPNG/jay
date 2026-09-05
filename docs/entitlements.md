# Shared sounds and Play access

Shared sounds are the part of Jay where access depends on the server you use. The setting is `SHARED_SOUND_ACCESS`, and there are two choices:

| Value | What it means |
| --- | --- |
| `play` | Uploading and choosing a shared sound requires verified Play access. This is the default. |
| `everyone` | Every authenticated device can upload and choose shared sounds without a purchase or an expiry. Group editing permissions still apply. |

If you run your own server, `everyone` lets you make this feature available to your users. You still need to provide the audio storage, and uploaded audio still goes through validation. See the [server setup guide](../server/README.md#shared-sounds-on-your-own-server) for the environment setting.

## What the rest of the group gets

Play access is only needed for uploading and selecting distributed sounds on a server using `play`. People using the free GitHub APK can still join groups, choose their device's default sound or silence, and receive and play sounds selected by someone with access.

For example, one member can choose an audio file for a shared alarm and everyone in the group can hear it. They do not all need to buy the app for that to work. Changing something unrelated, such as the alarm's label, also keeps the selected sound in place.

Android prepares a selected file or readable system ringtone as mono 48 kHz 16-bit FLAC, with a five-minute limit. It uploads the result directly to private Backblaze B2 storage using a signed request issued by the server. The server checks the uploaded object before allowing it to be selected.

## How the app knows what is available

The app gets the device's effective capabilities through authenticated synchronisation and `/v1/device/capabilities`. It stores the result for that server and identity, so a grant from one server or profile cannot be reused by another.

On an `everyone` server, shared-sound access has no expiry and the app skips Play verification. Debug and prerelease builds get their access through normal synchronisation too; this does not require a special APK.

On a `play` server, production builds refresh Play access immediately and every 24 hours when a connection is available. A successful verification grants access for 48 hours by default, controlled by `PLAY_ENTITLEMENT_LIFETIME_HOURS`. The API reports whether the device can upload through `shared_sound_upload`.

Changing the server setting back to `play` restores the server-side checks immediately. The app learns about it on its next sync or refresh, but an old cached grant cannot get an upload past the server. Sounds already selected remain playable, and unrelated edits preserve them.

Deploy the server before the updated Android client, since the client expects the capability response. This policy does not need a database migration.

## What Play verification checks

The production app requests a Standard Play Integrity token with a request hash tied to its Jay device identity. The authenticated API sends that token to Google and checks:

- the request package is `com.rispng.jay`;
- the request hash matches the authenticated device;
- the request is no more than five minutes old and no more than one minute in the future;
- the app recognition verdict is `PLAY_RECOGNIZED`;
- the app licensing verdict is `LICENSED`;
- the recognised package is `com.rispng.jay`.

If those checks pass, the server stores the entitlement for that device. The APK itself does not contain a separate paid feature flag that grants access.

## Where Firebase fits

Firebase Cloud Messaging lets the app receive prompt background sync notifications. It is separate from Play verification and shared-sound access.

Synchronisation must still work without Firebase: when Jay opens, when the user refreshes, after local group operations, and periodically in the background. Leaving Firebase unconfigured on the server does not, by itself, disable Firebase in an Android build that already includes its configuration.

## Keeping credentials out of the repository

Use local secure configuration, deployment secrets, or GitHub Actions secrets for Firebase and Google Play service-account JSON, Firebase application credentials, and Play Integrity credentials. Do not put them in source control, logs, fixtures, screenshots, or generated artifacts.

The server reads service-account JSON from `FIREBASE_CREDENTIALS_JSON` and `GOOGLE_PLAY_CREDENTIALS_JSON`. If either is missing, the corresponding integration may be unavailable. Do not work around that by embedding credentials in the code.
