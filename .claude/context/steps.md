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

### [ ] TERR-4 — Study Tellus: coordinates and scales (read-only)

- **Goal:** know exactly where to plug in NZTM.
- **Do:**
  - Trace world x/z → lat/lon, and the horizontal scale, vertical scale,
    sea-level and world-height settings. Check what breaks with a world
    from Y −272 to 2,031.
  - Trace how world settings and presets reach the chunk generator.
  - Write a new `.claude/context/tellus-internals.md` with file:line pointers
    and the proposed hook points.

### [ ] TERR-5 — Study Tellus: data, generator, DH (read-only)

- **Goal:** know where a local tile source fits and what to switch off.
- **Do:**
  - How data sources fetch, cache and sample data (`world/data/`).
  - How the chunk generator uses them.
  - How DH fast LOD samples them.
  - List every online feature to disable for NZ worlds: OSM roads, buildings
    and lights, real-time weather and time, online elevation, WorldCover,
    Köppen.
  - Find out why river channels stayed dry stone at 3 m/block in TERR-1
    (`testing.md`).
  - Add all of this to `tellus-internals.md`.

### [ ] TERR-6 — NZ skeleton

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
- **Needs:** TERR-4, TERR-5.

### [ ] TERR-7 — Local tile source

- **Do:**
  - A reader for the pipeline tiles, with a unit test against the pipeline's
    fixture.
  - A configurable data folder and a cache.
- **Needs:** TERR-6, PIPE-7.

### [ ] TERR-8 — NZ heights

- **Do:**
  - The NZ world takes its height from the tiles via the NZTM mapping and
    the height formula in hub `interfaces.md` (1:3, vertical 1:2, sea
    level Y 63, world Y −272 to 2,031).
  - Vanilla-style underground below NZ reaching the new floor at Y −272:
    patch the hard-coded lava level (Y −54), let caves reach the floor, and
    optionally add ores below −64.
  - Outside the data: flat ocean for now.
  - Check heights against the pipeline's `height.tif` at a few known
    points (e.g. the check peak in the pipeline README, "WorldPainter").
- **Needs:** TERR-7.

### [ ] TERR-9 — Switch off online features for NZ worlds

- **Do:** disable everything from the TERR-5 list, so NZ worlds work offline
  and have no roads or buildings.

### [ ] TERR-10 — Water

- **Do:** sea, lakes at their real surface height, and rivers, all from the
  water layer.

### [ ] TERR-11 — Surface rules

- **Do:**
  - Rock on steep slopes, scree, riverbeds, snow and glacier ice, and the
    soils, from the rules in `surface-palette.md`.
  - Keep the palette (blocks, mixes, thresholds) in one data file that is
    easy to edit, so tuning means changing that file and generating fresh
    chunks.
  - NZ ground blocks from `nz-mods` (MODS-17) are a soft dependency: each
    has a vanilla fallback from `surface-palette.md`, used when `nz-mods`
    isn't installed.
  - Tune the palette in game in the tuning box in `surface-palette.md`,
    and record the final choices there.
- **Needs:** TERR-3. MODS-17 is optional: without it, the fallbacks are
  used.

### [ ] TERR-12 — NZ biomes

- **Do:**
  - NZ biomes per vegetation class or region. They live in this mod, so an
    NZ world works without `nz-mods`.
  - Keep them out of vanilla structure biome tags (no villages in NZ).
  - Choose sky, fog, water, grass and foliage colours (tussock should look
    golden, not green).
  - Decide with MODS-15 how `nz-mods` hangs ambience (sounds, particles)
    onto these biomes without a hard dependency (hub `interfaces.md`).
  - Mob spawns (decided 22 Oct 2026, hub `NZ-MINECRAFT-CONTEXT.md` §2):
    NZ Natives' animals, plus vanilla goats, sheep, cows and pigs for
    food, and red deer if a deer mod is found (hub §8). Suggested split:
    goats on alpine rock and scree (like feral goats, tahr and chamois),
    sheep in tussock high country, cows on lowland open land, pigs in
    lowland forest and scrub, deer in forest, subalpine scrub and tussock
    valleys. NZ Natives' animals must reach NZ biomes; how it adds spawns is
    checked in MODS-2.
- **Needs:** HUB-3.

### [ ] TERR-13 — Flora placement

- **Do:** place flora by vegetation class and canopy height, using the
  placed-feature IDs from hub `interfaces.md`. This is a soft dependency: the
  world must still generate without `nz-mods`.
- **Needs:** MODS-9, HUB-3.

### [ ] TERR-14 — Streams and micro-detail

- **Do:** at 1:1, guided by the data:
  - streams from the stream layer;
  - slot gorges along them;
  - boulders;
  - cliff rock bands.

### [ ] TERR-15 — DH fast LOD from our tiles

- **Do:** fast LOD samples our tiles, including the approximate colour of
  vegetation.

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

### [ ] TERR-19 — First NZ release

- **Do:**
  - Version and changelog.
  - The LGPL notice (source link, statement that the code is modified).
  - Data attribution.
  - A GitHub release on the fork.
