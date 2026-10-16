# Upstream Tellus

- Upstream: https://github.com/Yucareux/Tellus (LGPL-3.0).
- Branches: `main` = exact copy of upstream `main`. `nz` = our work, based on
  `main`.
- On GitHub, `nz` is the fork's default branch.
- Remotes in the clone used for pushing: `origin` = the fork, `upstream` =
  Tellus (set up by `gh repo fork`). The sandbox clone has no remotes.

## Merging a new upstream version

Done in the clone that has the `upstream` remote:

1. `git fetch upstream`
2. `git switch main` then `git merge --ff-only upstream/main` — `main` only
   moves forward, it never gets our commits.
3. `git switch nz` then `git merge main` — conflicts are resolved here.
4. Re-check `tellus-layout.md` (layers, file counts, where things live) and
   build: `./gradlew -PtellusTargets=mc262 :mc262:build`.

Merging (not rebasing) keeps `nz` history stable, so clones can keep using
`git pull --ff-only`.

## Keeping merges small

- Conflicts only happen in files **both** sides changed. New files never
  conflict, so put NZ code in new files (its own package) wherever possible.
- Where an upstream file must change, keep it to a small hook that calls into
  NZ code, and mark it with `// NZ:`.
- Don't reformat or tidy upstream code.
