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

The branches form a pipeline, and merges only flow down it:

    main → main-canary → jay-group-addon → jay

Keep an upstream remote pointing to Clock You and preserve `main` as a branch containing only upstream history. Develop base Clock You improvements on focused branches from `main` for upstream pull requests, and merge every active contribution branch into `main-canary`, which holds the `main` that will be: the upstream base plus every contribution still waiting upstream. `main-canary` carries no social code, records which Clock You contributions are pending, and publishes nothing.

Maintain the social extension on `jay-group-addon`, based on `main-canary`, with changes outside the social package limited to the integration points above. `jay-group-addon` does not publish releases.

Use `jay` as the publishing head of `jay-group-addon`: merge `jay-group-addon` into `jay`, where ordinary pushes publish prereleases and commits beginning exactly with `Release ` publish stable releases. No other branch publishes a release.

Changes should originate on the branch that owns them and flow down the pipeline. If combined testing exposes a Clock You defect, fix its contribution branch and merge the corrected branch into `main-canary`; if it exposes a social defect, fix `jay-group-addon`; then let the correction flow down into `jay`. Avoid leaving a fix only on `jay`, because that makes its eventual upstream or add-on ownership ambiguous.

## Updating upstream

For each update:

1. Review the upstream changes before merging.
2. Merge the upstream main branch into `main` without squashing it.
3. Merge the updated `main` into `main-canary`. Where an accepted contribution differs from the prematurely integrated branch, the upstream Clock You implementation is authoritative, and once `main` carries a contribution's accepted implementation its contribution branch is superseded and stops being merged into `main-canary`.
4. Merge `main-canary` into `jay-group-addon` and resolve conflicts only at the integration points listed above.
5. Merge `jay-group-addon` into `jay`.
6. Run the server tests and Android unit tests.
7. Verify creation, update, deletion, snooze, early dismissal, reboot rescheduling, invitation links, and server switching on devices.

Clock You commits remain visible in their original ancestry, `main-canary` records which Clock You contributions are still pending upstream, social extension commits remain isolated on `jay-group-addon`, and `jay` is the publishing head of the social product line.
