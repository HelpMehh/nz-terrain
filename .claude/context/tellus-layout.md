# Tellus source layout

Checked against upstream `0.8.3` (commit `8c19ed8`). Re-check after each
upstream merge.

## How a target is compiled

Each version module (e.g. `mc262/`) is a thin overlay. Its Java sources are,
in order:

1. root `src/` (shared by **every** target),
2. the `shared/<layer>/src/` folders listed in its `sharedSourceLayers`
   (in `mc262/build.gradle`),
3. its own `mc262/src/`.

The source folders are put together in `gradle/tellus-fabric.gradle`. A later
layer can replace a file from an earlier layer only if that path is listed in
`overriddenSharedMainSources` / `overriddenSharedClientSources`. The root task
`checkVersionSourceDuplication` fails when identical files exist in two layers.

## Which layers each target uses

| Target | Shared layers (after root `src/`) |
|---|---|
| `mc262` (ours) | `post-1201`, `mc262`, `fabric` |
| `mc262-neoforge` | `post-1201`, `mc262`, `forge-family`, `neoforge` |
| `mc12111` | `post-1201`, `mc262`, `mc12111`, `fabric` |
| `mc12111-neoforge` | `post-1201`, `mc262`, `mc12111`, `forge-family`, `neoforge` |
| `mc1211` | `post-1201`, `pre-26`, `mc1211`, `fabric`, `pre-26-fabric` |
| `mc1211-neoforge` | `post-1201`, `pre-26`, `mc1211`, `forge-family`, `pre-26-forge`, `neoforge` |
| `mc1201` | `pre-26`, `mc1201`, `fabric`, `pre-26-fabric` |
| `mc1201-forge` | `pre-26`, `mc1201`, `forge-family`, `pre-26-forge` |

So for `mc262`:

| Folder | Java files | Also used by |
|---|---|---|
| `src/` | 314 | every target |
| `shared/post-1201/` | 3 | all except 1.20.1 |
| `shared/mc262/` | 29 | 1.21.11 (both loaders), 26.2 NeoForge |
| `shared/fabric/` | 2 | all Fabric targets |
| `mc262/src/` | 16 | nothing else |

Only `mc262/` is ours alone. An edit in any other folder also changes what the
other targets compile. We don't build those targets (`-PtellusTargets=mc262`),
but upstream's CI workflow (`.github/workflows/gradle.yml`) builds them all.
That is why NZ code goes in `mc262/src/` and edits elsewhere stay small hooks
(rules in `CLAUDE.md`).

## Where things live (root `src/main/java/com/yucareux/tellus/`)

- `world/data/` — data sources: `elevation`, `cover` (ESA WorldCover),
  `canopy`, `koppen`, `mask`, `ocean`, `osm`, `pmtiles`, `resolve`, `source`.
  The online data access we will replace with local tiles is here.
- `worldgen/` — terrain building: `tree`, `caves`, `road`, `building`, `arnis`.
- `integration/distant_horizons`, `integration/voxy` — LOD integrations.
- `preload/`, `cache/`, `network/`, `platform/`, `mixin/`.
- 26.2-specific: `shared/mc262/.../worldgen/EarthChunkGenerator.java`,
  `compat/`, `mixin/`.

**26.2 API traps** (from compile errors): screens open with
`minecraft.gui.setScreen(…)`, not `minecraft.setScreen(…)`. 26.2 is
unobfuscated, so `javap -p` on the Loom jars under
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/` shows the
real names and signatures.

Root `src/client/` holds client code shared by every target (e.g. the
`PresetEditorMixin`). How coordinates, heights and settings flow through
this code, and where NZ hooks go: `tellus-internals.md`.
