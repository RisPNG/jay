# Releases

This document records the release behavior implemented by the build and GitHub Actions workflows. Do not put credentials or signing material in this repository.

## Version ownership

Jay's release version is stored in `gradle.properties` as `jayVersionName` and `jayVersionCode`. Increase both for a Google Play release. Clock You's displayed version remains separately recorded as `clockYouVersionName` and `clockYouVersionCode`.

The production application ID is `com.rispng.jay`. Debug and prerelease builds add the `.debug` suffix and use `com.rispng.jay.debug`.

## Prereleases

Every push to `jay` whose head commit message does not begin exactly with `Release ` runs `.github/workflows/prerelease.yml`. No other branch publishes a prerelease.

The workflow derives:

- version code: `100000 + GITHUB_RUN_NUMBER`;
- version name: `<jayVersionName>-pre.r<five-digit-run-number>.g<seven-character-sha>`;
- release title: the seven-character commit SHA;
- tag: `v<prerelease-version-name>`;
- artifact: `jay-<prerelease-version-name>.apk`.

The `r` makes the zero-padded run component an alphanumeric SemVer identifier, while its fixed width keeps GitHub's tag-version ordering chronological through run 99,999. For example, run 18 produces `0.4.0-pre.r00018.g<sha>` and sorts after run 17's `0.4.0-pre.r00017.g<sha>`.

The prerelease contains only the debug APK, signed with the prerelease key, and is marked as a GitHub prerelease. Bleeding Edge or `BE` builds no longer exist. Older unpadded prereleases may remain out of order; they can be removed without affecting the new sequence.

## Stable releases

A push to `jay` whose head commit message begins exactly with `Release ` skips the prerelease job and runs `.github/workflows/release.yml`. The release title is only the first two whitespace-separated words of the commit subject. For example, `Release 0.4.0 add sounds` produces the title `Release 0.4.0`.

The stable tag is `v<jayVersionName>`. Artifact names use `jayVersionName` from `gradle.properties`:

- `jay-<version>.apk`: minified production APK signed with the production key;
- `jay-<version>.aab`: minified production AAB signed with the production key;
- `jay-<version>-debug.apk`: debug APK signed with the prerelease key for debugging.

The production APK and AAB use the same `com.rispng.jay` application ID and runtime entitlement behavior. Paid access is not baked into a separate artifact.

Publishing a stable release removes every prerelease that came before it, along with its tag, so the list of releases keeps one entry per stable release.

`main`, `jay-group-addon`, and feature branches do not publish releases.

## Secrets

Signing stores and passwords belong only in local secure configuration or GitHub Actions secrets. Never add them to source control, logs, fixtures, screenshots, or generated artifacts.
