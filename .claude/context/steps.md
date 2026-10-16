# Steps

One step = one conversation ("Do step TERR-4"). Order across all repos, and
what blocks what: in the `mc-nz` workspace, `../.claude/context/steps.md`.
Tick `[x]` here and there when a step is done. Later steps are coarser; refine
them when they come up.

### [ ] TERR-1 — Vertical scale test (desktop, no code)

- **Goal:** choose 1:4 or 1:3 vertical.
- **Do:**
  - Use a launcher instance with Fabric 26.2, the released Tellus jar,
    Distant Horizons and Sodium.
  - Make worlds at 7 m/block horizontal and 1:4 and 1:3 vertical. Claude helps
    find the matching Tellus settings.
  - Visit the Remarkables and the Canterbury Plains (Milford Sound if there
    is time), and take screenshots.
  - Distant Horizons: Tellus releases ship their own DH build
    (`…-tellus-height…`) next to the mod, without saying why. Test the stock
    DH 3.3.x from Modrinth first. If tall peaks (Y 1,000+) render wrong in
    the distance, try the Tellus DH build. Record which one works, because
    the pack needs to know.
- **Done when:** results are in `testing.md`; the decision is recorded in
  HUB-1.

### [ ] TERR-2 — Build check

- **Goal:** the dev setup works before any code changes.
- **Do:**
  - Run `./gradlew -PtellusTargets=mc262 :mc262:build`, then `runClient262`.
  - Record the time taken and any gotchas in `testing.md`.
  - Confirm GitHub Actions are off on the fork; the upstream workflow builds
    all eight targets.
- **Done when:** the build passes and the dev client starts.

### [ ] TERR-3 — WorldPainter prototype (desktop)

- **Goal:** settle the look of test area 1 before writing generator code.
- **Do:**
  - Import the PIPE-5 heightmap and the PIPE-6 masks, and paint with
    placeholder trees.
  - Decide the block palette: rock types by slope and altitude, scree,
    riverbeds, snowline height, soils under forest and tussock.
  - Record it in a new `.claude/context/surface-palette.md`.
- **Needs:** PIPE-5, PIPE-6.

### [ ] TERR-4 — Study Tellus: coordinates and scales (read-only)

- **Goal:** know exactly where to plug in NZTM.
- **Do:**
  - Trace world x/z → lat/lon, and the horizontal scale, vertical scale and
    sea-level settings.
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
  - The NZ world takes its height from the tiles (1:7, vertical V, sea level
    63) via the NZTM mapping.
  - Outside the data: flat ocean for now.
  - Compare test area 1 with the WorldPainter prototype.
- **Needs:** TERR-7.

### [ ] TERR-9 — Switch off online features for NZ worlds

- **Do:** disable everything from the TERR-5 list, so NZ worlds work offline
  and have no roads or buildings.

### [ ] TERR-10 — Water

- **Do:** sea, lakes at their real surface height, and rivers, all from the
  water layer.

### [ ] TERR-11 — Surface rules

- **Do:** rock on steep slopes, scree, riverbeds and the snowline, from
  `surface-palette.md`.
- **Needs:** TERR-3.

### [ ] TERR-12 — NZ biomes

- **Do:**
  - NZ biomes per vegetation class or region.
  - Keep them out of vanilla structure biome tags (no villages in NZ).
  - Choose sky, fog and water colours.
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
  - a combined biome source;
  - DH falls back to normal generation outside NZ.

  Write a plan in `.claude/plans/` first.
- **Needs:** PIPE-10, PACK-1 (Tectonic/Terralith on 26.2), HUB-4.

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
