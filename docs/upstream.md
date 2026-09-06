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

1. Fetch `origin` with pruning and fetch `upstream`. Inspect all local branches, their remote counterparts, and linked worktrees, preserving uncommitted work. Review the upstream changes before merging.
2. Merge the upstream main branch into `main` without squashing it. If `origin/main` already contains the update, use it after verifying that it matches `upstream/main`.
3. Identify which contributions are still pending upstream. If Clock You accepted a contribution with changes, use its accepted implementation. Once that implementation is in `main`, stop updating and merging the old contribution branch into `main-canary`.
4. Merge the updated `main` into every active contribution branch, including branches checked out in linked worktrees. Resolve conflicts on the contribution branch, preserving its pending changes alongside the accepted upstream implementation. Contribution branches receive base updates from `main`, never from `main-canary` or `jay`.
5. Merge the updated `main` into `main-canary`, then merge every active contribution branch into `main-canary`.
6. Merge `main-canary` into `jay` and resolve conflicts only at the integration points listed above.
7. Remove local contribution branches whose tracked branches were deleted from `origin` only after verifying that their work is preserved in `main` or `main-canary`, or superseded by an accepted upstream implementation. Check linked worktrees and uncommitted work before removal. Keep branches with unpreserved work and report them; a missing remote alone is not enough to delete them.
8. Run the server tests against PostgreSQL and Android unit tests, and compile Android debug and release variants. Verify changed contribution branches as well as the integrated `jay` result.
9. Verify creation, update, deletion, snooze, early dismissal, reboot rescheduling, invitation links, and server switching on devices. Report any checks that could not be completed.
10. When committing and pushing is authorized, commit any remaining resolutions and push all updated branches to `origin`: `main`, every active contribution branch, `main-canary`, and `jay`. Check the `jay` head message against the [release rules](releases.md) before pushing. Verify that the local branches match their remote counterparts afterward.

An upstream update includes active contribution branch synchronization and obsolete local branch cleanup, even when the accepted changes are already present in `main-canary` and `jay`. Updating only the three pipeline branches is not the complete workflow.

This keeps the relationship readable: `main` is Clock You, `main-canary` adds the contributions still waiting upstream, and `jay` adds the social features. The original upstream commits stay intact.
