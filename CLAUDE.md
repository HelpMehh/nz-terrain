# nz-terrain

Fork of [Tellus](https://github.com/Yucareux/Tellus) (LGPL-3.0), becoming the
New Zealand terrain generator: real NZ terrain from local pre-processed tiles,
at 1:3 horizontal and 1:2 vertical scale, for **Minecraft 26.2 Fabric** only.

**Shared goal, decisions and workflow:** the hub `CLAUDE.md` of the `mc-nz`
workspace (`../CLAUDE.md` when this repo sits in the workspace). The block
coordinates and tile format the generator reads are in
`../.claude/context/interfaces.md`.

## Status

- "New Zealand" world type (TERR-6) that generates NZ terrain from the
  pipeline tiles (TERR-7 reader `com.yucareux.tellus.nz.tiles`, TERR-8
  heights `com.yucareux.tellus.nz.terrain`), solid down to Y −272, fully
  offline (TERR-9; lat/lon by NZTM, `com.yucareux.tellus.nz.NzProjection`),
  with sea, lakes and rivers from the tiles' water layer (TERR-10,
  `com.yucareux.tellus.nz.terrain.NzWater`; steps flow, TERR-14 step 1),
  streams from the `streams` layer (TERR-14 step 2, `NzStreams`) with
  slot gorges where the LiDAR shows a deep V (step 3), boulders
  (step 4, `NzBoulders`), cliff ledges (step 5, `NzLedges`),
  and NZ surface blocks from
  the palette file `nz-terrain/surface-palette.jsonc` (TERR-11,
  `com.yucareux.tellus.nz.terrain.NzSurface`; in-game tuning still open).
  Biomes and trees are still Tellus's. Next step: see `steps.md`.
- Based on upstream Tellus `0.8.3` (commit `8c19ed8`). Latest NZ version:
  **`0.8.3+nz.15`** (TERR-14 step 5 follow-up, before its second desktop
  check).

## Naming and versions (decided in TERR-6)

- **Mod ID stays `tellus`.** It is also the namespace of every resource and
  registry ID, so renaming it would touch hundreds of upstream files. The
  jar therefore **replaces** Tellus and can't be installed next to it.
- Display name "NZ Terrain"; jar `nz-terrain-fabric-26.2-<version>.jar`
  (`archives_base_name` in `gradle.properties`).
- NZ package: `com.yucareux.tellus.nz`.
- **Version `<Tellus version>+nz.<N>`**: the Tellus part is `mod_version`,
  N is `nz_version`, both in `gradle.properties`. Raise N by 1 in every change
  set that changes the jar, and never reset it (not after an upstream merge
  either). Fabric ignores the `+…` part when comparing versions, so nothing
  can depend on a minimum NZ version.
- Changelog: **`CHANGELOG-NZ.md`**, NZ changes only. The name can't collide
  with a changelog that upstream adds.

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

1. **New NZ code goes in new files**, in `com.yucareux.tellus.nz` under
   `mc262/src/` (NZ resources: `mc262/src/main/resources/`). That folder is
   compiled only by the 26.2 Fabric target, so the code can use 26.2 APIs
   freely.
2. **Existing Tellus files may be edited wherever they live** (root `src/`,
   `shared/post-1201`, `shared/mc262`, `shared/fabric`, `mc262/`), but only as
   small hooks that call into NZ code. Mark each one with an `// NZ:` comment,
   and list the file in `upstream.md` (JSON files can't hold the marker).
   Don't reformat or tidy upstream code. In upstream Java files, call NZ
   classes by their full name (`com.yucareux.tellus.nz.…`) instead of
   adding imports: import blocks conflict easily in merges.
   NZ mixins go in NZ's own mixin configs, not Tellus's:
   `nz-terrain.mixins.json` (package `com.yucareux.tellus.nz.mixin`) and
   `nz-terrain.client.mixins.json` (`com.yucareux.tellus.nz.mixin.client`).
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
| `.claude/context/tellus-internals.md` | Changing coordinates, heights, world height, the underground, world settings, data sources, water, surfaces, DH or online features: how Tellus does it, file:line pointers, the NZ hooks (§6). |
| `.claude/context/upstream.md` | Merging upstream Tellus, or editing an upstream file (the list of NZ edits). |
| `.claude/context/testing.md` | Running or recording tests, including the scale test. |
| `.claude/context/surface-palette.md` | Choosing or tuning surface blocks: rock, scree, riverbeds, snow and ice, soils; the palette file and how to tune it in game (TERR-3, TERR-11). |
