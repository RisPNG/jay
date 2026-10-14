Clock You to Jay sync routine:
1. Update `main` from the latest remote origin/main of Clock You repository.
2. Check all `main` changes and see whether they're up to the standard or not (AGENTS.md), if it is not, decide whether the fix will belong in jay or in contribution branch, and do the fixes.
3. Remove finished local branches only when their work is preserved, as described in `CONTRIBUTING.md`.
4. Update branches following the documented merge flow.
5. If Jay's current version has a matching `Release <version>` commit, bump the patch version and version code once; otherwise leave them unchanged.
6. Push the updated branches to `origin`.
