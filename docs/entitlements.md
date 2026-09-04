# Entitlements

This document records the paid-entitlement behavior implemented by the Android client and server. Do not put credentials or signing material in this repository.

## Design

Firebase Cloud Messaging provides immediate social synchronization notifications. Synchronization must remain functional without Firebase through launch, manual, operation-triggered, and periodic synchronization.

Production release builds enable Play entitlement refresh; debug and prerelease builds do not. A production installation requests a Standard Play Integrity token using a request hash bound to its Jay device identity. The authenticated FastAPI endpoint sends the token to Google and verifies:

- the request package is `com.rispng.jay`;
- the request hash matches the authenticated device;
- the request is no more than five minutes old and no more than one minute in the future;
- the app recognition verdict is `PLAY_RECOGNIZED`;
- the app licensing verdict is `LICENSED`;
- the recognized package is `com.rispng.jay`.

A successful verdict stores a server-side entitlement for the authenticated device. Its default lifetime is 48 hours and is configurable through `PLAY_ENTITLEMENT_LIFETIME_HOURS`. Android refreshes it immediately and every 24 hours while networking is available. The API exposes the resulting capability as `shared_sound_upload`.

The entitlement gates uploading and selecting group-distributed sounds only. GitHub and other free users remain fully interoperable: they may participate in groups, switch shared alarms and timers between their device default sound and silence, and receive and play sounds selected by an entitled editor. Saving an unrelated edit does not replace an existing distributed sound. Android normalizes a selected file or readable system ringtone to mono 48 kHz 16-bit FLAC, capped at five minutes, then uploads it directly to private B2 storage with a server-issued signed request. The server verifies the normalized object before it can be selected.

## Secrets

Firebase service-account JSON, Google Play service-account JSON, Firebase application credentials, and Play Integrity credentials belong only in local secure configuration, deployment secrets, or GitHub Actions secrets. Never add them to source control, logs, fixtures, screenshots, or generated artifacts.

The server reads Firebase and Google Play service-account JSON from `FIREBASE_CREDENTIALS_JSON` and `GOOGLE_PLAY_CREDENTIALS_JSON`. Their absence may disable the corresponding integration but must never be compensated for with embedded credentials.
