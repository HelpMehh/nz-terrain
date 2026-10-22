# Tellus internals

How Tellus turns block coordinates into terrain, and where NZ code plugs in.
Checked against upstream `0.8.3` (commit `8c19ed8`) in TERR-4. Line numbers
drift after upstream merges: search for the method name if one is off.

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
with Mercator in an NZ world reads the wrong place on Earth (Mercator at
3 m/block puts NZ about 6.4 million blocks east of 0,0), so it must be
switched off or redirected to our data. TERR-5 lists those; TERR-9 switches
them off. Lat/lon is only needed where a person types or sees it: the spawn
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
   TERR-5 confirms the full list of call sites, including DH.
4. **Spawn:** `EarthChunkGenerator.java:420`–`:424` and
   `getSurfacePosition` (`:524`) turn spawn lat/lon into blocks by Mercator.
   NZ worlds need NZTM or block coordinates here.
5. **Underground floor (TERR-8, option B):** the shell bottom,
   `TerrainShellBedrockProtection.supportBottomY` and the column fill at
   `EarthChunkGenerator.java:1317`–`:1334`.
6. **Lat/lon users** (section 1): switch off or redirect for NZ worlds
   (TERR-5 lists them, TERR-9 acts).
