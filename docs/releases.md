# Releases

Releases come only from the `jay` branch. The head commit message decides whether a push publishes a prerelease, a stable release, or neither, so check that message before pushing.

## Which version to change

Jay and Clock You keep separate version numbers in `gradle.properties`. Change `jayVersionName` and `jayVersionCode` for Jay, increasing both for a Google Play release. `clockYouVersionName` and `clockYouVersionCode` record the Clock You base shown in the app.

Jay uses `com.rispng.jay`, Jay Lite uses `com.rispng.jay.lite`, and debug builds use `com.rispng.jay.debug`. These packages remain the same in stable releases and prereleases.

## Prereleases

A push to `jay` whose head commit message begins with `dev release ` (case insensitive, including the trailing space) runs the prerelease job in `.github/workflows/prerelease.yml`. For example, `dev release test shared alarms` and `DEV RELEASE test shared alarms` both publish a prerelease. Other commit messages skip the prerelease job, and no other branch publishes a prerelease.

The workflow puts the build information together like this:

- version code: `100000 + GITHUB_RUN_NUMBER`;
- version name: `<jayVersionName>-pre.r<five-digit-run-number>.g<seven-character-sha>`;
- release title: the seven-character commit SHA;
- tag: `v<prerelease-version-name>`;
- artifacts: `jay-<prerelease-version-name>.apk`, `jay-<prerelease-version-name>-debug.apk`, and `jay-lite-<prerelease-version-name>.apk`.

The run number is padded so GitHub keeps the tags in chronological order through run 99,999. For example, `0.4.0-pre.r00018.g<sha>` sorts after `0.4.0-pre.r00017.g<sha>`. The `r` also makes this an alphanumeric SemVer identifier, where the leading zeroes are allowed.

The prerelease contains the full and Lite release APKs signed with the production key, plus the debug APK signed with the prerelease key. It is marked as a GitHub prerelease and contains no app bundles.

Prereleases use higher version codes than ordinary stable builds. Installing a full or Lite prerelease can therefore prevent installing a subsequent stable APK over it until the stable version code exceeds the installed code. Use the separate debug package when testing without replacing a stable installation.

## Stable releases

A push to `jay` whose head commit message begins with `Release ` (case insensitive) skips the prerelease job and runs `.github/workflows/release.yml`. The release title is only the first two whitespace-separated words of the commit subject. For example, `Release 0.4.0 add sounds` produces the title `Release 0.4.0`.

The stable tag is `v<jayVersionName>`. Artifact names use `jayVersionName` from `gradle.properties`:

- `jay-<version>.apk`: minified production APK signed with the production key;
- `jay-<version>.aab`: minified production AAB signed with the production key;
- `jay-<version>-debug.apk`: debug APK signed with the prerelease key for debugging;
- `jay-lite-<version>.apk`: minified Lite APK signed with the production key;
- `jay-lite-<version>.aab`: minified Lite AAB signed with the production key.

The full APK and AAB use `com.rispng.jay`; the Lite APK and AAB use `com.rispng.jay.lite`. Add the release changelog to `fastlane/metadata/android/en-US/changelogs/<jayVersionCode>.txt`; the workflow includes it before GitHub's generated release notes. Publishing a stable release removes the prereleases before it and their tags. This keeps older testing builds from crowding the release list.

When `jayVersionName` increases the major or minor version compared with the highest previously published stable version, the workflow also deletes all uploaded assets from previous stable releases. For example, `1.0.3` to `1.1.0` or `1.9.5` to `2.0.0` removes the older APKs, AABs, and any other uploaded files. Previous stable release pages, notes, and tags remain, along with GitHub's automatically generated source-code ZIP and tar.gz downloads. Patch-only changes such as `1.1.0` to `1.1.1`, downgrades, and the first stable release do not trigger this asset cleanup.

The workflow records the existing published releases immediately before publishing. Cleanup runs only after the new release is published successfully and only affects that recorded set, preserving the new release's assets and any releases published afterwards. Draft releases are excluded.

## Signing material

Keep signing stores and passwords in local secure configuration or GitHub Actions secrets. They must not appear in source control, logs, fixtures, screenshots, or generated artifacts.

## Server releases

The API release workflow deploys web and worker instances at one commit after CI, with migrations owned by the web pre-deploy phase. Follow [server deployment](../server/README.md#deployment) for configuration, readiness checks and recovery. Android and server builds must implement compatible API contracts.
