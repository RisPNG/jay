# Maintaining Jay on top of Clock You

Jay is a source-level Clock You extension, not a runtime Android plugin. Android application features cannot be injected into another APK while safely sharing its Room database, alarm services, activities, and manifest components.

## Ownership boundary

The repository keeps the extension boundary narrow:

- `server/` owns the complete Jay server and PostgreSQL protocol.
- `app/src/main/java/com/bnyro/clock/social/` owns social storage, networking, synchronization, workers, and group UI.
- `device_alias_words.json` owns generated device-name vocabulary.
- `SocialDatabase` owns group and remote-revision state separately from Clock You's database.
- `SharedAlarmLink` maps remote alarms to unchanged Clock You `Alarm` rows.

The intentional Clock You integration points are:

- application startup and dependency construction;
- the home navigation list;
- the alarm list's group labels, editing permissions, and source filter;
- the alarm editor's group selector;
- alarm create, edit, and delete dispatch;
- ringing, snooze, and early-dismiss actions;
- settings, launcher branding, dependencies, resources, and manifest declarations.

Base clock behavior belongs to Clock You. Develop improvements to alarms, clocks, timers, stopwatches, settings, onboarding, notifications, pickers, and other generally useful Clock You behavior on focused branches from `main`, then submit them upstream. Social groups, shared alarms, membership, synchronization, social notifications, entitlements, and the Jay server belong to `jay-group-addon`. A social feature may modify a Clock You-owned file only at an integration point above and should reuse the existing Clock You domain behavior rather than create a parallel implementation.

## Branch responsibilities

Keep an upstream remote pointing to Clock You and preserve `main` as a branch containing only upstream history. Maintain the social extension on `jay-group-addon`, based on the latest `main`, with changes outside the social package limited to the integration points above. `jay-group-addon` does not publish releases.

Use `jay` as the complete integration branch. Merge `jay-group-addon` and every active Clock You contribution branch into `jay` for combined testing. Ordinary pushes to `jay` publish prereleases, while commits beginning exactly with `Release ` publish stable releases. No other branch publishes a release.

Changes should originate on the branch that owns them. If combined testing exposes a Clock You defect, fix its contribution branch; if it exposes a social defect, fix `jay-group-addon`; then merge the corrected branch back into `jay`. Avoid leaving a fix only on `jay`, because that makes its eventual upstream or add-on ownership ambiguous.

## Updating upstream

For each update:

1. Review the upstream changes before merging.
2. Merge the upstream main branch into `main` without squashing it.
3. Merge the updated `main` into `jay-group-addon` and resolve conflicts only at the integration points listed above.
4. Merge `jay-group-addon` and active Clock You contribution branches into `jay`.
5. Run the server tests and Android unit tests.
6. Verify creation, update, deletion, snooze, early dismissal, reboot rescheduling, invitation links, and server switching on devices.

When Clock You accepts a contribution branch, update `main` to include the upstream implementation, merge `main` into `jay-group-addon`, and stop merging the superseded contribution branch into `jay`. If the accepted implementation differs from the prematurely integrated branch, the upstream Clock You implementation is authoritative.

Clock You commits remain visible in their original ancestry, social extension commits remain isolated on `jay-group-addon`, and `jay` records the complete tested combination without obscuring which Clock You contributions are still pending upstream.
