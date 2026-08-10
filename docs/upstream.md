# Maintaining Jay on top of Clock You

Jay is a source-level Clock You extension, not a runtime Android plugin. Android application features cannot be injected into another APK while safely sharing its Room database, alarm services, activities, and manifest components.

The repository keeps the extension boundary narrow:

- `server/` owns the complete Jay server and PostgreSQL protocol.
- `app/src/main/java/com/bnyro/clock/social/` owns social storage, networking, synchronization, workers, and group UI.
- `device_alias_words.json` owns generated device-name vocabulary.
- `SocialDatabase` owns group and remote-revision state separately from Clock You's database.
- `SharedAlarmLink` maps remote alarms to unchanged Clock You `Alarm` rows.

The intentional Clock You integration points are:

- application startup and dependency construction;
- the home navigation list;
- the alarm editor's group selector;
- alarm create, edit, and delete dispatch;
- ringing, snooze, and early-dismiss actions;
- settings, launcher branding, dependencies, resources, and manifest declarations.

Keep an upstream remote pointing to Clock You and preserve a branch containing only upstream history. Maintain Jay on its own long-lived branch. Periodically fetch Clock You and merge its main branch into Jay. Merging preserves published Jay commit identities; rebasing would rewrite them.

For each update:

1. Review the upstream changes before merging.
2. Merge the upstream main branch into Jay without squashing it.
3. Resolve conflicts only at the integration points listed above.
4. Run the server tests and Android unit tests.
5. Verify creation, update, deletion, snooze, early dismissal, reboot rescheduling, invitation links, and server switching on devices.

Clock You commits remain visible in their original ancestry, Jay commits remain ahead of them, and merge commits record exactly which upstream release has been incorporated.
