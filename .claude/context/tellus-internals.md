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

**Proposal:** leave `EarthProjection` alone. Our tiles are already in block
coordinates (hub `interfaces.md`), so NZ worlds read them by block x/z and
the terrain path never needs lat/lon. Any Tellus code that still projects
with Mercator in an NZ world reads the wrong place on Earth: our blocks
x ±170k, z ±241k at world scale 3 are about ±4.6° lon and ±6.5° lat around
0°N 0°E, i.e. the Gulf of Guinea and West Africa (tropical Köppen, warm
ocean). So it must be switched off or redirected to our data. §10 lists
those; TERR-9 switches them off. Lat/lon is only needed where a person types or sees it: the spawn
setting, `/tellus` location text and geo-teleport (`root:TellusCommon.java`
`:260`, `:616`), and the customize screen's map. Either store the spawn as
block coordinates or add an NZTM ↔ lat/lon conversion (transverse Mercator
on GRS80) in NZ code.

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
block holds everywhere, unlike Mercator. The rounding (away from sea level)
is still to be decided in HUB-2 / TERR-8.

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

So TERR-8's planned "patch the hard-coded lava level (Y −54)" is not needed:
it already follows the shell, and ores already go below −64 wherever the
shell does. One side effect: when the shell is deeper than vanilla's
column, caves get stretched vertically. Example: ground at Y 113 with depth
512 → a 385-block shell for a 177-block vanilla column → caves 2.2× taller.

There is a dormant full-fill path in the generator (`!thinShellTerrain` at
`EarthChunkGenerator.java:1283`, `fillSolidSections`, deepslate from
`minY + 64` at `:1048`, bedrock at `minY` at `:1096`). Upstream no longer
uses it, so it is untested.

### Options for TERR-8 (raise first)

| Option | How | For | Against |
|---|---|---|---|
| **A. Shell only** | Set `underground_depth` (e.g. 335 = 63 − (−272), so ground at sea level reaches the floor). No code. | Zero code, no merge risk. | Under hills and mountains the world ends at bedrock with air below, far above −272. Contradicts "floor at −272" in the hub. |
| **B. Shell + solid fill (recommended)** | Keep caves and ores in the shell; below the shell, fill plain stone/deepslate down to bedrock at −272 instead of air. One small hook at the shell bottom. | Floor at −272 everywhere, as the hub says. Caves stay vanilla-like near the surface. All-stone chunk sections are cheap (single-block palette). | More blocks per chunk under mountains (some generation time). Deep mountain cores are plain stone, with no caves. |
| **C. Absolute vanilla underground** | Caves and ores by absolute Y down to −272, like vanilla with a lower floor. | Closest to "vanilla underground". | Fights Tellus's whole surface-relative cave/ore design; large, conflict-prone changes. |

With A or B, `underground_depth` still sets how deep the caves go and how
much they stretch; choose it in TERR-8.

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
   from spawn lat/lon via Mercator (`:420`–`:424`).

The settings record is the only per-world object most code receives: data
sources are static singletons (`root:worldgen/TellusWorldgenSources.java:41`),
and per-world helpers are looked up by settings (as map keys, so equality
matters).

## 6. Proposed hook points

1. **Mark a world as NZ (decide in TERR-6).** Everything gets
   `EarthGeneratorSettings`, so the NZ flag/settings must ride on it.
   - a. Add one record component (e.g. `nz`) and update every constructor
     call: 21 in `EarthGeneratorSettings.java`, 1 in
     `EarthCustomizeScreen.java`, plus one in `shared/pre-26` that we don't
     edit (that target breaks; accepted). Every upstream settings change then
     conflicts with ours on those lines.
   - b. **Recommended:** the same component, plus a constructor with the old
     parameter list that passes "not NZ". Upstream's 21 calls stay
     untouched. Trap: the `with…` methods would silently drop the NZ value,
     so decode the NZ key **last** in the codec. Checked: no code outside
     the codec calls the `with…` methods.
   - c. A separate NZ generator type subclassing `EarthChunkGenerator`
     doesn't help: the key methods are private, and the sources and
     resolvers only see settings.
2. **NZ world preset:** a new preset JSON plus the `normal` tag, with the
   values from section 2, `min_altitude` −272, `max_altitude` 2031, and roads,
   buildings and realtime off. `PresetEditorMixin` decides which preset gets
   which editor. The Earth customize screen (Mercator map, spawn picker,
   scale sliders) would show wrong things for NZ: give the NZ preset no
   editor, or a small one of its own.
3. **Heights (TERR-8):** wherever elevation becomes Y (section 2 table),
   chiefly the generator's `sampleSurfaceHeight` family
   (`EarthChunkGenerator.java:5253`–`:5294`: blocking, local-only and
   memory-only variants), its resolved/ocean variants (`:10021`–`:10184`),
   and `WaterSurfaceResolver.sampleSurface` (`:3355`). Tellus's own
   **preload package** override already sits at exactly these places
   (`samplePreloadedTerrain`: a ready Y by block x/z, else the online
   source), so an NZ hook goes right next to it. The preload package itself
   is not the right vehicle: it loads asynchronously and falls back to
   online sources while loading, and it is keyed to a settings fingerprint.
   TERR-5 confirmed the full list of call sites, including DH: §8.4.
4. **Spawn:** `EarthChunkGenerator.java:420`–`:424` and
   `getSurfacePosition` (`:524`) turn spawn lat/lon into blocks by Mercator.
   NZ worlds need NZTM or block coordinates here.
5. **Underground floor (TERR-8, option B):** the shell bottom,
   `TerrainShellBedrockProtection.supportBottomY` and the column fill at
   `EarthChunkGenerator.java:1317`–`:1334`.
6. **Lat/lon users** (section 1): switch off or redirect for NZ worlds
   (list: §10; TERR-9 acts).

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

**File formats Tellus can already read** (for HUB-2):

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

### 8.4 Where NZ data must come in (complete list)

Every call from the generator into a data source, found by
searching for the `…_SOURCE.` fields (`:166`–`:175`):

| Data | Generator methods (line) | NZ replacement |
|---|---|---|
| Height | `sampleSurfaceHeight` / `…LocalOnly` / `…MemoryOnly` (`:5253`–`:5294`); `useOceanZoom*` (`:5314`–`:5350`); `resolveLodMapterhornLandOverride`, `resolveLodOceanTerrainSurface`, `fallbackLodOceanTerrainSurface` (`:10153`–`:10180`) | height tile (TERR-8) |
| DEM slope | `sampleDemSlopeDegrees` (`:6051`) | slope tile |
| Cover class | `sampleCoverClassFor{Base,Exact}Terrain`, `…ForTerrainShell`, `sampleVisualCoverClassFor…` (`:5361`–`:5406`); `sampleCoverClass`, `sampleVisualCoverClass` (`:10073`–`:10103`); `resolveDryOsmTerrainCoverClass` (`:10228`) | vegetation class (TERR-11/12) |
| Land mask | `isKnownOceanLandMask` (`:10026`) | water tile (sea) |
| Köppen | `sampleKoppenCode` (`:3895`) | NZ biome/climate (TERR-12) |
| Canopy, ecoregion | `placeTrees`, `applyPreparedTreePlacement` (`:4816`, `:4952`) | canopy + vegetation class (TERR-13), or `custom_trees` off |
| Sand | `hasOvertureSandAt` (`:6047`) | surface palette (TERR-11) |
| Water | `waterResolver.*` (§11) | water tile (TERR-10) |

Outside the generator: `WaterSurfaceResolver.sampleSurface` (`:3344`,
`:3355`), `sampleCoverClass` (`:3297`), `sampleLandMask` (`:3302`–`:3319`)
and the Overture water query (`:646`, `:1860`); `EarthBiomeSource` (§8.3);
`TellusWorldgenSources.samplePreloadTerrain`; the client preview
(`s262-client:client/preview/TerrainPreview.java`).

Almost all height and cover reads already check `samplePreloadedTerrain`
first (13 calls in the generator), so an NZ hook "if NZ world, read the NZ
tile" fits the same spots. Every height path goes through the
`sampleSurfaceHeight` family, and so does DH (§9). A hook there therefore
covers chunks and LODs together.

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

For TERR-15: once the `sampleSurfaceHeight` family and the cover-class
methods read our tiles, fast LOD shows NZ terrain with no DH-specific
code. What's left is speed: a far LOD cell asks for a coarse resolution, so
our tiles want a coarse pyramid or overviews instead of reading every 3 m
pixel. That is an input to HUB-2.

## 10. Online features to switch off for NZ worlds (TERR-9)

| Feature | Online source | How to switch it off | Notes |
|---|---|---|---|
| Roads, bridges, street lights, road furniture | Overture transportation + base | `enable_roads: false` | the infrastructure source also needs roads on |
| Buildings | Overture buildings | `enable_buildings: false` | |
| Real-time time of day | Open-Meteo (time zone) | `realtime_time: false` (default) | `/tellus` command overrides exist (`TellusCommon.java:110`–`:150`) |
| Real-time weather | Open-Meteo | `realtime_weather: false` (default) | |
| Historical snow | Open-Meteo past days | `historical_snow: false` (default) | |
| Elevation and bathymetry | Mapterhorn, OpenWaters, Mapterhorn coverage | **no setting**: NZ height hook (TERR-8) | `dem_*` only picks providers |
| Land cover | WorldCover COG, Overture land_cover | **no setting**: NZ cover hook (TERR-11/12) | |
| Land mask | Tellus land polygons (GitHub) | **no setting**: NZ water tile | downloaded once, but still Mercator |
| Water | Overture water | `enable_water: false` turns off Overture water, but then WorldCover class 80 becomes water instead (§11); NZ needs a water hook (TERR-10) | |
| Sand | Overture land/sand | **no setting**: hook or ignore for NZ | |
| Canopy height | ETH via ArcGIS | `custom_trees: false`, or hook (TERR-13) | also feeds DH canopy |
| Ocean temperature | NOAA OISST | **no setting**: NZ biome source (TERR-12) | |
| Köppen, RESOLVE ecoregions | bundled, offline | — | Mercator lookups read West Africa in an NZ world; replace in TERR-12/13 |
| Preload packages, managed DH downloads | all of the above | `tellus_managed_terrain_downloads: false` (default); don't offer the preload screen | |
| Customize screen map, place search, spawn picker | OSM map tiles (`root-client:client/widget/map/SlippyMapTileCache.java`), Nominatim (`root:world/data/source/NominatimGeocoder.java`) | NZ preset gets no Earth editor (§6.2) | client only |
| `/tellus` geo-teleport and location text | Mercator | NZ version or off (§1) | |

So settings alone switch off roads, buildings, lights, realtime and managed
downloads. Elevation, cover, land mask, sand, OISST and water need NZ
hooks, because Tellus has no "offline" switch for them. All of their
fetches go through the sources in §7, so once NZ worlds stop calling them
(§8.4), nothing else downloads. For a safety net, a test can run the dev
server with networking blocked and check the cache folder stays empty.

## 11. Water, and why rivers stayed dry in TERR-1

`root:worldgen/WaterSurfaceResolver.java` (about 4,000 lines). It works
on 64 × 64-block regions, each analysed with a margin of 192–512 blocks
and cached (`buildRegionData` `:785`).

**Where water comes from:**

- With `enable_water` on (the default), inland water and ocean come **only
  from Overture water shapes** (`populateOsmBaseWaterMask` `:1841`; the
  `osmWaterEnabled` flag is just `enableWater`, `:147`).
  - Areas are scanline-filled.
  - Centrelines (rivers and streams mapped as lines) are drawn **1 block
    wide** (`halfWidth = 0.5`, `:2060`), with small gaps bridged.
  - Ocean polygons override everything.
  - WorldCover's water class is then *not* water.
- With `enable_water` off, WorldCover class 80 plus the land mask make the
  water instead (`:872`).

**River surfaces:**

- Connected water cells form components. Long thin components, or
  components mostly made of flowing lines, are rivers
  (`shouldClassifyInlandComponentAsRiver` `:1746`).
- A river's water surface **follows the DEM** cell by cell
  (`fillComponentSurfaceFromDem`). `InlandWaterFlowAnalyzer` then levels it
  into flat reaches with waterfalls.
- A river cell is never allowed above its own terrain
  (`shouldRejectRiverWaterCell`: water > terrain → lowered or dropped,
  `:1682`, `:2671`).
- The bed is cut 1–6 blocks below the water by distance from the bank
  (`computeRiverDepth` `:1784`, `tellus.water.riverMaxDepth`). Centreline
  cells get water at the DEM height with the bed 1 block below (`:1268`).
- Lakes get one surface from the 10th percentile of their border heights.

**Dry water class:** a column whose cover class is WorldCover water (80)
but which got no water becomes the nearest land class, or else bare (60)
(`resolveDryOsmTerrainCoverClass`, `EarthChunkGenerator.java:10219`). Bare
counts as "mountain rocky cover" (`MountainSurfaceRules.java:118`), so it
is drawn as **stone**.

**TERR-1 finding (from the code; not checked in game):**

- The "carved channels" are the real riverbeds in Mapterhorn's LiDAR DEM;
  Tellus didn't dig them.
- They stay dry where Overture has no water *area* over them. Most NZ
  rivers in OSM are only centrelines, which give a 1-block strip of water
  in a channel tens of blocks wide. That strip sits wherever the OSM line
  is, which may not match the LiDAR channel, and it vanishes in coarse DH
  LODs.
- The rest of the channel is WorldCover water or bare gravel, and both are
  drawn as stone.
- Less likely: Overture coverage was incomplete when those chunks were
  made, so they were built dry and patched later (§8.1).

To confirm, look at one riverbed with the F3 screen or with
`-Dtellus.debugWater=true`.

**For NZ (TERR-10):**

- Our water layer (sea / lake / river, with one surface height per lake)
  replaces the Overture shapes.
- The resolver's later stages could be reused, by filling its masks from
  our tile instead of Overture in `populateOsmBaseWaterMask` and the ocean
  field: components, river surfaces from the DEM, bed depth, shore blending
  and waterfalls.
- Our lake surfaces should replace the percentile guess.
- River surfaces should come from our LiDAR-derived river mask, so the
  whole channel is wet, not just a 1-block line.
- Decide in TERR-10 whether to reuse the resolver this way or write a
  simpler NZ water pass.
