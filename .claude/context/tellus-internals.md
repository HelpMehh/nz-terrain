# Tellus internals

How Tellus turns block coordinates into terrain, and where NZ code plugs in.
Checked against upstream `0.8.3` (commit `8c19ed8`) in TERR-4 (§1–6) and
TERR-5 (§7–11). Line numbers drift after upstream merges: search for the
method name if one is off.

Short paths used below (all under `com/yucareux/tellus/`):

| Short | Folder |
|---|---|
| `root:` | `src/main/java/…` (every target) |
| `root-client:` | `src/client/java/…` (every target) |
| `s262:` | `shared/mc262/src/main/java/…` |
| `s262-client:` | `shared/mc262/src/client/java/…` |
| `m262:` | `mc262/src/main/java/…` (ours alone) |

The chunk generator `s262:worldgen/EarthChunkGenerator.java` is about
14,800 lines. Read it by searching for method names, not top to bottom.

## 1. Block x/z ↔ latitude/longitude

`root:worldgen/EarthProjection.java` — spherical Web Mercator, block 0,0 at
0°N 0°E.

- `world_scale` = metres per block **at the equator**.
  `lon = x × worldScale / 111,319.49` (`:18`);
  `z = −R × ln(tan(45° + lat/2)) / worldScale` (`:30`), inverse `:43`.
- Ground metres per block = `worldScale / sec(lat)` (`:95`), so the scale
  changes with latitude (TERR-1: 3.46 m at Cape Reinga, 2.85 m at Stewart
  Island with world scale 4.2).
- System property `tellus.projection.mode=legacy` switches to a plain
  lat/lon grid (`:9`). Not useful for us.

**There is no single place to swap the projection.** About 40 files call
`EarthProjection` directly, and most compute `lon = x / blocksPerDegree`
inline (`blockXToLongitude` is unused). The callers fall in these groups:

- data sources, `root:world/data/*` (elevation, cover, canopy, koppen, mask,
  ocean, osm, resolve);
- worldgen helpers in `root:worldgen/` (`WaterSurfaceResolver`,
  `OceanCoastField`, `LandlockedSeaLevel`, `AntarcticSnowPolicy`,
  `WaterfallNoCarveZone`, `DhLodWaterResolver`, buildings, roads);
- the chunk generator (spawn, OSM road and building geometry);
- DH, `s262:integration/distant_horizons/TellusLodGenerator.java` (23 calls);
- preload areas, the realtime time/weather manager, and the client preview
  and customize screens.

**But the data sources take block x/z**, not lat/lon, and project inside:
e.g. `TellusElevationSource.sampleElevationMeters(blockX, blockZ,
worldScale)` (`root:world/data/elevation/TellusElevationSource.java:104`,
projection at `:1508`). So the generator mostly thinks in block coordinates.

**Decided (TERR-4, done in TERR-9):** leave `EarthProjection` alone. Our tiles are already in block
coordinates (hub `interfaces.md`), so NZ worlds read them by block x/z and
the terrain path never needs lat/lon. Any Tellus code that still projects
with Mercator in an NZ world reads the wrong place on Earth: our blocks
x ±170k, z ±241k at world scale 3 are about ±4.6° lon and ±6.5° lat around
0°N 0°E, i.e. the Gulf of Guinea and West Africa (tropical Köppen, warm
ocean). So it must be switched off or redirected to our data (§10, done in
TERR-9).

**NZ lat/lon (TERR-9):** `m262:nz/NzProjection` converts block ↔ NZTM ↔
lat/lon (Krüger series on GRS80, checked against pyproj to 1 mm, also past
180° at the Chathams). Only what a person types or sees uses it, through
hooks:
- the spawn: the preset's `spawn_latitude`/`spawn_longitude` are **real**
  lat/lon, and the generator turns them into a block by NZTM (constructor
  spawn chunk, `getSurfacePosition`). No NZ spawn setting was needed;
- `/tellus map` (opens Tellus's OSM map with place search at the player's
  lat/lon; a click teleports through `getSurfacePosition`), and
  `/tellus weather` and the realtime overrides (Open-Meteo at the player's
  lat/lon). These stay online, client-side or on request only; world
  generation never uses them.

Trap: Tellus's `latitudeFromBlock(z)` and `longitudeFromBlock(x)` each take
one coordinate, which can't work for a transverse projection. They are left
unchanged; the hooks call `NzProjection.latitude/longitude(generator, x, z)`
at each caller (`TellusCommon`, `TellusRealtimeManager`). A new caller of
the one-coordinate methods would read Mercator in NZ worlds.

## 2. Elevation → block Y

`root:worldgen/TerrainHeightTransform.java`:

- `blockOffset(…)` (`:249`):
  `scaled = elevation × heightScale × correction / effectiveVerticalWorldScale`
  (`:225`).
  - `heightScale` = `terrestrial_height_scale` for elevation ≥ 0,
    `oceanic_height_scale` below 0.
  - `correction` = sec(latitude) when `automatic_height_scaling` is on (it
    undoes the Mercator stretch), else 1.
  - `effectiveVerticalWorldScale()` is just `worldScale`
    (`root:worldgen/EarthGeneratorSettings.java:819`).
- Rounded **away from sea level**: `ceil` for elevation ≥ 0, `floor` below
  (`:341`). So land 0–2 m high lands on Y 64, never on 63.
- Block Y = `blockOffset + effectiveHeightOffset()`.
- "Increase Height" (experimental) forces scales 1.0 and offset 0 and
  compresses heights (`:226`). Keep it off.

Where elevation becomes Y (every one calls `blockOffset`):

| Where | What |
|---|---|
| `s262:worldgen/EarthChunkGenerator.java:5296` `scaleElevationToHeight` | the generator's own height samples |
| `root:worldgen/WaterSurfaceResolver.java:3478` `scaleElevationToHeight` | water and ocean-floor columns |
| `root:worldgen/WaterSurfaceResolver.java:192` | minimum waterfall drop, at the spawn latitude |
| `root:worldgen/TellusWorldgenSources.java:143` `samplePreloadTerrain` | writing preload packages |
| `s262-client:client/preview/TerrainPreview.java:1350` | the customize-screen preview |

**Finding: our height formula needs no code.** Hub `interfaces.md` says
`y = 63 + elevation / 2`. Tellus gives exactly that with:

| Setting (JSON key) | Value |
|---|---|
| `world_scale` | 3.0 |
| `terrestrial_height_scale` | 1.5 |
| `oceanic_height_scale` | 1.5 |
| `height_offset` | 63 |
| `automatic_height_scaling` | false |
| `experimental_increase_height` | false |

because `elevation × 1.5 × 1 / 3 = elevation / 2`. `world_scale` 3.0 is
also right everywhere Tellus uses it as "metres per block" (ocean-floor
transitions, waterfall drop, LOD resolution), because with NZTM 3 m per
block holds everywhere, unlike Mercator. The NZ preset has exactly these
values. **Rounding (decided in TERR-8): Tellus's own**, away from sea level.
Tellus fills sea water up to Y 63 inclusive, so land rounded to Y 63 would
sit level with the sea surface; rounding up keeps every coast at least one
block above the water. The cost, land about ½ block too high on average,
can't be seen. The tiles store metres, not block Y (HUB-2).

TERR-1 used world scale 4.2 with automatic scaling **on** instead, because
released Tellus places NZ by Mercator. That is only right for the TERR-1
test, not for NZ worlds.

## 3. Sea level and world height

- Sea level = `effectiveHeightOffset()` (`EarthChunkGenerator.java:412`,
  `getSeaLevel()` `:4663`). With `height_offset` 63 it is Y 63.
- World height: `EarthGeneratorSettings.resolveHeightLimits` (`:2236`).
  - Explicit `min_altitude` / `max_altitude` are aligned to 16 and become
    the dimension's `min_y` and `height`. −272 / 2031 give
    `min_y −272, height 2304`, exactly as wanted.
  - `Integer.MIN_VALUE` means "auto": sized for Everest and the Mariana
    Trench, which doesn't fit, so it falls back to the full −2032..2031.
  - Anything outside −2032..2031 or taller than 4,064 (`:88`–`:90`) falls
    back to the full range too.
- The limits are written into the dimension type twice:
  1. at world creation, by the customize screen
     (`s262-client:client/screen/EarthCustomizeScreen.java:232`
     `updateWorldCreationContext` → `s262:compat/MinecraftVersionCompat.java:49`
     `applyHeightLimits`);
  2. at every server start, into a datapack inside the world folder
     (`root:TellusCommon.java:683` `ensureDynamicDimensionPack`, called at
     `:206`), so they survive restarts.
  The starting types are
  `shared/mc262/src/main/resources/data/tellus/dimension_type/earth.json`
  (`min_y −368`, `height 800`) and `earth_dynamic.json`.
- `ExperimentalHeightSupport.validateOrThrow`
  (`root:worldgen/ExperimentalHeightSupport.java:72`) does nothing unless
  Increase Height is on.
- **NZ worlds (TERR-8)** use a fixed dimension type `tellus:nz`
  (`m262` resources `data/tellus/dimension_type/nz.json`, a copy of
  `earth.json` with Y −272..2031). `resolveTellusDimensionKey` only knows
  `tellus:earth` and `tellus:earth_dynamic`, so Tellus leaves it alone and
  writes no height datapack. The preset's `min_altitude`/`max_altitude`
  must keep matching it; `NzWorldSettingsTest` checks that.

## 4. What a world from Y −272 to 2,031 breaks

Fine: the dimension type (above), DH rendering (TERR-1), sea level, the
validators.

**The real issue: Tellus builds a terrain shell, not a solid world.**
`usesTerrainShell()` always returns true
(`EarthGeneratorSettings.java:823`; the `thin_shell_terrain` setting is
ignored). Each column is solid only from the surface down
`underground_depth` blocks (setting, 64–512, default 256), then one bedrock
block, then **air** down to the world floor.

- Bottom of the shell:
  `root:worldgen/TerrainShellBedrockProtection.java:12` `supportBottomY` =
  `max(minY, surface − undergroundDepth)`; used in
  `EarthChunkGenerator.java:5057` and the column fill at `:1317`–`:1334`.
  Bedrock "curtains" (`:1164`–`:1173`) hide the void where a lower
  neighbour column would expose it.
- So the floor at −272 is reached only where
  `surface ≤ −272 + undergroundDepth`: with the default 256, only on sea
  floor below Y −16 (deeper than about 158 m). With 512, ground up to Y 240
  (354 m elevation). Under Aoraki (Y ≈ 1,922) with the default, bedrock sits
  at Y ≈ 1,666 with about 1,940 blocks of air below. The TERR-1 world was
  built this way too; it can't be seen from above.

**Everything underground is relative to the surface, not to absolute Y.**
`root:worldgen/caves/TellusCaveDepthMapper.java` squeezes or stretches
vanilla's column (Y −64 up to the vanilla-equivalent surface) linearly into
the shell. That covers:

- noise caves (`root:worldgen/caves/TellusVanillaNoiseCaveSampler.java:115`);
  their lava level −54 (`:246`–`:250`) is a *vanilla* Y, so it lands near
  the shell bottom;
- carver caves and ravines (`root:worldgen/caves/TellusConfiguredCarvers.java:46`–`:51`),
  with lava at shell floor + 8;
- ores and other height-range features (`root:mixin/HeightRangePlacementMixin.java`);
- the deepslate boundary at vanilla Y 0 (`TellusCaveDepthMapper.actualDeepslateBoundaryY`, `:52`);
- cave biomes and the deep dark (`root:worldgen/EarthBiomeSource.java`, by
  `undergroundDepth`).

So no lava level needs patching: it already follows the shell, and ores
already go below −64 wherever the shell does. Caves, ores, lava pools and
geodes are **off** in Tellus's defaults (`EarthGeneratorSettings.DEFAULT`,
which the Customize screen starts from); the NZ preset turns them on. One
side effect: when the shell is deeper than vanilla's
column, caves get stretched vertically. Example: ground at Y 113 with depth
512 → a 385-block shell for a 177-block vanilla column → caves 2.2× taller.

There is a dormant full-fill path in the generator (`!thinShellTerrain` at
`EarthChunkGenerator.java:1283`, `fillSolidSections`, deepslate from
`minY + 64` at `:1048`, bedrock at `minY` at `:1096`). Upstream no longer
uses it, so it is untested.

### NZ worlds: shell plus solid fill (option B, decided in TERR-8)

The options were: A, shell only (no code, but air under every hill); B,
shell plus solid fill; C, an absolute vanilla underground (fights the whole
surface-relative design). B was chosen, with `underground_depth` 256. The
deep zone stays plain deepslate until TERR-20 carves caverns into it.

- `m262:nz/terrain/NzUnderground` fills each column from the floor + 1 up to
  the shell's bedrock block with deepslate and puts bedrock at the floor.
  Hooks in `EarthChunkGenerator`: after the column loop of
  `fillTellusSurface`, in place of the bedrock skin and curtains (with no
  void there is nothing to hide; skins would show as bedrock walls in
  caves), and at the end of the shell branch of `getBaseColumn`. It took
  two hooks, not one: the fill must not move the shell bottom, because
  caves, ores and deepslate are mapped onto the shell.
- Not hooked: the "terrain refinement" rebuild (`rewriteBottom` path,
  about `:7344`, and its skin arrays). It only runs with the deferred-shell
  system properties, or after an `OceanCoverageUnavailableException`,
  which NZ worlds can't throw (their ocean floor skips the coast field,
  §11). If either changes, that path needs the same fill.
- The skin and curtain arrays are still computed for NZ chunks and then
  ignored; cheap, and skipping them would need a third hook.
- Measured (TERR-8 server run): every column has bedrock at −272 and
  deepslate up to the shell; ore blobs and gravel spill up to 16 blocks
  below the shell bottom, as ore features do.
- Cave stretch with depth 256 (vanilla's column −64 up to the surface,
  mapped into 256 blocks): about 2× taller at the coast, 1.45× at 100 m,
  vanilla-like around Y 190, squashed (0.7×) in the mountains.

### NZ worlds: the cave surface (TERR-23)

**Why caves opened out of faces:** Tellus keeps a cover over caves
measured from each column's *own* surface: noise caves start 5 blocks
down (`TellusVanillaNoiseCaveSampler.SURFACE_COVER_DEPTH` + 1), the
carver mask blocks the same 5 (`TellusVanillaCarverRunner`, the
`y >= surfaceYByColumn[index] - 4` test). On a slope the downhill neighbour's
surface is lower than the cave, so the cave opens sideways out of the
face. `caves_reach_surface` is off (Tellus's default; the NZ preset
keeps it), so there are no intended mouths at all; every opening seen
was this.

`m262:nz/terrain/NzCaves` (decisions in `steps.md`, TERR-23):

- **Cave surface** = the lowest terrain height (`sampleSurfaceHeight`,
  i.e. the sampler with streams and ledges) in a 13 × 13 square around
  the column (radius `RADIUS_BLOCKS` 6). Per chunk from a 28 × 28 window,
  cached per settings value (4,096 chunks, emptied when full or when the
  tile store changes).
- **Caves:** the carve path in `EarthChunkGenerator` lowers the
  `terrainSurfaceYByColumn` array it hands the cave runner (min with the
  real surface, so a lake bed stays lower). Both covers then count from
  the cave surface. Noise caves map vanilla's column onto the shorter
  column (`TellusCaveDepthMapper`), so under steep ground they sit lower.
  The carvers' height range still comes from the real surface at the
  source chunk's middle (`orderedCarvers(sourceSurfaceY)`); the mask cuts
  them off, which can leave flat ceilings in tunnels near faces. Moving
  that too would mean a cave surface per source chunk (17 × 17 per chunk).
  The shell bottom (`generationFloorYByColumn`) is unchanged.
- **Height-range features** (`HeightRangePlacementMixin`, via
  `getUndergroundPlacementSurfaceY`): springs, underground lava lakes,
  geodes, ores, lush-cave plants, monster rooms. A spring needs exactly
  one open side, which inside the cave surface can only be a cave, so
  lava and water no longer pour out of cliffs.
- **Dropped features** (`shouldKeepFeatureId`): `lake_lava_surface`
  (placed on the surface heightmap, not by height range) and
  `rooted_azalea_tree`. The latter is lush caves' root system, which grows
  up to 100 blocks to the surface and plants an azalea tree there; Tellus's
  tree filter misses it (its `RootSystemConfiguration` doesn't list the
  tree in `getFeatures`). The filtered biome settings are cached by
  `geologyFlags`, so NZ worlds set bit 256.
- **Lush caves** only where `NzCaves.lushCavesUnder` says: the
  `vegetation` class is a forest (123, 130–135, 140–143) or mangrove
  (153), or, without that layer, LCDB maps to ESA trees or mangroves.
  Hook in `EarthBiomeSource.resolveCaveBiome` (lush chance 0 elsewhere).
- **Measured (TERR-23 headless check, `testing.md`):** the caves open
  to the sky are gone, while the cave volume drops only 4–5 % (60–80 %
  fewer cave blocks within 20 blocks of the surface).
- **Later:** glaciers (TERR-22) lower the cave surface to their bed;
  cave mouths (TERR-24) raise it back to the ground where a mouth goes.

## 5. How settings reach the chunk generator

1. **Preset:** `src/main/resources/data/tellus/worldgen/world_preset/earth.json`
   defines `tellus:earth`: dimension type `tellus:earth`, generator type
   `tellus:earth` with a `settings` object, and biome source `tellus:earth`
   with its **own copy** of `settings`. The tag
   `data/minecraft/tags/worldgen/world_preset/normal.json` puts it in the
   World Type list.
2. **Codecs:** `m262:Tellus.java:25`–`:26` registers the biome source and
   generator codecs. The generator codec (`EarthChunkGenerator.java:159`) is
   `biome_source` + `settings`.
3. **Settings:** `root:worldgen/EarthGeneratorSettings.java:19`, a record
   with 64 components; `DEFAULT` at `:113`. Its codec (`MAP_CODEC`, `:408`)
   is written by hand; every key falls back to `DEFAULT` when missing, so a
   preset only needs the keys it changes. Decoding applies optional keys one
   by one through private `with…` methods, each calling the full
   constructor.
4. **Customize screen:** `root-client:mixin/client/PresetEditorMixin.java:25`
   attaches `EarthCustomizeScreen` to the preset. `buildSettings()`
   (`EarthCustomizeScreen.java:454`) reads the sliders into
   `new EarthGeneratorSettings(…)` (`:550`). `onClose()` (`:213`) resolves
   the height limits and swaps the overworld's dimension type and generator
   in the world-creation context (`:232`, generator via
   `EarthChunkGenerator.create` at `:269`, which builds the biome source
   from the same settings). The world saves that into `level.dat`.
5. **Server start:** the dimension datapack is (re)written (section 3).
6. **Generator constructor** (`EarthChunkGenerator.java:409`): sea level,
   height limits, the water resolver (`TellusWorldgenSources.waterResolver`,
   one per distinct settings value), the preload view, and the spawn chunk
   from spawn lat/lon via Mercator (`:420`–`:424`; NZTM in NZ worlds, §1).

The settings record is the only per-world object most code receives: data
sources are static singletons (`root:worldgen/TellusWorldgenSources.java:41`),
and per-world helpers are looked up by settings (as map keys, so equality
matters).

## 6. Hook points

1. **NZ flag (built in TERR-6).** Everything gets `EarthGeneratorSettings`,
   so the flag rides on it: a last component `Optional<NzWorldSettings> nz`
   (`m262:nz/NzWorldSettings.java`); present means NZ world
   (`NzWorldSettings.isNzWorld`). This is option b from TERR-4. Option a
   (edit all 21 constructor calls) would conflict with every upstream settings
   change; option c (an NZ generator subclass) doesn't help, because the key
   methods are private and the sources only see settings.
   - Tellus's own `new EarthGeneratorSettings(…)` calls use a compatibility
     constructor with the old parameter list, which passes `Optional.empty()`.
     They stay untouched.
   - The `nz` key comes from wrapping Tellus's codec
     (`CODEC = NzWorldSettings.withNzKey(MAP_CODEC).codec()`). It is applied
     after Tellus has decoded all of its own keys, so its private `with…`
     copies can't drop it.
   - **Trap:** every Tellus copy (the public `withUndergroundDepth`,
     `withCustomTrees`, … and `EarthCustomizeScreen.buildSettings()`) returns
     **non-NZ** settings. Copy with `NzWorldSettings.withNz(…)` afterwards, or
     round-trip through `CODEC` (as `TerrainPreloadSettingsOverrides.apply`
     does, which keeps `nz`). In TERR-6 only tests call the public ones.
   - New NZ settings go into `NzWorldSettings` (a `RecordCodecBuilder`
     codec), never into `EarthGeneratorSettings`. 26.2 ships DataFixerUpper
     10, which has no `Codec.unit`; use `MapCodec.unitCodec` if a unit codec
     is ever needed.
   - **Data versions (TERR-7):** `nz.data_versions` = tile set name → data
     version. When the key is missing (the preset's `"nz": {}`, or a world
     from before TERR-7), decoding fills in the tiles found right now
     (`NzTiles.currentDataVersions`). The preset is decoded when a world is
     created, so the world saves the versions it was made from, with no hook
     in world creation. That decode must never throw: it runs while the world
     presets load, and an exception there would break every world type. Both
     settings copies (generator and biome source) get the versions.
   - Unit tests that decode `"nz": {}` need `tellus.gameDir` and
     `tellus.configDir` set to a temp folder (`NzWorldSettingsTest`): without
     them `FabricLoader.getGameDir()` throws "invoked too early?".
   - In 26.2 a world keeps its generator settings in
     `<world>/data/minecraft/world_gen_settings.dat`, not in `level.dat`.
2. **NZ world preset (built in TERR-6, values in TERR-8).**
   `m262` resources `data/tellus/worldgen/world_preset/nz.json`, listed in
   the root `normal` tag as an optional entry, so it shows as "New Zealand" in
   the World Type list. It has no editor (`PresetEditorMixin` maps only
   `EARTH`), so Customize is unavailable for it, on purpose: the Earth screen
   shows a Mercator map, spawn picker and scale sliders that would be wrong
   for NZ. Both settings copies hold the same values: the section 2 scales,
   `min_altitude` −272, `max_altitude` 2031, `underground_depth` 256, caves,
   ores, lava pools and geodes on, roads, buildings and realtime off, the
   spawn, and `"nz": {}`.
   - **Height on first start (fixed in TERR-8):** without an editor, nothing
     applies the height limits at world creation, and Tellus's height
     datapack (section 3) only takes effect at the *next* start. With
     Tellus's `tellus:earth` type, the first session ran at Y −368..431 and
     a reopened world failed with `Failed to load chunk …
     ArrayIndexOutOfBoundsException` (a different section count). The
     preset now uses the fixed type `tellus:nz` (section 3), so the height
     is right from the start; checked by reopening a world (`testing.md`).
   - **Spawn (TERR-9):** `spawn_latitude`/`spawn_longitude` are the real
     lat/lon of block −76040, 93845 (Sentinel Rock, Franz Josef), turned into
     the block by NZTM (§1). NZ worlds made before TERR-9 saved Mercator
     values (−2.53, −2.05) that now point far out to sea; their spawn was
     already saved, but they are throwaway anyway.
   - **`custom_trees: false` (TERR-9):** Tellus's procedural trees download
     ETH canopy heights (§10), so NZ worlds get vanilla tree features per
     biome until TERR-13. The tiles' own `canopy` layer could feed the
     procedural trees instead, if TERR-13 wants them.
   - **No tiles, no world (TERR-8):** `nz/mixin/client/CreateWorldScreenMixin`
     (its own mixin config, `nz-terrain.client.mixins.json`) stops the
     Create button for this preset when `NzTerrain.creationRefusal()` finds
     no usable tile set, and shows where it looked. A dedicated server with
     an NZ world and no tiles throws at `SERVER_STARTING`, before any level
     exists. An existing single-player world only warns: throwing at server
     start would crash the client.
3. **Terrain inputs (built in TERR-8): one hook, in the preload view.** The
   generator, the water resolver and the DH fast LOD all ask
   `TerrainPreloadPackageRegistry.SettingsView` first for height, cover
   class, land mask and ocean flags (`samplePreloadedTerrain`, 13 calls in
   the generator, all of the resolver's `sampleSurface`, `sampleCoverClass`
   and `sampleLandMask`), and use the online sources only when it has
   nothing. For NZ settings, `createSettingsView` returns a view backed by
   `m262:nz/terrain/NzTerrainSampler`, which answers every block with a
   `TerrainPreloadPackage.Sample` built from the tiles:
   - height: the finest available level for the requested resolution
     (`levelFor`: level = log2 of the resolution in blocks; chunks ask
     level 0, DH coarser), bilinear between pixel centres, so level 0 gives
     each block its own pixel. Sea and nodata pixels use the placeholder
     floor (`NzSeaFloor`, §11). Then Tellus's `TerrainHeightTransform`;
   - land mask: known everywhere, land where the water layer isn't sea or
     nodata;
   - cover: ESA no-data (0) on the sea, which Tellus treats as ocean, water
     (80) on lakes and rivers, else LCDB mapped by `NzCoverClasses`
     (stopgap: since TERR-11 it no longer picks surfaces, §12, but it still
     drives Tellus's biomes and trees until TERR-12/13);
   - `oceanElevationSelected` true everywhere, so DH's LOD ocean floor
     trusts the sample instead of asking OpenWaters.

   `contains` is always true, which also skips Tellus's online terrain
   prefetch per chunk. Preload packages themselves were not the vehicle: they
   load asynchronously, fall back to online sources while loading, and are
   keyed to a settings fingerprint. The view is cached per settings value
   for the whole JVM, so the sampler looks up `NzTiles.global()` on each
   call (the store is rebuilt at every server start) and rebuilds its sea
   floor cache when the store changes.

   Two height paths bypass the view and needed their own hooks: the ocean
   floor (§11) and Tellus forcing Overture water on (§10). The other online
   reads were switched off in TERR-9 (§10).
4. **Spawn (TERR-9):** `EarthChunkGenerator.java:420`–`:424` and
   `getSurfacePosition` (`:524`) turn spawn lat/lon into blocks; NZ worlds
   by NZTM (§1).
5. **Underground floor (built in TERR-8, option B):** §4.
6. **Lat/lon users** (section 1): redirected or switched off for NZ worlds
   in TERR-9 (§1, §10).
7. **Water (TERR-10):** both water resolvers hand NZ worlds to `NzWater`
   (§11.2). Steps (TERR-14): `EarthChunkGenerator.isWaterfallSourceColumn`
   asks `NzWater.isStepSource`; the NZ mixin `nz/mixin/FlowingFluidMixin`
   stops new sources near steps (§11.2).
8. **Surfaces (TERR-11):** `fillTellusSurface` hands every column to
   `NzSurface` instead of Tellus's surface palette and snow (§12).
9. **Boulders (TERR-14):** `applyBiomeDecoration` calls `NzBoulders.place`
   right after `placeTrees` (§12, "Boulders").
10. **Cave surface (TERR-23):** four hooks in `EarthChunkGenerator` (the
   carve path, `getUndergroundPlacementSurfaceY`, `shouldKeepFeatureId`,
   `geologyFlags`) and one in `EarthBiomeSource.resolveCaveBiome`, all
   calling `NzCaves` (§4, "the cave surface").
11. **DH fast LOD (TERR-15):** `TellusLodGenerator` takes its surface
   blocks from `NzLodSurface`; `EarthChunkGenerator.buildLodShorelineCache`
   and `shouldRefineLodSurfaceShape` skip their work for NZ worlds (§9).

## 7. Data sources: fetch, cache, sample

Each source in `root:world/data/` is a static singleton, created once in
`root:worldgen/TellusWorldgenSources.java:41`–`:51` and shared by every
world in the JVM. Only the water resolver is per world: one per distinct
settings value (`:121`). All sources work the same way:

- **Input is block x/z plus `worldScale`.** The source projects to lat/lon
  with Mercator itself and picks a tile zoom from the wanted resolution
  (`previewResolutionMeters`, which defaults to `worldScale`; DH passes a
  coarser one, §9).
- **Three lookup modes** for each sample method:
  - blocking: memory → disk → download, and it waits;
  - `…LocalOnly`: memory → disk, never the network;
  - `…MemoryOnly`: memory only; a miss returns `NaN` or `Integer.MIN_VALUE`.

  The thread-local `ManagedTerrainNetworkPolicy.cacheOnly()` scope turns
  blocking calls into LocalOnly ones.
- **Two caches.** Decoded tiles live in a Guava LRU in memory, sized by
  system properties; elevation's is `tellus.elevation.cacheTiles` = 512
  tiles of 512² shorts, about 256 MB. The downloaded files stay on disk under
  `<game dir>/tellus/cache/<source>/` until deleted from the cache screen
  (`root:cache/TellusCacheManager.java`). `TellusCacheRegistry` keeps a
  generation counter per `TellusCacheDomain`, so a clear also discards
  downloads that are still running.
- **Prefetch:** before each chunk, `prefetchForChunk`
  (`TellusWorldgenSources.java:246`) queues async downloads one tile around
  it; `prefetchForArea` (`:266`) does the same for DH areas.
  `warmCriticalTerrainInputsForChunk` (`:168`) is a blocking variant, off by
  default (`tellus.prefetch.blockingWarmup`).
- **Elevation sampling** (`elevation/TellusElevationSource.java`):
  Mapterhorn Terrarium WebP tiles, 512², zoom ≤ 18, read bilinearly across
  tile edges (`sampleAtZoom` `:1507`). Missing low-zoom tiles count as 0 m
  (ocean). Below sea level it switches to OpenWaters bathymetry. Results
  are metres, and the caller turns them into Y (§2).

| Source (`world/data/…`) | Data | Fetched from | Disk cache (`tellus/cache/…`) | Used for | Switch |
|---|---|---|---|---|---|
| `elevation/TellusElevationSource` | Mapterhorn DEM, OpenWaters bathymetry | HTTP tiles | `elevation-mapterhorn/`, `elevation-openwaters/` (`z/x/y.webp`) | heights, DEM slope (snow), sea floor | always |
| `elevation/MapterhornCoverageResolutionSource` | where Mapterhorn has fine data | HTTP | `elevation-mapterhorn-coverage/` | zoom choice | always |
| `cover/TellusLandCoverSource` | ESA WorldCover 2021 (10 m COG, HTTP range reads); Overture `land_cover` PMTiles where WorldCover has none | HTTP | `worldcover-2021-v200-range/`, `land-cover-overture/` | the cover class per column (ESA codes 10–100): surface, biome, water candidates, trees | always |
| `mask/TellusLandMaskSource` | land/ocean polygons (PMTiles from a Tellus GitHub release) | HTTP | `map/land-mask/` | ocean detection | always |
| `canopy/TellusCanopyHeightSource` | ETH canopy height 2020 (ArcGIS image service, LERC) | HTTP | `canopy-height-eth-2020-v1/` | tree heights, DH canopy | `custom_trees` |
| `koppen/TellusKoppenSource` | Köppen–Geiger 1 km GeoTIFF, **bundled in the jar** | — | `koppen/` (copy) | biome climate, shore material, trees | always |
| `resolve/TellusResolveSource` | RESOLVE Ecoregions 2017, bundled `.bin.xz` | — | — | tree species (`placeTrees`) | with trees |
| `ocean/OisstOceanClimateSource` | NOAA OISST sea temperature (ERDDAP) | HTTP | `ocean-oisst-v21/` | warm/cold ocean biomes (`EarthBiomeSource.java:472`) | always |
| `osm/TellusOsmWaterSource` | Overture `base/water`: ocean, river lines and areas, lakes, waterfalls | HTTP range (PMTiles) | `map/water/` | all water (§11) | `enable_water` |
| `osm/TellusOsmSandSource` | Overture `base/land`, subtype sand | HTTP range | `map/sand/` | beach sand | always |
| `osm/TellusOsmRoadSource` | Overture `transportation` | HTTP range | `map/roads/` | roads, bridges | `enable_roads` (≤ 15 m/block) |
| `osm/TellusOsmInfrastructureSource` | Overture `base` points: street lights, bins… | HTTP range | `map/infrastructure/` | road furniture, lights | `enable_roads` |
| `osm/TellusOsmBuildingSource` | Overture `buildings` | HTTP range | `map/buildings/` | buildings | `enable_buildings` |

Overture URLs come from `osm/OvertureTileUrls` (the latest release
listing). `osm/OverpassRoadClient` is not called from anywhere.

**File formats Tellus can already read** (input to HUB-2, which designed
its own format instead; reasons in hub `interfaces.md`):

- `elevation/TellusRasterReader`: Tellus's own `TELLUS/RASTER` format.
  It holds int16 rasters only, in chunks, each XZ-compressed with a
  PNG-style filter (none/left/up/average/Paeth). It has a writer and tests,
  but is used only by the normalized elevation cache, which is off by
  default (`tellus.elevation.normalized.enabled`). Byte layers would need
  a new format id.
- `cover/WorldCoverCogSource`: a COG reader with overviews and HTTP range
  reads, but strictly uint8, deflate and no predictor.
- `koppen/TellusKoppenSource`: a small tiled GeoTIFF reader (LZW or
  deflate) for the bundled file.
- PMTiles + MVT vector tiles (`mask/PmTilesReader`, `osm/PmTilesRangeReader`).

None of these is a general GeoTIFF reader. The pipeline's int16 height
with a predictor wouldn't load in any of them unchanged.

## 8. How the chunk generator uses the data

`s262:worldgen/EarthChunkGenerator.java`. `fillFromNoise` calls
`fillTellusSurface` (`:1007`), which works on one chunk:

1. **Prefetch** the area's tiles (§7).
2. **Height grid:** `buildHeightGrid` (`:3635`) samples
   `sampleSurfaceHeight` for **every block** of a 24 × 24 grid: the chunk
   plus 4 blocks on each side (for slope and convexity). There is one sample
   per block, with no coarse step. Grids are cached
   (`tellus.chunkgen.heightGrid.cacheEntries`).
3. **Water:** `resolveChunkWaterData` (`:9981`) gets this chunk's slice of
   a 64 × 64 water region from the water resolver (§11). The resolver
   analyses each region with up to 512 blocks of margin and caches it.
4. **Columns:** `fillExactChunkTerrainColumns` (`:7112`), per column:
   cover class (`sampleCoverClassForExactTerrain` `:5364`), terrain and
   water height (`resolveColumnHeights` `:5473`), the dry-water fix-up
   (§11) and the visual cover class.
5. Shoreline ramp, terrain anomaly repair, buildings.
6. **Metrics and biomes:** `fillChunkTerrainMetricsAndBiomes` (`:7171`)
   computes slope and convexity from the height grid, then
   `EarthBiomeSource.getBiomeAtBlock` uses cover class, water and Köppen.
7. **Block fill:** the shell (§4), the surface palette, water from terrain
   + 1 up to the water surface, and snow.
8. Later stages: `applyBiomeDecoration` places trees (`placeTrees`
   `:4751`; canopy height, Köppen and RESOLVE ecoregion per tree), roads and
   buildings.

### 8.1 Switches (system properties, all at their defaults)

`EarthChunkGenerator.java:246`–`:262`. By default chunks use **blocking**
lookups (`tellus.chunkgen.nonBlockingTerrainInputs=false`), so a chunk
waits for its downloads. A "terrain shell" mode builds the chunk from
memory-only data with gaps filled in, then patches it later
(`terrainRefinementManager`). It needs both
`tellus.chunkgen.memoryOnlyTerrainCriticalPath` and
`tellus.chunkgen.deferTerrainRefinement`, which are off. The same patching
path also runs whenever **Overture water coverage is incomplete**
(`OceanCoverageUnavailableException`): the chunk is built dry first, and
the water arrives on a retry.

### 8.2 Per-world state

The generator holds caches keyed by chunk (height grids, water chunks,
decoration contexts, a "prepared chunk state" reaper). Everything it reads
comes through the static sources or the per-settings water resolver.

### 8.3 Biome source

`root:worldgen/EarthBiomeSource.java` reads data on its own for vanilla
biome queries (`getNoiseBiome` `:199`, used for structures and `/locate`):
cover class, elevation, ocean elevation, Köppen, OISST and the water
resolver (`:77`–`:80`, `:160`). TERR-12 replaces this path for NZ.

It doesn't ask the preload view, so TERR-9 hooked it directly (field `nz`,
the shared `NzTerrainSampler` of the settings, null in other worlds): cover
class from the tiles (`coverClass`, the same values as the generator's
samples), no separate visual cover, ocean depth from the tiles' sea floor
(`elevationMetres`), a fixed sea temperature (`NzOceanClimate`, 14 °C, so
every NZ sea is a lukewarm ocean) instead of OISST, and no badlands
relief. Köppen is still read by Mercator (West Africa; bundled, so
offline).

### 8.4 Where NZ data must come in (complete list)

Every call from the generator into a data source, found by
searching for the `…_SOURCE.` fields (`:166`–`:175`):

| Data | Generator methods (line) | NZ replacement |
|---|---|---|
| Height | `sampleSurfaceHeight` / `…LocalOnly` / `…MemoryOnly` (`:5253`–`:5294`); `useOceanZoom*` (`:5314`–`:5350`); `resolveLodMapterhornLandOverride`, `resolveLodOceanTerrainSurface`, `fallbackLodOceanTerrainSurface` (`:10153`–`:10180`) | **done (TERR-8)**: preload view (§6.3) |
| DEM slope | `sampleDemSlopeDegrees` (`:6051`) | NZ surfaces compute their own slope from the height tiles (TERR-11, §12); Tellus's snow, its only user here, doesn't run in NZ worlds |
| Cover class | `sampleCoverClassFor{Base,Exact}Terrain`, `…ForTerrainShell`, `sampleVisualCoverClassFor…` (`:5361`–`:5406`); `sampleCoverClass`, `sampleVisualCoverClass` (`:10073`–`:10103`); `resolveDryOsmTerrainCoverClass` (`:10228`) | stopgap LCDB → ESA in the preload view (TERR-8); vegetation class (TERR-11/12) |
| Land mask | `isKnownOceanLandMask` (`:10026`) | **done (TERR-8)**: water tile, preload view |
| Köppen | `sampleKoppenCode` (`:3895`) | NZ biome/climate (TERR-12) |
| Canopy, ecoregion | `placeTrees`, `applyPreparedTreePlacement` (`:4816`, `:4952`) | `custom_trees` off (TERR-9); canopy + vegetation class (TERR-13) |
| Sand | `hasOvertureSandAt` (`:6047`) | off for NZ (TERR-9); **done (TERR-11)**: NZ surface palette (§12) |
| Water | `waterResolver.*` (§11) | **done (TERR-10)**: `NzWater`, hooks in both water resolvers (§11) |

Outside the generator: `WaterSurfaceResolver.sampleSurface` (`:3344`,
`:3355`), `sampleCoverClass` (`:3297`), `sampleLandMask` (`:3302`–`:3319`)
and the Overture water query (`:646`, `:1860`); `EarthBiomeSource` (§8.3);
`TellusWorldgenSources.samplePreloadTerrain`; the client preview
(`s262-client:client/preview/TerrainPreview.java`).

Almost all height and cover reads check `samplePreloadedTerrain` first (13
calls in the generator), which is why TERR-8 hooked the preload view
itself instead of each call (§6.3). Every height path goes through the
`sampleSurfaceHeight` family, and so does DH (§9), so that covers chunks
and LODs together. Exceptions: the resolver's ocean floor (§11) and the
DEM slope (`sampleDemSlopeDegrees`, LocalOnly, so it reads only what is
already cached and otherwise has no value; NZ surfaces compute slope from
the height tile, §12).

## 9. Distant Horizons

`root:integration/distant_horizons/DistantHorizonsIntegration.java` picks
one of two generators per level (`shouldUseChunkLodGenerator` `:407`):

- **Chunk-based** (`TellusChunkLodGenerator`, root): used when
  `distant_horizons_render_mode` is `detailed` or DH's own generator mode is
  "internal server". It generates real chunks through
  `EarthChunkGenerator`, so NZ hooks there apply unchanged.
- **Direct ("fast LOD")** (`s262:integration/distant_horizons/TellusLodGenerator.java`):
  used by default (render mode `fast`). It writes DH data columns without
  building chunks:
  - one sample per LOD cell, at the cell centre (cell = 2^detail blocks,
    `buildLod` `:338`);
  - data resolution `lodPreviewResolutionMeters` = `worldScale × cellSize`
    (`:1942`), so the sources read coarser zooms far away;
  - in `fast` mode, detail ≥ 4 (16-block cells) uses the even simpler
    `buildUltraFastLod` (`:1169`); render mode `ultra_fast` uses it at every
    detail.

  Data reaches DH only through generator methods:
  - `resolveLodTerrainSurface` (just `sampleSurfaceHeight(x, z, resolution)`,
    `EarthChunkGenerator.java:10126`);
  - `sampleCoverClass`, `sampleVisualCoverClass`,
    `resolveDryOsmTerrainCoverClass`;
  - `resolveLodWaterColumn` (`:10317`, the fast water path; the detailed
    resolver only at detail ≤ 5 with `distant_horizons_water_resolver`);
  - `resolveLodSurface` / `resolveUltraFastLodSurface` (surface blocks);
  - the shoreline, mountain and shared-terrain caches;
  - canopy, straight from `TellusWorldgenSources.canopyHeight()` (`:4783`).

  The 23 `EarthProjection` calls in `TellusLodGenerator` are **all** in
  the road and building masks (`:2022`–`:3288`), so they go away with
  roads and buildings off.
- **Managed downloads** (`integration/distant_horizons/managed/`, setting
  `tellus_managed_terrain_downloads`, off by default) download whole areas
  ahead of DH, then generate LODs cache-only. Not needed for local tiles.

**NZ worlds (TERR-15, `0.8.3+nz.17`).** Heights, cover and water reach
the fast LOD through the preload view and `NzWater.lodArea` (§6.3, §11),
at the level of the cell: `previewResolutionMeters` = 3 m × cell, so
detail d reads level d (`NzTerrainSampler.levelFor`). On top of that:

- **Surfaces:** `TellusLodGenerator` asks `m262:nz/terrain/NzLodSurface`
  instead of `resolveLodSurface` / `resolveUltraFastLodSurface` (field
  `nzLodSurface`, null in other worlds). Rules in its Javadoc: the
  palette's water floor or first land rule, from the water and LCDB class
  and a Horn slope on the cell's level; filler = the top's own mix (face
  mix on rock and on `bare_step` slopes). Why the filler: DH draws a
  column as one top block over one filler down to the floor, and the side
  of a column above its neighbour shows the filler; with dirt there, every
  forested hillside showed a dirt stripe at each LOD step.
- **Coarser than the tiles:** DH asks for details up to about 11 far
  away (`LodSamplingGrid`, cells of 2,048 blocks). A request coarser
  than a layer's top level reads the top level (`NzTiles.finestLevel`);
  before TERR-15 it found nothing, and the sampler, water and surface all
  answered "sea" (test area 1 has levels 0–7, so it vanished from
  detail 8).
- **Skipped for NZ:** Tellus's dense shoreline cache (detail ≤ 2: water
  per block over the LOD plus a margin, all at level 0; 350 ms cold per
  LOD) and the surface-shape refinement (5 extra height samples per
  column up to detail 6). Only Tellus's LOD surfaces use them.
- **Speed (scratch harness, test area 1, one thread, 64 × 64 columns
  per LOD, cold tile cache):** after these changes 35–65 ms per LOD from
  detail 1 up (detail 0 pays for level-0 streams and ledges once, about
  330 ms, as chunks do). The parts: NZ samples 2–10 ms, NZ water 1–27 ms
  (the coarse level's lakes and rivers are built once per tile, then
  about 1 ms), NZ surfaces 2–6 ms, and **Tellus's LOD biome 20–25 ms**,
  the largest part (Köppen and the biome rules, not tile reads; TERR-12
  replaces it). The harness built `EarthChunkGenerator` and
  `EarthBiomeSource` in a unit test (`VanillaRegistries.createLookup()`
  for biomes, `tellus.gameDir`/`tellus.configDir` pointing at a temp
  folder with `nz-terrain.json`) and called the phases of `buildLod`
  in order. Tellus's own surface rules can't run there (biome tags
  aren't bound). Rebuild it the same way to measure again.
- **Not from the tiles yet:** biome tints (grass, leaves) and the canopy
  (Tellus's `resolveMinecraftCanopyColumn` per biome, where the LCDB
  forest classes map to ESA tree cover) come with TERR-12 and TERR-13.
  The tiles' `canopy` layer exists only at level 0, so a distant canopy
  height would need coarse levels of it.

## 10. Online features switched off for NZ worlds (TERR-9)

All done in TERR-9: NZ world generation makes no network request (checked
with networking blocked, `testing.md`).

| Feature | Online source | How it is off in NZ worlds | Notes |
|---|---|---|---|
| Roads, bridges, street lights, road furniture | Overture transportation + base | `enable_roads: false` | the infrastructure source also needs roads on |
| Buildings | Overture buildings | `enable_buildings: false` | |
| Real-time time of day, weather, historical snow | Open-Meteo | `realtime_time`, `realtime_weather`, `historical_snow` false (defaults) | an op can still turn them on with `/tellus weather enable_realtime_…`, and `/tellus weather` asks on request; both use NZ lat/lon (§1) |
| Elevation and bathymetry | Mapterhorn, OpenWaters, Mapterhorn coverage | preload view (§6.3, TERR-8), ocean floor hook (§11), biome source ocean depth (§8.3) | |
| Land cover | WorldCover COG, Overture land_cover | preload view (TERR-8), biome source hook (§8.3) | stopgap LCDB → ESA mapping |
| Land mask | Tellus land polygons (GitHub) | water tile, preload view (TERR-8) | |
| Water | Overture water | NZ settings force `enable_water` off (§11, TERR-8) | the setting itself does nothing for Earth worlds |
| Sand | Overture land/sand | hook: `hasOvertureSandAt` false, no sand prefetch | Tellus beach sand is gone until TERR-11's surface rules |
| Canopy height | ETH via ArcGIS | `custom_trees: false` in the preset | vanilla tree features per biome until TERR-13 (§6.2) |
| Ocean temperature | NOAA OISST | biome source hook: `NzOceanClimate` (§8.3) | TERR-12 |
| Prefetch around chunks and DH areas | all of the above | hooks in `TellusWorldgenSources`: `prefetchForArea` and `warmCriticalTerrainInputsForChunk` do nothing for NZ; the per-chunk prefetch skips sand (terrain was already skipped by the preload view) | the per-chunk water region prefetch still runs: it is local work |
| Köppen, RESOLVE ecoregions | bundled, offline | — | Mercator lookups read West Africa; replace in TERR-12/13. Köppen copies its bundled file to `tellus/cache/koppen/`, the only file there in an NZ world |
| Preload packages, managed DH downloads | all of the above | `tellus_managed_terrain_downloads: false` (default); the preload screen opens only from the Earth customize screen, which NZ has not | |
| Customize screen map, place search, spawn picker | OSM map tiles, Nominatim | NZ preset gets no Earth editor (§6.2) | client only |
| `/tellus map` (geo-teleport, place search) | OSM map tiles, Nominatim | kept, on request only: NZ lat/lon (§1) | client only |

Settings alone covered roads, buildings, lights, realtime and managed
downloads; Tellus has no "offline" switch for the rest, so it took hooks.
Every fetch goes through a source in §7 (11 classes with their own HTTP
code, no common client), so a new upstream feature that calls a source
directly from the generator or biome source would go online again: the
networking-blocked test in `testing.md` catches that.

## 11. Water

### 11.1 Tellus's water resolvers

`root:worldgen/WaterSurfaceResolver.java` (about 4,000 lines) answers
every water question of the generator and the biome source. It works on
64 × 64-block regions (`buildRegionData`), each analysed with a margin of
192–512 blocks and cached. A region holds per column: terrain (the floor
under water), water surface (Y of the top water block), a flag (none /
inland / ocean / waterfall drop) and the raw DEM surface. The chunk
generator reads regions (`resolveChunkWaterData`); the biome source and
out-of-chunk neighbours read single columns (`resolveColumnData` →
region, `resolveFastColumnData` → `coarseWaterColumnData`). DH has its
own resolver, `root:worldgen/DhLodWaterResolver.java` (`resolveArea`,
about 1,900 lines), on LOD grids.

How Tellus does it, for Earth worlds:

- **`enable_water` is ignored** (found in TERR-8): the settings
  constructor sets `enableWater = true` whatever was saved ("Overture
  vector water is now the only supported source"). About 60 places read
  the flag. Inland water and ocean come **only from Overture shapes**
  (`populateOsmBaseWaterMask`): areas scanline-filled, river centrelines
  drawn **1 block wide**, ocean polygons override everything.
- With the flag off, WorldCover class 80 plus the land mask make the water
  instead. Upstream no longer reaches that path. NZ worlds did until
  TERR-10 (their settings have `enableWater = false`, §10): rivers came
  out as flat reaches with steps and a row of sand across the channel at
  each step (TERR-8 desktop check).
- Then guesses: connected water cells form components; long thin ones are
  rivers (`shouldClassifyInlandComponentAsRiver`); a river's surface
  follows the DEM and `InlandWaterFlowAnalyzer` levels it into reaches
  with waterfalls (only on Overture *area* cells); lakes get one surface
  from the 10th percentile of their border heights; beds are cut 1–6
  blocks by distance to the bank; shores are blended.
- **Ocean floor** (`sampleOceanTerrainHeight`): OpenWaters bathymetry
  (online, by Mercator) and a coast profile. Hooked in TERR-8: NZ worlds
  take the preload sample's height (`NzSeaFloor`), clamped one block below
  the water.
- **TERR-1 finding:** released Tellus left NZ rivers dry because most NZ
  rivers are only centrelines in OSM: a 1-block strip in a LiDAR channel
  tens of blocks wide, the rest drawn as stone.

### 11.2 NZ water (TERR-10)

**Decided (23 Oct 2026):** a separate NZ water pass instead of feeding our
tiles into Tellus's resolver. The resolver's analysis exists to fix rough
global data (polygons that don't fit the DEM, unknown lake heights); our
water layer and LiDAR already agree, and reusing it would have needed many
hooks deep in a file upstream rewrites in most releases.

`m262:nz/terrain/NzWater` answers one column from the tiles; three hooks
hand NZ worlds to it, so none of Tellus's analysis runs:

- `WaterSurfaceResolver.buildRegionData`: the region is `NzWater.region`,
  i.e. `NzWater.column` block by block, at level 0.
- `WaterSurfaceResolver.coarseWaterColumnData`: `NzWater.column` at the
  requested resolution, so neighbours and biome queries get exactly the
  region's values at block resolution.
- `DhLodWaterResolver.resolveArea`: `NzWater.lodArea`, the same column at
  the tile level of the LOD cell size, over DH's own terrain sample.

Flags in `NzWater.Region` use Tellus's private values (none 0, inland 1,
ocean 2); no waterfall flags. Per column:

- **Sea** (water layer sea, or no data): water Y 63 (sea level) over the
  sampler's height (`NzSeaFloor`), at most Y 62.
- **Lake and river** (`m262:nz/terrain/NzInlandWater`, per tile and
  level, with a margin from the neighbouring tiles, 64 tiles cached):
  - Surface: `water_level` → Y of the top water block **rounded to the
    nearest block** (`63 + floor(m / 2 + 0.5)`). Terrain rounds away from
    sea level (ceil on land), so a bank is never below the water beside it
    and estuaries and lagoons 0–1 m high sit at Y 63 like the sea. Water
    level with its bank (both round up) or one block below it.
  - Rivers: the lowest river surface within 2 blocks (a square), so a
    channel is level across and blocks over a bank or bar take the river
    level. In test area 1, 1.9 % of river blocks sit 2+ blocks above the
    river within 2 blocks, 0.3 % 4+ (measured on `water.tif`/`height.tif`),
    so nothing heavier was needed. Downstream, rivers step down, mostly
    one block at a time (steps below).
  - Floor: `bed` where measured, at the same tile level. Otherwise
    invented from the exact distance to the nearest *land* pixel (sea and
    lakes don't count, so mouths and inlets don't shallow): rivers 1 block
    deep, 2 from 7 blocks out, 3 from 13; lakes 1 m deeper per 4 m, 2–40 m.
    Where the LiDAR under a lake is lower (a lake that isn't flattened,
    8 m fill), the LiDAR wins. Always at least 1 block deep. The constants
    are at the top of `NzInlandWater`.
  - Steps (TERR-14 step 1): see below.
- **Streams** (land in the water layer, level 0 only): `NzStreams`,
  below.
- **Land:** no water, terrain = the sampler's height.

**Where a column's height comes from (found in TERR-14 step 2):** only
*wet* columns take their terrain from the water region.
`resolveColumnHeights` gives a dry column the height grid's value, and
the grid comes from the sampler (`buildHeightGrid`, via the preload
hook). So a change to dry land (a stream bank, a gorge wall) must come
in through `NzTerrainSampler.sample`, not only `NzWater.column`; it then
also reaches DH and everything else that asks for heights.

The NZ preset sets **`shoreline_blend_cliff_limit: false`**. With it on
(Tellus's default), `EarthChunkGenerator.applyShorelineBankRamp` cuts any
land within 5 blocks of water that stands 3+ blocks above it down to a
1:1 ramp: it would erase sea cliffs, gorge walls and lake banks from the
LiDAR. The resolvers' own uses of the flag no longer run in NZ worlds.
`river_lake_shoreline_blend`/`ocean_shoreline_blend` (5) still keep
Tellus's trees 5 blocks back from water (`placeTrees`; TERR-13).

**Found in the TERR-10 check (`testing.md`):**

- Every lake, river and sea column was wet with exactly the expected
  surface. When checking saved chunks, count seagrass, kelp and
  waterlogged blocks as water: Tellus plants tall seagrass at the top of
  shallow water.
- LiDAR land below 0 m next to the sea is terrain at Y 62 or lower (rounded
  away from sea level), so the sea floods it to Y 63 as soon as the chunk
  ticks (test area 1: about 2,000 columns at one beach). Not changed: a
  wet beach below sea level is plausible.
- Flowing water spills a little over low ground at the edges of loaded
  areas (vanilla fluid ticks).

**Steps (TERR-14 step 1, `0.8.3+nz.7`).** Before, each step was a 1-block
wall of still water on a 1-block sill level with the lower reach's
surface: a weir ("lip", TERR-10 desktop check). Now:

- **Sill:** `NzInlandWater` makes a lake or river pixel with lower water
  (lake, river or sea) within `SILL_RADIUS_BLOCKS` (2) 1 block deeper,
  before the LiDAR/`bed` floors. Under a 1-block step the bed runs on
  level. **Decided (23 Oct 2026): taller drops are waterfalls**, so the
  sill is only ever 1 block deeper and a drop of k blocks keeps a k−1
  rock ledge for the water to fall over. Test area 1: 97.7 % of step
  edges drop 1 block, 1.6 % 2, 0.7 % 3–12 (`water-surface.tif`).
- **Flow:** a lake or river pixel with lower water on one of its four
  sides is a *step* (a `BitSet` per cached tile). The hook in
  `EarthChunkGenerator.isWaterfallSourceColumn` asks
  `NzWater.isStepSource` and marks the top block for post-processing
  (Tellus's waterfall mechanism; NZ regions have no waterfall flags).
  When the chunk turns full, `LevelChunk.postProcessGeneration` ticks
  that block: it spreads one block of flowing water over the lower reach
  (flowing water on top of water doesn't spread further), or falls down
  a taller drop to the water below.
- **No new sources:** at a step's inside corners the air above the lower
  reach has two source neighbours over source water, so vanilla's
  `getNewLiquid` returns a source; one makes the next, and the reach
  below fills up to the level above (seen headless: 2,571 blocks).
  Tellus's `WaterSourceConversionMixin` doesn't help: it only blocks
  flowing water turning into a source, and `getSpread` also asks
  `getNewLiquid` for **air** blocks. The NZ mixin
  `nz/mixin/FlowingFluidMixin` (config `nz-terrain.mixins.json`) returns
  plain flowing water instead whenever a source would appear within
  `NzWater.NO_NEW_SOURCE_RADIUS_BLOCKS` (2) of a step; 1 already covers
  every block beside one. Infinite sources still work everywhere else.
- **Only ticking chunks flow:** the step blocks of chunks at the edge of
  the loaded area stay still until their neighbours load (vanilla).

Desktop check (23 Oct 2026): steps and waterfalls look good
(`testing.md`).

**Streams (TERR-14 step 2, `0.8.3+nz.8`).** `m262:nz/terrain/NzStreams`,
per level-0 tile with a margin of 34 blocks, 64 tiles cached;
`NzWater.column` asks it for land columns, `NzTerrainSampler.sample` for
the ground of dry ones (above). Rules in its Javadoc; in short:

- Level: the tile layer `stream_level` (pipeline, hub `interfaces.md`),
  rounded to the nearest block like lakes. The pipeline makes it, not the
  generator: telling downstream from class and height is wrong for 22 % of
  stream steps, and carves through filled pits can be longer than a tile.
- Width (option C, decided 23 Oct 2026): 1:1 from the size class
  (`2 m × √km²`, at most 31), spreading from the centreline only over land
  at most 1 block above the centreline's ground (the gully floor). A
  stream spreads evenly to both sides, so widths are odd: 2 m shows as
  1 block, 4 m as 3. Accepted at the desktop check; even widths would need
  a stream to lean to one side.
  Measured on test area 1 before building it: 1:1 widths fit the gully
  floor for 93 % of stream blocks, 1:3 for 99 %; the misfits are the
  big streams (class 76+). Each block takes the nearest centreline's level.
- Corner blocks join diagonal links; land lower than the water beside it
  is raised to a bank; steps (lower water on a side) flow through
  `NzWater.isStepSource`, and the sill rule makes the bed run on under a
  step, as for rivers.
- Constants (width factor, tolerance, depths) are at the top of
  `NzStreams`; tune them after the desktop check.
- Cost: 25–65 ms per region the first time (test area 1).
- Trees: Tellus's tree pass keeps trees `river_lake_shoreline_blend`
  (5) blocks from any water, streams included. In game it shows only as
  a narrow open strip in places (desktop check); TERR-13 replaces the
  tree pass anyway.

**Slot gorges (TERR-14 step 3, `0.8.3+nz.9`–`nz.11`).** In the same
per-tile pass of `NzStreams` (`gorges`), so no new hook: walls are dry columns and
reach the chunk generator through `NzWater.landGround` and the sampler,
like banks. Rules and constants are in its Javadoc; in short:

- **Where (decided 23 Oct 2026: option (a), from the LiDAR's shape):** a
  centreline block whose lower side, across the stream, is 3+ blocks
  higher 3 blocks out and 8+ blocks higher 6 blocks out (the deepest of
  four axes), in an 8-connected run of 5+ such blocks. Test area 1:
  31,198 of 808,749 stream blocks (3.9 %); 878 runs of 10+ blocks, 274 of
  20+, the longest 109. Mostly small creeks (classes 1–50) on steep
  reaches (0.3 % of flat reaches, 8.7 % above 40 % real gradient), in
  forest, scrub, tussock and alpine land; none in pasture. Big streams
  almost never qualify: 3 blocks out is still their own water.
- **Shape (decided: fill up to the rim):** land within 6 blocks of a
  gorge centreline, nearer to it than to any other centreline (its
  *owner*), is raised to the rim: that lower side 6 blocks out, at most
  `wallCap(class)` above the stream's ground, ±2 blocks of smooth noise
  (`rimJitter`, lattice 8 blocks; 3 looked like a fence of columns).
  Walls rise 2 blocks per block from a run's ends (next to a non-gorge
  centreline), so a gorge opens out instead of ending in a cross wall; a
  run that ends in a river keeps its full walls. A column lower than its
  centreline's ground (beside a waterfall) is raised by at most 16.
- **Wall height by size class (decided 24 Oct 2026, after the second
  desktop look):** 4 blocks up to class 30 (16 ha), rising linearly to
  16 at class 76 (1,000 ha). A small creek thus keeps the LiDAR's steep
  V as a wooded gully with a short rock cut at the water; full slots are
  for big streams. This matches photos of South Westland creeks
  (boulder-choked channels in vegetated V gullies, bare rock only near
  the water) and replaced a separate "rock only low on the wall" rule.
- **Ledges:** land within the ledge width (Chebyshev) of the stream's
  water is a ledge instead of wall. The width is `ledgeWidth` (0, 1 or 2
  by smooth noise, lattice 6) evaluated **at the owner centreline**, so
  both sides agree, plus `bendSide`: +1 on the inside of a bend, −1 on
  the outside (the mean offset of the centreline within 4 blocks points
  to the inside; a bend counts from 0.5 blocks). A ledge is at most 1
  block above the highest stream water within its width: the only place
  a gorge lowers land. `NzStreams.isGorgeLedge` → `NzSurface` gives it
  the stream-bed surface. The stream and its bed never change.
- **No pillars:** in `nz.10` each column took the noise at its own
  position, so where the width changed a single wall column stood
  between ledges. Now, besides the owner rule, a *raised* wall column
  with ledge or water on 3 of its 4 sides becomes ledge, judged on the
  ledges before that pass (so the result doesn't depend on the scan
  order, which differs per tile window). Unraised land is left alone.
- **First desktop look (23 Oct 2026, `0.8.3+nz.9`):** right from a
  distance, but up close the 3-wide slot with parallel flat walls looked
  cut by a machine, and soil showed on the walls as dirt bands and 1 × 1
  dirt pillars: the surface rule follows the LiDAR slope (under 63° on a
  V's sides, so `forest_floor`), not the raised heights. Fixed by the
  noise above and the palette's `bare_step` (§12).
- **Second desktop look (23–24 Oct 2026, `0.8.3+nz.10`):** no dirt on
  walls, gravel ledges good; but single stone pillars beside the water,
  spiky wall tops, and small creeks as 1-wide corridors between 15-block
  bare walls. Fixed in `nz.11` (the rules above).
- Vertical walls at most. **Decided (24 Oct 2026): no undercut walls.**
  Photos of South Westland gorges (Callery, Gates of Haast, Copland
  creeks) show overhangs only as small undercuts at the waterline in
  greywacke and schist; not worth a 3D carve.
- Cost: about 20 ms per tile for streams and gorges together, the first
  time (test area 1, 896 tiles).

**Open:** whether 1–3 blocks is deep enough for big rivers, and how lakes
look up close (water in DH's LODs looked right, 23 Oct 2026).

## 12. Surfaces (TERR-11)

How Tellus does it: per column, `fillTellusSurface` fills the shell with
stone, then `applyThinShellSurface` picks a `SurfacePalette` (top,
underwater top, filler, depth) from the biome, the ESA cover class and the
height grid's slope/convexity (`selectFullChunkSurfacePalette`: mountain
rules, ocean beaches, Overture sand, DEM deepslate slopes, badlands),
turns some tops into ore (`ThinShellSurfaceOre`), and then
`applySnowCover` puts snow on snowy biomes and persistent-snow cover.

**NZ worlds:** one hook in `fillTellusSurface` (the per-column loop):
`m262:nz/terrain/NzSurface.forChunk` before the loop (null in other
worlds) and `apply(localX, localZ, surface, underwater, ocean)` in place
of the surface calls; the snow pass is skipped. None of the Tellus
surface rules above run. The shell fill and `resolveMountainMassFillBlock`
(it only ever returns stone) are untouched.

- **Inputs**, from the tiles at level 0: LCDB class (nodata → 0), water
  class, height in metres, and the slope by Horn's 3 × 3 method on those
  heights (`NzTerrainSampler.pixelElevation`, so sea pixels use the
  placeholder sea floor), in **game** degrees: rise per block ×
  `terrestrial_height_scale / world_scale` (0.5 blocks per metre), i.e.
  `tan(game) = 1.5 × tan(real)`. "Underwater" and "ocean" come from the
  generator's water arrays, so they match the water actually placed.
  Water on a column the water layer calls land is a stream: the palette's
  `water.stream` floor (optional, falls back to `water.river`).
- **Column:** top block, the `under` layers, then the `face` material down
  to one above the lowest of the 4 neighbours. On a step of `bare_step`
  (palette, default 3) blocks or more the `under` layers are left out, so
  the face starts under the top block: the surface rule follows the LiDAR
  slope, which a gorge wall or a rounded cliff edge doesn't (TERR-14
  step 3). The neighbours come from the generator's height
  grid, after water floors; the ring just outside the chunk is refilled
  from `NzWater.column`, since the generator copies water floors back only
  for the chunk's own columns), so step and cliff sides show the rock mix;
  then the `cover` block on top on dry land. Never below the shell
  (`surface − underground_depth + 1`) or outside the chunk.
- **Palette** (`m262:nz/terrain/NzSurfacePalette`, `NzSurfaceMix`): the
  file `mc262/src/main/resources/nz-terrain/surface-palette.jsonc` (its
  comments are the format's documentation). Read at `SERVER_STARTING`
  (`NzTerrain.reloadTiles`); `config/nz-terrain-surface.jsonc` wins while it
  exists. Gson's lenient parser allows the comments; the reader itself
  rejects unknown keys, blocks and surfaces, and a land list without a
  catch-all last rule. A broken tuning copy falls back to the jar's
  palette with a warning in the log and chat; a broken jar palette fails
  the tests. Mix picks hash the block position with a salt per place in
  the file, so they don't depend on the world seed or generation order.
- **DH fast LOD (TERR-15):** the same palette, read at the LOD cell's
  tile level, through `NzLodSurface` (§9).
- **Not covered:** `getBaseColumn` still answers stone (only used for
  heights and structures). The dormant refinement rebuild path (§4) has
  no surface hook.
- **Later stages that change NZ tops** (TERR-11 check, `testing.md`):
  - Tellus's tree pass (`placeTrees`, on ESA tree cover) sets the ground
    to grass wherever it tries a tree on a non-dirt block, even when the
    tree then fails: grass blocks on mossy-rock and rock faces in
    forest. Vanilla trees turn the block under their trunk to dirt.
    TERR-13 replaces the tree pass.
  - Ore features (and `ore_gravel`) replace stone, andesite and tuff, so a
    few ore and gravel blocks show at the surface of rock (about 0.1 % of
    columns).

**Cliff ledges (TERR-14 step 5, `0.8.3+nz.14`).** `m262:nz/terrain/NzLedges`,
a per-tile pass like `NzStreams` (rules and constants in its Javadoc).

- **Decided (25 Oct 2026): option (b)**, one height per column: on a
  steep face the 3 m LiDAR gives an even staircase of 2–3-block steps,
  and moving some column tops onto ledge levels gives flat ledges with
  taller rock between them. (a) material only kept the staircase;
  (c) a 3D pass (overhangs) would need an upstream hook and cross-chunk
  edits, and South Westland photos show few overhangs (step 3). Add (c)
  only if the desktop look asks for it. Horizontal stripes are not the
  geology here (foliated schist, blocky greywacke), so the ledges come
  and go and the levels wave and drift, rather than neat strata.
- **Where:** dry columns whose surface (the palette's land rules on the
  tile heights and Horn slope, the same as `NzSurface`, via the shared
  `NzSurface.slopeDegrees`) is listed in the palette's `"ledges"` →
  `"surfaces"`, from `min_slope`. Never within 3 blocks of water or of a
  column the streams change (`NzStreams.markChanged`: water, banks,
  gorge walls and ledges), so no bank loses its water.
- **How it reaches the world:** `NzWater.landGround` (was
  `streamGround`) adds the ledge offset to the sampler's level-0 land
  height, so the chunk generator, the water column, boulders and the
  surface's neighbour grid all see it, with no new upstream hook.
  `NzWater` holds the `NzLedges` instance and rebuilds it when the tiles
  or the palette change (both at every server start). `NzSurface` gives
  treads the palette's tread surface; `NzBoulders` takes the ledges
  (and their streams) so its surfaces match.
- **Numbers** (prototype on the rasters, then the survey): in test area 1,
  4.8 % of land columns are treads and 3.7 % move, by −6 to +6 blocks,
  symmetric (the face's mean height stays). 12 ms per tile, streams
  already computed. The tile is worked in 128-block parts so no window is
  a G1 humongous object (TERR-18 note); a tile's result is a `byte[]`
  of offsets plus a `BitSet`.
- **Order kept (`0.8.3+nz.15`):** each column decides on its own, and
  the reach changes from column to column (cliff top and foot, a soil
  neighbour, the keep-away edge, the width noise). So a column could end
  above its upslope neighbour (a rock fin in front of the soil) or below
  its downslope one (a hollow behind a rim): the first desktop look
  showed both, and on the real tiles 15 % of moved columns did it, 3 %
  by 2–5 blocks. `NzLedges.settle` pulls such a column back towards its
  own height, as far as its 8 neighbours need (neighbours at the same
  height don't count, so a ledge can end in a step along the cliff), in
  up to 8 passes; the part's window has a margin of 4 + 8 so every part
  comes out as if the whole land were worked at once. Afterwards: 0 out
  of order, and 6 % fewer columns move.
- **Palette dependence:** unlike everything else in the palette, the
  `"ledges"` section changes terrain heights. A tuning copy that changes
  it makes new chunks meet old ones with a seam on cliffs.

**Lessons from TERR-14 (streams, gorges, boulders, ledges), for any new
terrain detail** (glaciers, TERR-22, will need them):

- **Heights of land go through a per-tile pass** that the sampler reads
  via `NzWater.landGround` (`NzStreams`, `NzLedges`): every reader (the
  chunk generator, water, boulders, the surface's neighbour grid) then
  agrees, with no new upstream hook. Work tiles in parts with a margin
  wide enough that each part comes out as if the whole land were done
  at once (no seams at part or tile edges).
- **The surface rule follows the LiDAR slope, not the block heights**,
  so anything that changes heights must set its own surface (gorge
  walls, boulders, ledge treads), or it inherits the soil around it.
- **Neighbouring columns must agree.** Per-column noise in a width made
  lone pillars (gorges: take the width from the owning centreline);
  per-column reach made fins and hollows (ledges: keep the height order
  of the tiles, `NzLedges.settle`).
- **New detail needs a material that contrasts a little** with its
  surroundings: mossy boulders vanished on mossy walls, schist ones on
  schist scree (TERR-14 step 4).
- **Order of work that paid off:** measure a rule on the rasters in
  Python first (pipeline venv; its counts matched the generator's
  closely), survey test area 1 with a throwaway JUnit test, compare a
  headless world's saved chunks with expected columns, then a desktop
  check with named spots and F3 screenshots (`testing.md`). Photos of
  real South Westland places settled shape questions faster than
  guessing.

**Boulders (TERR-14 step 4, `0.8.3+nz.12`).** `m262:nz/terrain/NzBoulders`,
called once per chunk from `applyBiomeDecoration` right after
`placeTrees` (hook 9, §6). Rules and constants are in its Javadoc; in
short:

- **Decided (24 Oct 2026): option (a)**, a decoration pass of its own.
  Vanilla features through NZ biomes would wait for TERR-12 and can only
  vary density per biome; bumps in the column fill can't be round or
  stand in water.
- **Rules** come from the surface palette's `"boulders"` list (first
  match wins, as `"land"`): surface name (under water the floor's:
  `stream_bed`, `riverbed`), game slope, height, and `below_cliff`
  (ground rising 8+ blocks within 4, a square: about 63°, which includes
  the foot of a gorge wall). Each gives a chance per column, weighted
  sizes, a body material and a top for columns above water.
- **Looks (decided 24 Oct 2026, after the first desktop look,
  `nz.13`):** mossy on the forest floor; in bush creeks and gorges plain
  schist with a moss cap (washed below flood level, and mossy boulders
  vanished against the mossy gorge walls); above the bush the darker
  greywacke mix (weathered and lichen-grown, and schist boulders blended
  into schist scree); riverbeds and moraine schist (looked right). The column's surface comes from `NzSurface.columns`, the same
  pick as the fill, gorge ledges included; the ground and water from
  `NzWater.column`.
- **Shape:** a dome with a flattish top, as wide as the size (stretched
  up to 20 % along one axis, corners cut from size 3), 0.5–0.8 times as
  high, ±0.4 blocks per column; it stands on the lowest ground under it.
- **Left out:** a boulder not fully inside its chunk (so the chunk's own
  trees, placed first, are the only ones it can meet, and a later
  chunk's tree pass never anchors a tree on it); ground varying by more
  than 2 blocks under it; anything solid in its space (a log, leaves, an
  earlier boulder in the same chunk; the scan order is fixed, so the same
  one wins every time); a non-solid block at its ground (a cave mouth, a
  lava pool). Plants in its space are replaced; a plant left on top
  (the upper half of a tall plant) is removed.
- **Unit tests can't use block tags:** `BlockTags.LOGS` is empty until
  a world loads its data, so a tag check passes in tests by accident.
  "Anything solid" needs no tags.
- Test area 1 (every 4th chunk each way): 14,697 boulders in 57,344
  chunks at the first densities, about 1 per 1,000 columns; most on
  forest floor, scree, creek beds, below cliffs and on moraine. The size-4 share comes out at about half its
  weight (more of them miss their chunk or stand on uneven ground). Cost
  1.3 ms per chunk, cold caches included.
