# Releases

Releases come from `jay`. The head commit message decides whether a push publishes a prerelease or a stable release, so check that message before pushing.

## Which version to change

Jay and Clock You keep separate version numbers in `gradle.properties`. Change `jayVersionName` and `jayVersionCode` for Jay, increasing both for a Google Play release. `clockYouVersionName` and `clockYouVersionCode` record the Clock You base shown in the app.

The production application ID is `com.rispng.jay`. Debug and prerelease builds add the `.debug` suffix and use `com.rispng.jay.debug`.

## Prereleases

Every push to `jay` whose head commit message does not begin exactly with `Release ` runs `.github/workflows/prerelease.yml`. No other branch publishes a prerelease.

The workflow puts the build information together like this:

- version code: `100000 + GITHUB_RUN_NUMBER`;
- version name: `<jayVersionName>-pre.r<five-digit-run-number>.g<seven-character-sha>`;
- release title: the seven-character commit SHA;
- tag: `v<prerelease-version-name>`;
- artifact: `jay-<prerelease-version-name>.apk`.

The run number is padded so GitHub keeps the tags in chronological order through run 99,999. For example, `0.4.0-pre.r00018.g<sha>` sorts after `0.4.0-pre.r00017.g<sha>`. The `r` also makes this an alphanumeric SemVer identifier, where the leading zeroes are allowed.

The prerelease contains only the debug APK, signed with the prerelease key, and is marked as a GitHub prerelease. Bleeding Edge or `BE` builds no longer exist. Older unpadded prereleases may remain out of order; they can be removed without affecting the new sequence.

## Stable releases

A push to `jay` whose head commit message begins exactly with `Release ` skips the prerelease job and runs `.github/workflows/release.yml`. The release title is only the first two whitespace-separated words of the commit subject. For example, `Release 0.4.0 add sounds` produces the title `Release 0.4.0`.

The stable tag is `v<jayVersionName>`. Artifact names use `jayVersionName` from `gradle.properties`:

- `jay-<version>.apk`: minified production APK signed with the production key;
- `jay-<version>.aab`: minified production AAB signed with the production key;
- `jay-<version>-debug.apk`: debug APK signed with the prerelease key for debugging.

The production APK and AAB both use `com.rispng.jay` and the same access checks at runtime. Shared-sound access comes from the server's policy and the device's entitlement, rather than a separate paid APK. See [Shared sounds and Play access](entitlements.md).

For invitation and profile links to open in Jay, the server's `ANDROID_APP_LINKS` setting needs the installed app's package name and SHA-256 signing certificate fingerprint. Use the Google Play app signing certificate for Play installations. If direct-download APKs use a different certificate, include that one too. Prereleases need their own `com.rispng.jay.debug` entry.

The [server guide](../server/README.md#shared-links) covers setup and device checks. The Play Store fallback only works once the listing is available to the recipient. After installing, they need to tap the original link again.

Publishing a stable release removes the prereleases before it and their tags. This keeps older testing builds from crowding the release list.

`main`, `main-canary`, and feature branches do not publish releases.

## Signing material

Keep signing stores and passwords in local secure configuration or GitHub Actions secrets. They must not appear in source control, logs, fixtures, screenshots, or generated artifacts.
