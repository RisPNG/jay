# Maintaining Jay on top of Clock You

Jay builds directly on Clock You's source. This lets it use the same alarm services, Room database, activities, and Android manifest components. A runtime plugin could not safely share all of that by injecting features into another APK, which is why the separation here happens in the source code.

## Where things belong

Keep the social code together so it is clear what Jay adds and what comes from Clock You:

- `server/` owns the complete Jay server and PostgreSQL protocol.
- `app/src/main/java/com/bnyro/clock/social/` owns social storage, networking, synchronization, workers, and group UI.
- `device_alias_words.json` owns generated device-name vocabulary.
- `SocialDatabase` owns group and remote-revision state separately from Clock You's database.
- `SharedAlarmLink` maps remote alarms to unchanged Clock You `Alarm` rows.

Some social features need to connect to the clock itself. These are the places where that integration belongs:

- application startup and dependency construction;
- the home navigation list;
- the alarm list's group labels, editing permissions, and source filter;
- the alarm editor's group selector;
- alarm create, edit, and delete dispatch;
- shared-alarm time-zone resolution during scheduling;
- ringing, snooze, and early-dismiss actions;
- the timer's start dispatch, including the group a group timer is started for;
- timer add-time, reset, and stop dispatch, and the ringing timer's answer actions;
- the timer list's group labels and action permissions;
- the saved-timer sheet's group template field;
- settings, launcher branding, dependencies, resources, and manifest declarations.

If an improvement would be useful in Clock You on its own, develop it on a focused branch from `main` and submit it upstream. That includes alarms, clocks, timers, the stopwatch, settings, onboarding, notifications, and pickers. Social groups, shared alarms, membership, synchronisation, social notifications, entitlements, and the server belong on `jay`.

A social feature may change a Clock You-owned file only at one of the integration points above. Use the existing clock behaviour there. For example, a shared alarm should use Clock You's alarm scheduling rather than a second scheduler written just for groups.

## Branch responsibilities

The branches form a pipeline, and merges only flow down it:

    main → main-canary → jay

Keep an `upstream` remote pointing to Clock You. `main` must contain the exact upstream history, including its original commit messages and hashes. That makes it possible to compare and update the base without introducing another copy of the same commits.

Start Clock You contributions on focused branches from `main`, then merge every active contribution branch into `main-canary` while the work waits upstream. `main-canary` contains the upstream base and those pending contributions. It carries no social code and publishes nothing.

Develop the social extension directly on `jay`, on top of `main-canary`. Keep changes outside the social package limited to the integration points above. This is also the publishing branch: ordinary pushes create prereleases, and commits beginning exactly with `Release ` create stable releases.

Fix a problem where it belongs, then let the fix follow the pipeline. A Clock You defect gets fixed on its contribution branch and merged into `main-canary`. A social defect gets fixed directly on `jay`. Merge `main-canary` into `jay` to bring the clock improvements along, resolving conflicts only at the documented integration points.

## Updating upstream

When Clock You changes:

1. Review the upstream changes before merging.
2. Merge the upstream main branch into `main` without squashing it.
3. Merge the updated `main` into `main-canary`. If Clock You accepted a contribution with changes, use its accepted implementation. Once that implementation is in `main`, stop merging the old contribution branch into `main-canary`.
4. Merge `main-canary` into `jay` and resolve conflicts only at the integration points listed above.
5. Run the server tests and Android unit tests.
6. Verify creation, update, deletion, snooze, early dismissal, reboot rescheduling, invitation links, and server switching on devices.

This keeps the relationship readable: `main` is Clock You, `main-canary` adds the contributions still waiting upstream, and `jay` adds the social features. The original upstream commits stay intact.
