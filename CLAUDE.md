# nz-terrain

Fork of [Tellus](https://github.com/Yucareux/Tellus) (LGPL-3.0), becoming the
New Zealand terrain generator: real NZ terrain from local pre-processed tiles,
at 1:3 horizontal and 1:2 vertical scale, for **Minecraft 26.2 Fabric** only.

**Shared goal, decisions and workflow:** the hub `CLAUDE.md` of the `mc-nz`
workspace (`../CLAUDE.md` when this repo sits in the workspace). The block
coordinates and tile format the generator reads are in
`../.claude/context/interfaces.md`.

## Status

- No NZ changes to Tellus code yet. **Don't change Tellus code until a step
  says so** (first code step: TERR-6). Next step: see `steps.md`.
- Based on upstream Tellus `0.8.3` (commit `8c19ed8`). NZ version: none
  released yet; the numbering scheme is decided in TERR-6.

## Rules

- **Work on branch `nz`.** `main` stays identical to upstream Tellus; never put
  NZ work on it. Check the branch before editing.
- **This repo is public** (a GitHub fork of a public repo). No personal or
  network details in any file.
- **LGPL-3.0:** all our changes to this code stay LGPL. Keep `LICENSE` and
  `THIRD_PARTY_NOTICES.md` (Arnis code, Apache-2.0). New files get the same
  licence.

### Where code goes

The goal is clean upstream merges. Conflicts only happen in files that both
upstream and we changed.

1. **New NZ code goes in new files**, in an NZ package under `mc262/src/`.
   That folder is compiled only by the 26.2 Fabric target, so the code can use
   26.2 APIs freely.
2. **Existing Tellus files may be edited wherever they live** (root `src/`,
   `shared/post-1201`, `shared/mc262`, `shared/fabric`, `mc262/`), but only as
   small hooks that call into NZ code. Mark each one with an `// NZ:` comment.
   Don't reformat or tidy upstream code.
3. **Never edit** the other version modules (`mc1201*`, `mc1211*`, `mc12111*`,
   `mc262-neoforge`) or the shared layers only they use (`shared/mc1201`,
   `shared/mc1211`, `shared/mc12111`, `shared/pre-26`, `shared/neoforge`).
4. Accepted side effect: hooks in shared folders can call NZ classes that only
   exist in `mc262/`, so **the other targets may stop compiling**. We only
   build `mc262`, so that's fine. Keep GitHub Actions off on the fork.

## Build

Needs Java 25. Build only the 26.2 Fabric target:

```
./gradlew -PtellusTargets=mc262 :mc262:build
./gradlew -PtellusTargets=mc262 runClient262
```

`-PtellusTargets` is read by `settings.gradle`; without it Gradle configures
all eight targets, and those need other Java versions.

Without a display (e.g. a sandbox), the client can't open a window. Use
`runServer262` as the headless check (needs `eula=true` in
`mc262/run/eula.txt`), and run the client on a desktop. Details:
`testing.md`.

## Context index

| File | Read when |
|---|---|
| `.claude/context/steps.md` | Starting a step ("Do step TERR-n"), or picking the next one. |
| `.claude/context/tellus-layout.md` | Before reading or changing any Java code: how the source layers combine, where things live. |
| `.claude/context/tellus-internals.md` | Changing coordinates, heights, world height, the underground or world settings: how Tellus does it, file:line pointers, the proposed NZ hook points. |
| `.claude/context/upstream.md` | Merging upstream Tellus. |
| `.claude/context/testing.md` | Running or recording tests, including the scale test. |
| `.claude/context/surface-palette.md` | Choosing surface blocks: rock, scree, riverbeds, snow and ice, soils (TERR-3, TERR-11). |
