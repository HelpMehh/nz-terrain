# Steps

One step = one conversation ("Do step TERR-4"). Order across all repos, and
what blocks what: in the `mc-nz` workspace, `../.claude/context/steps.md`.
Tick `[x]` here and there when a step is done. Later steps are coarser; refine
them when they come up.

### [x] TERR-1 — Scale check (desktop, no code)

- **Goal:** confirm that the chosen scale (hub `CLAUDE.md`: 1:3 horizontal,
  1:2 vertical, sea level Y 63, world Y −272 to 2,031) looks right and
  runs well, before every repo builds on it.
- **Do:**
  - Use a launcher instance with Fabric 26.2, the released Tellus jar,
    Distant Horizons and Sodium.
  - Make a world at 3 m/block horizontal and 2 m/block vertical. Claude
    helps find the matching Tellus settings, and whether Tellus can set the
    world height we want. If it can't, test with its default height and
    note what TERR-8 must change.
  - Visit the Remarkables, the Canterbury Plains and Milford Sound, and take
    screenshots. Does it look dramatic, and still believable on the plains?
  - Distant Horizons: Tellus releases ship their own DH build
    (`…-tellus-height…`) next to the mod, without saying why. Test the stock
    DH 3.3.x from Modrinth first. If tall peaks (Y 1,500+) render wrong in
    the distance, try the Tellus DH build. Record which one works, because
    the pack needs to know.
  - Try DH at 256, 512 and 1,024 chunks: frame rate, how long the distance
    takes to fill in, and how big the DH database and world folder get.
- **Done when:** results are in `testing.md`; HUB-1 records them in the
  hub (and changes the scale only if it doesn't work).

### [x] TERR-2 — Build check

- **Goal:** the dev setup works before any code changes.
- **Done (22 Oct 2026):** the build passes (600 tests), and the dev server
  and dev client start; the client generates an Earth world. GitHub Actions
  are now off on the fork. Times and expected log noise are in `testing.md`.
- **Learned:** the client can't run in a sandbox without a display. Use
  `runServer262` there for a headless check, and run the client on the
  desktop.

### [x] TERR-3 — Surface palette from data

- **Goal:** settle the look of test area 1 before writing generator code.
- **Done (22 Oct 2026):** the rules come from measurements of the
  test-area data: land-cover class as the base; slope splits gravel flats,
  scree and rock; steep vegetation becomes rock; snow vs glacier ice by
  height. The block mixes are a first guess. All of it is in
  `surface-palette.md`.
- **Changed from the plan:** the WorldPainter prototype was dropped.
  Painting by hand was very slow, it can't show modded blocks or biome
  colours, and changing a block means repainting. The palette is tuned in
  game in TERR-11 instead, where a change is an edit plus fresh chunks.

### [x] TERR-4 — Study Tellus: coordinates and scales (read-only)

- **Goal:** know exactly where to plug in NZTM.
- **Done (22 Oct 2026):** everything is in `tellus-internals.md`. Main
  findings:
  - There is no single projection hook (about 40 files use Mercator), but
    the data sources take block x/z, so NZ worlds can read our block-aligned
    tiles directly and skip lat/lon in the terrain path.
  - The NZ height formula `y = 63 + elevation / 2` needs no code: Tellus's
    own transform gives it with world scale 3.0, height scales 1.5,
    offset 63 and automatic height scaling off.
  - Tellus builds only a rock shell under the surface with air below, and
    puts caves, lava, ores and deepslate relative to the surface. This
    changes TERR-8 (see its note).
  - NZ settings must ride on `EarthGeneratorSettings`; options for TERR-6
    are in `tellus-internals.md` §6.

### [x] TERR-5 — Study Tellus: data, generator, DH (read-only)

- **Goal:** know where a local tile source fits and what to switch off.
- **Done (22 Oct 2026):** everything is in `tellus-internals.md` §7–11.
  Main findings:
  - Every source is a static singleton that takes block x/z, projects
    with Mercator itself, and has blocking / local-only / memory-only
    lookups over a memory LRU and a disk cache. The complete list of
    generator call sites for NZ hooks is in §8.4.
  - DH's default fast LOD reads heights only through the generator's
    `sampleSurfaceHeight` family, at a coarser resolution. So one height
    hook covers chunks and LODs. Its own `EarthProjection` calls are all
    roads and buildings.
  - Settings switch off roads, buildings, lights, realtime time/weather
    and managed downloads. Elevation, land cover, land mask, sand, OISST
    and water have no off switch and need NZ hooks (§10).
  - Dry rivers in TERR-1 (from the code, not checked in game): water comes
    only from Overture shapes; centreline rivers become a 1-block strip,
    and the rest of the LiDAR channel is WorldCover water or bare gravel,
    drawn as stone (§11).
  - Tellus's own readable formats (for HUB-2): `TELLUS/RASTER` (int16,
    XZ), a uint8-only COG reader, PMTiles (§7).

### [x] TERR-6 — NZ skeleton

- **Goal:** a place for NZ code, and the naming decisions made.
- **Do:**
  - Decide the mod ID and jar name (keep `tellus` or rename, so it can sit
    next to real Tellus) and the NZ package name.
  - Decide the version scheme (e.g. `0.8.3+nz.1`) and the changelog file
    name.
  - Create the package in `mc262/src/` with an NZ world option that does
    nothing yet.
  - Record the decisions in `CLAUDE.md`.
- **Done when:** build passes, the option shows up in-game.
- **Done (22 Oct 2026), version `0.8.3+nz.1`:**
  - Decisions in `CLAUDE.md` "Naming and versions": mod ID stays `tellus`
    (the jar replaces Tellus), jar `nz-terrain-…`, package
    `com.yucareux.tellus.nz`, version `<Tellus>+nz.<N>`, `CHANGELOG-NZ.md`.
  - `NzWorldSettings` (the `nz` key on the generator settings) and the
    "New Zealand" preset `tellus:nz`. How, and the traps:
    `tellus-internals.md` §6.
  - Build passes (604 tests). A dedicated server created and reopened a
    world from the preset with `nz` saved, and on the desktop client "New
    Zealand" shows in World Type and generates (`testing.md`).
- **Needs:** TERR-4, TERR-5.

### [x] TERR-7 — Local tile source

- **Do:**
  - A reader for the pipeline tiles (format: hub `interfaces.md`), with a
    unit test against the pipeline's fixture.
  - The data folder (default `<game dir>/nz-tiles/`, overridable in a
    config file named here) and a cache of decoded tiles.
  - Manifest checks: refuse unknown format versions and a different
    E0/N0; merge tile sets by priority; save the data versions in the
    world and warn when they change.
  - The data versions saved in the world go into `NzWorldSettings`
    (`tellus-internals.md` §6.1), not into Tellus's settings.
- **Needs:** TERR-6, PIPE-7.
- **Done (22 Oct 2026), version `0.8.3+nz.2`:**
  - Package `com.yucareux.tellus.nz.tiles`: `NzTileReader` (one layer of
    one file), `NzTileSet` (manifest checks), `NzTiles` (all sets merged by
    priority, Guava LRU of decoded layers, `layer(…)` and `sample(…)` by
    level and block). Config `config/nz-terrain.json` (`tiles_dir`,
    `tile_cache_mb`, default 256), written on first start. The store is
    rebuilt at every server start.
  - A broken tile is logged once and treated as missing; refused sets are
    logged with the reason.
  - Data versions: `tellus-internals.md` §6.1. `NzTerrain` warns at
    server start (log, chat for admins) when tiles differ or are missing.
  - The pipeline fixture is copied to `mc262/src/test/resources/
    nz-tiles-fixture/`; refresh it when the pipeline's fixture changes.
  - **Not done, left for TERR-8:** refusing an NZ world without tiles (it
    only warns), and sampling the finest available level with bilinear
    heights (hub `interfaces.md`); `sample` reads one given level, nearest
    pixel.

### [x] TERR-8 — NZ heights

- **Goal:** NZ worlds take their terrain from the tiles, at the hub scale
  and world height, solid down to the floor.
- **Decided (22 Oct 2026):**
  - Underground: **option B**, shell plus solid fill: Tellus's caves and
    ores stay in the shell, deepslate fills below it, bedrock only at
    Y −272. `underground_depth` stays **256**. The deep zone is meant for
    huge explorable caverns later: TERR-20.
  - Rounding: **Tellus's**, away from sea level (`ceil` above 0 m, `floor`
    below), so the coast is always at least one block above the sea.
- **Done (22 Oct 2026), version `0.8.3+nz.3`:**
  - `com.yucareux.tellus.nz.terrain`: `NzTerrainSampler` answers Tellus's
    preload view with tile height, cover class and land mask, so one hook
    covers the generator, the water resolver and DH; `NzSeaFloor`, a
    placeholder sea floor; `NzCoverClasses`, a stopgap LCDB → ESA mapping;
    `NzUnderground`, the fill. How it all works: `tellus-internals.md`
    §4, §6.3.
  - `NzTiles.finestLevel` and `pixel`: the finest available level,
    bilinear heights between pixel centres (level 0 = the block's own
    pixel).
  - Preset `tellus:nz` has its values and its own dimension type
    `tellus:nz` (Y −272..2031), so the height is right from the first
    start. Caves, ores, lava pools and geodes are on (Tellus has them off
    by default); roads, buildings, realtime and structures off.
  - Spawn: Sentinel Rock, Franz Josef (block −76040, 93845), set as the
    Mercator lat/lon that lands there. Temporary, until NZ spawn handling
    (TERR-9).
  - Refusal without tiles: the Create button (client mixin) and a
    dedicated server's start. An existing single-player world only warns.
  - Checked: 18,236 land columns against `height.tif`, all exact; Aoraki
    Y 1,922; reopened world loads (`testing.md`).
- **Not done:** DH and the desktop client (the desktop check in
  `testing.md`); the "terrain refinement" rebuild path in
  `EarthChunkGenerator` has no fill hook (unreachable in NZ worlds,
  `tellus-internals.md` §4).
- **Needs:** TERR-7.

### [x] TERR-9 — Switch off online features for NZ worlds

- **Goal:** NZ worlds generate offline, with no roads or buildings.
- **Decided (23 Oct 2026):**
  - `/tellus map`, `/tellus weather` and the realtime overrides are made
    **correct for NZ** (real NZ lat/lon by NZTM), not switched off. They
    stay online, on request only; generation never uses them.
  - Spawn: the preset keeps Tellus's `spawn_latitude`/`spawn_longitude`,
    now real lat/lon converted by NZTM, instead of a new block setting.
  - Canopy: `custom_trees: false` (vanilla trees until TERR-13) rather
    than a hook.
- **Done (23 Oct 2026), version `0.8.3+nz.4`:**
  - `com.yucareux.tellus.nz.NzProjection`: block ↔ NZTM ↔ lat/lon, with
    hooks for the spawn, `getSurfacePosition` (geo-teleport) and the
    lat/lon callers in `/tellus` and the realtime manager
    (`tellus-internals.md` §1).
  - The biome source reads cover and sea depth from the tiles, a fixed
    sea temperature (`NzOceanClimate`) instead of OISST, and no badlands
    relief (§8.3). Overture sand and all prefetching are off for NZ
    worlds (§10).
  - Checked: online server run leaves only `koppen/` (a copy of a bundled
    file) in `tellus/cache/`; the same with networking blocked; client on
    a virtual display: spawn, `/tellus weather` (Westland), `/tellus map`
    and a teleport to Fox Glacier (`testing.md`).
- **Not done:** the DH-area prefetch hook is untested (no DH in the dev
  client): check `tellus/cache/` on the desktop with DH. Köppen and
  RESOLVE still read West Africa by Mercator, offline (TERR-12/13).
- **Needs:** TERR-8.

### [x] TERR-10 — Water

- **Do:** sea, lakes at their real surface height, and rivers, all from the
  water layer. Whole river channels must be wet, not a 1-block line
  (TERR-1, `tellus-internals.md` §11).
- **Decided (23 Oct 2026):** a separate NZ water pass, not Tellus's
  resolver fed with our tiles: the resolver's analysis fixes problems our
  data doesn't have, and reusing it needed many hooks deep in a file
  upstream rewrites often (§11.2). Water surfaces round to the nearest
  block; the preset turns `shoreline_blend_cliff_limit` off so LiDAR banks
  and cliffs beside water stay.
- **Done (23 Oct 2026), version `0.8.3+nz.5`:**
  - `com.yucareux.tellus.nz.terrain.NzWater` answers each column from the
    tiles; hooks in `WaterSurfaceResolver` (regions, coarse columns) and
    `DhLodWaterResolver` (LOD areas) hand NZ worlds to it.
    `NzInlandWater` computes lake and river surfaces and floors per tile
    and level: rivers level across and stepping downstream, invented
    floors by distance to land, LiDAR or `bed` where deeper/measured.
  - Checked: every lake, river and sea column in five test areas wet at
    exactly the expected surface (`testing.md`).
- **Desktop check (23 Oct 2026):** rivers look much better, but every
  step still has a lip (a water wall on a sill; `testing.md`), to fix in
  TERR-14. Water in DH's LODs looks right.
- **Not done:** the desktop check of lakes up close. No waterfall
  handling for multi-block drops; `bed` is
  only read at the same tile level (coarse-only bathymetry and the sea's
  `bed`: PIPE-10).
- **Needs:** TERR-8.

### [ ] TERR-11 — Surface rules

- **Do:**
  - Rock on steep slopes, scree, riverbeds, snow and glacier ice, and the
    soils, from the rules in `surface-palette.md`.
  - Keep the palette (blocks, mixes, thresholds) in one data file that is
    easy to edit, so tuning means changing that file and generating fresh
    chunks.
  - NZ ground blocks come from the `aotearoa` mod (MODS-17), which this
    generator requires (hub `interfaces.md`). Until MODS-17 lands, use the
    vanilla placeholders from `surface-palette.md`; if this is the first
    step to use `aotearoa`, add the dependency (hub `interfaces.md`).
  - Tune the palette in game in the tuning box in `surface-palette.md`,
    and record the final choices there.
- **Needs:** TERR-3. MODS-17 for the NZ blocks; placeholders until then.
- **Built (23 Oct 2026), version `0.8.3+nz.6`:**
  - `com.yucareux.tellus.nz.terrain.NzSurface`: one hook in
    `fillTellusSurface` hands every NZ column to it instead of Tellus's
    surface palette and snow (`tellus-internals.md` §12). Slope by Horn
    on the height tiles, in game degrees; step sides show a `face` mix.
  - The palette is the data file
    `mc262/src/main/resources/nz-terrain/surface-palette.jsonc`
    (`NzSurfacePalette`, `NzSurfaceMix`: weighted mixes as noise, patches
    or bands). A copy in `config/nz-terrain-surface.jsonc` wins, read at
    every world start, for tuning without a rebuild.
  - Vanilla placeholders: MODS-17 isn't done, so no `aotearoa`
    dependency yet.
  - Checked headless: 98.9 % of 200,192 columns in the tuning box have a
    top from the right mix, at the right shares (`testing.md`).
- **Still to do:** tune in game in the tuning box and record the final
  choices (`surface-palette.md`, "Tuning in game"). Tick the step then.

### [ ] TERR-12 — NZ biomes

- **Do:**
  - Place the NZ biomes in the biome source, per vegetation class or
    region. The biomes themselves (colours, spawns, ambience, the
    glow-worm cave biome) are defined in the `aotearoa` mod, MODS-19
    (decided 23 Oct 2026, hub `interfaces.md`); this generator requires
    it. If this is the first step to use `aotearoa`, add the dependency.
  - The glow-worm cave biome needs caves where it goes. Real NZ glow-worm
    caves (Waitomo, Oparara, Ngārua) are fairly shallow limestone stream
    caves, so they could follow the `rock` layer (PIPE-15); the deep caverns
    (TERR-20) are the other candidate. Cave shape is this generator's job.
- **Needs:** MODS-19.

### [ ] TERR-13 — Flora placement

- **Do:** place flora by vegetation class and canopy height, using the
  placed-feature IDs from hub `interfaces.md` (from the `aotearoa` mod,
  which this generator requires). Tellus's tree pass turns the ground to
  grass wherever it tries a tree, even on rock faces where the tree then
  fails (TERR-11, `tellus-internals.md` §12): the NZ placement should
  leave the surface blocks alone.
- **Needs:** MODS-9, HUB-3.

### [ ] TERR-14 — Streams and micro-detail

- **Do:** at 1:1, guided by the data:
  - streams from the stream layer;
  - slot gorges along them;
  - boulders;
  - cliff rock bands;
  - river steps without lips: the bed runs on under a step and the step
    flows (TERR-10 desktop check, `tellus-internals.md` §11.2).

### [ ] TERR-15 — DH fast LOD from our tiles

- **Do:** fast LOD samples our tiles, including the approximate colour of
  vegetation. Heights and cover already reach DH through the generator
  hooks (`tellus-internals.md` §9); this step is about speed (read the
  tiles' coarse level for each LOD detail, hub `interfaces.md`) and the
  vegetation colour. The LOD surface blocks are still Tellus's
  (`resolveLodSurface`, `resolveUltraFastLodSurface`): give them the NZ
  palette's top blocks (`NzSurfacePalette.land` and the water floors,
  with slope from the LOD's tile level; `tellus-internals.md` §12).

### [ ] TERR-16 — Test area 2 in game

- **Do:** generate test area 2 and check the rewilding, and that the
  Edoras-style tussock survives.
- **Needs:** PIPE-9.

### [ ] TERR-17 — Hybrid generation

- **Do:** follow hub `NZ-MINECRAFT-CONTEXT.md` §5.2:
  - ocean ring and blend band from the distance raster;
  - a density function wrapping Tectonic's `final_density`;
  - a combined biome source: vanilla biomes outside NZ. Terralith only if
    it works unmodified, wrapped from the outside; never open its files
    (licence, hub `CLAUDE.md`);
  - Tectonic's terrain continues down to the world floor at Y −272, with
    the same underground patches as TERR-8;
  - DH falls back to normal generation outside NZ.

  Write a plan in `.claude/plans/` first.
- **Needs:** PIPE-10, PACK-1 (done: Tectonic and Terralith have 26.2 builds), HUB-4.

### [ ] TERR-18 — Whole NZ

- **Do:** run with the full tiles. Measure memory, generation speed and the
  tile cache; decide on pre-generation.
- **Needs:** PIPE-11.

### [ ] TERR-20 — Deep caverns

- **Goal:** huge explorable caverns in the solid zone below the shell
  (TERR-8, option B), which today is plain deepslate.
- **Do:** write a plan in `.claude/plans/` first. Open choices: an absolute
  band (e.g. Y −272 to −80) or everything below the shell; whether to
  clamp the shell above that band (this also reduces the cave stretch near
  the coast, `tellus-internals.md` §4); entrances from the shell caves;
  lakes, lava, pillars. New NZ code only: the fill already runs through
  `NzUnderground`.
- **Needs:** TERR-8. Any time; not needed for the test-area milestone.

### [ ] TERR-21 — Vanilla structures in NZ worlds

- **Goal:** decide which vanilla structures NZ worlds get. Today none:
  Tellus has them all off and the NZ preset keeps that (TERR-8).
- **Raise first:** strongholds are the only way to the End in survival;
  mineshafts, dungeons, trial chambers, ancient cities underground; no
  villages (hub decision). Keep them out of NZ biome tags as needed
  (TERR-12).
- **Needs:** TERR-12.

### [ ] TERR-19 — First NZ release

- **Do:**
  - Version and changelog.
  - The LGPL notice (source link, statement that the code is modified).
  - `mc262/.../fabric.mod.json`: `contact` still points at upstream and
    `authors` is upstream's placeholder; point them at the fork (TERR-6
    changed only name and description).
  - Data attribution.
  - A GitHub release on the fork.
