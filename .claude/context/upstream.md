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
5. If `mod_version` changed, the next jar is `<new version>+nz.<N+1>`:
   `nz_version` keeps counting (`CLAUDE.md`).

Merging (not rebasing) keeps `nz` history stable, so clones can keep using
`git pull --ff-only`.

## Keeping merges small

- Conflicts only happen in files **both** sides changed. New files never
  conflict, so put NZ code in new files (its own package) wherever possible.
- Where an upstream file must change, keep it to a small hook that calls into
  NZ code, and mark it with `// NZ:`.
- Don't reformat or tidy upstream code.

## NZ edits to upstream files

Every upstream file we changed, so a merge knows where to look. Java hooks
also carry `// NZ:`; JSON can't. Add each new one here.

| File | What | Since |
|---|---|---|
| `build.gradle` | `version` = `mod_version+nz.nz_version` | TERR-6 |
| `gradle.properties` | `archives_base_name=nz-terrain`; `nz_version` at the end | TERR-6 |
| `mc262/src/main/resources/fabric.mod.json` | `name`, `description`; the NZ client mixin config (TERR-8) | TERR-6 |
| `src/main/java/…/worldgen/EarthGeneratorSettings.java` | `nz` component, compatibility constructor, `CODEC` wrapped (`tellus-internals.md` §6); `enableWater` false for NZ settings (TERR-8, §11) | TERR-6 |
| `src/main/resources/data/minecraft/tags/worldgen/world_preset/normal.json` | optional entry `tellus:nz` | TERR-6 |
| `mc262/src/main/java/…/Tellus.java` | calls `NzTerrain.initialize()` at the end of `onInitialize` | TERR-7 |
| `src/main/resources/assets/tellus/lang/{en_us,es_es,ja_jp}.json` | `generator.tellus.nz`. Upstream's `LocalizationCoverageTest` needs every lang key in all three files | TERR-6 |
| `src/main/java/…/preload/TerrainPreloadPackageRegistry.java` | NZ settings get a view backed by `NzTerrainSampler` (`createSettingsView`, `SettingsView` constructor, `sample`, `contains`; `tellus-internals.md` §6.3) | TERR-8 |
| `src/main/java/…/worldgen/WaterSurfaceResolver.java` | `sampleOceanTerrainHeight`: NZ sea floor from the tiles (§11); field `nzWater` (`NzWater`, set in the constructor), NZ regions at the top of `buildRegionData` and NZ columns at the top of `coarseWaterColumnData` (TERR-10, §11) | TERR-8 |
| `src/main/java/…/worldgen/DhLodWaterResolver.java` | `resolveArea`: NZ LOD water from `NzWater.lodArea` (§11) | TERR-10 |
| `shared/mc262/src/main/java/…/worldgen/EarthChunkGenerator.java` | solid fill below the shell instead of the bedrock skin (`fillTellusSurface`), and in `getBaseColumn` (§4); lat/lon → block by NZTM for the spawn chunk (constructor) and `getSurfacePosition`, no Overture sand (`hasOvertureSandAt`) (TERR-9, §10) | TERR-8 |
| `src/main/java/…/worldgen/EarthBiomeSource.java` | field `nz` (`NzTerrainSampler`); cover, visual cover and ocean depth from the tiles, `NzOceanClimate` instead of OISST, no badlands relief (§8.3) | TERR-9 |
| `src/main/java/…/worldgen/TellusWorldgenSources.java` | no prefetch for NZ worlds in `warmCriticalTerrainInputsForChunk` and `prefetchForArea`, no sand prefetch in `prefetchWaterForChunk` (§10) | TERR-9 |
| `src/main/java/…/TellusCommon.java` | `openGeoTpMap`: lat/lon from both coordinates via `NzProjection` (§1) | TERR-9 |
| `src/main/java/…/world/realtime/TellusRealtimeManager.java` | lat/lon via `NzProjection` (`requestWeatherReport`, the weather fetch, `approximateUtcOffsetSeconds`) (§1) | TERR-9 |

**When upstream adds a settings component** to `EarthGeneratorSettings`, the
merge conflicts in the record header and the constructors. Add the new
component to the compatibility constructor (parameter list and `this(…)` call)
and to `NzWorldSettings.withNz`; the compiler flags both if missed.
