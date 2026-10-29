# Changelog (NZ)

NZ changes on top of upstream Tellus, one entry per version, newest first.
Upstream's own changes are not listed; the part of the version before `+nz`
is the Tellus version it is based on.

## 0.8.3+nz.17 (TERR-15, 29 Oct 2026)

- Distant Horizons' distant terrain now uses the NZ surface palette, like
  the chunks: forest floor, tussock, scree, gravel flats, rock, snow and
  glacier ice in the distance instead of Tellus's Earth colours. The
  surface is picked from each distant cell's land cover and slope at the
  tiles' coarse level, so a cell shows what the ground averages to there:
  a small cliff inside a forest slope stays forest far away.
- The sides of distant columns show the surface's own blocks (or rock on
  rock faces), so forested hillsides stay forest-coloured from afar
  instead of showing a stripe of dirt at each step.
- Very far terrain no longer vanishes: when DH asks for cells coarser
  than the tiles' coarsest level (beyond level 7 in test area 1), the
  coarsest level is used instead of nothing, which had shown sea floor.
- Faster distant terrain near the player: DH no longer scans every block
  around a near LOD for Tellus's shorelines (up to a third of a second
  per LOD the first time), and no longer samples extra heights for
  Tellus's slope rules. Neither is used in NZ worlds.

## 0.8.3+nz.16 (TERR-23, 26 Oct 2026)

- Caves stay inside the mountains: no more holes in cliff faces, caves
  open to the sky, or lava and glow berries out in the open. Caves now
  keep at least 6 blocks of rock to the side of any face and Tellus's
  5 blocks below any ground, so they sit deeper under steep ground. Caves
  still fill the mountains, but there are no cave mouths yet: they come
  later, where real NZ caves are (limestone and marble country).
- Lava and water springs, underground lava lakes, geodes and ores stay
  inside the same skin, so springs no longer pour lava or water down
  cliffs.
- No surface lava lakes, and no azalea trees growing on the surface
  above lush caves (azalea isn't native).
- Lush caves (moss, glow berries) only under forest and mangroves.

## 0.8.3+nz.15 (TERR-14 step 5 follow-up, 25 Oct 2026)

- No more rock fins and hollows on cliffs: a ledge column could end up
  higher than the slope just behind it (a thin rock wall standing in
  front of the soil) or lower than the one in front of it (a pit behind
  a rim). Such columns are now pulled back as far as their neighbours
  need, so rock only shows where the slope steps down.
- Mossy rock faces in the bush have far fewer full moss blocks on their
  sides: the moss stays on the tops of steps and ledges, and the faces
  read as wet rock with moss in the cracks.

## 0.8.3+nz.14 (TERR-14 step 5, 25 Oct 2026)

- Cliff ledges: on steep rock faces, some columns move up or down by a
  few blocks onto ledge levels. A cliff now shows flat ledges with taller
  rock between them, instead of an even staircase. Each ledge comes and
  goes along the cliff, and the levels rise and fall across the land.
  Ledges keep a few blocks clear of water, streams and gorges.
- Ledges hold what collects on real ones: scree and tussock above the
  bush, moss and soil in the bush, snow up high.
- Rock faces above the bush have a little more variety (cobbles and dark
  weathered spots).
- Which surfaces get ledges, from which slope, how far apart and how wide
  comes from a new optional `"ledges"` section in the surface palette
  file, so it can be tuned in `config/nz-terrain-surface.jsonc` without a
  rebuild. Unlike the rest of the palette, it changes the terrain's height.

## 0.8.3+nz.13 (TERR-14 step 4 follow-up, 24 Oct 2026)

- Boulders stand out more: in bush creeks and gorges they are washed grey
  rock with a moss cap above the water (they vanished against the mossy
  gorge walls), and above the bush they are the darker, weathered
  greywacke mix (they blended into the scree and rock faces). Forest
  floor boulders stay mossy; riverbed and moraine boulders are unchanged.

## 0.8.3+nz.12 (TERR-14 step 4, 24 Oct 2026)

- Boulders: 1:1 rocks of 1 to 5 blocks, placed after the trees. They lie
  at the foot of cliffs and gorge walls, in creek beds and on gorge ledges,
  on riverbeds, outwash and moraine (the biggest ones), on scree, and here
  and there in tussock and forest. In the bush they are mossy, with a moss
  cap above the water.
- Where they go, how many, how big and what they are made of comes from a
  new optional `"boulders"` list in the surface palette file, so they can
  be tuned in `config/nz-terrain-surface.jsonc` without a rebuild.
- A boulder never cuts a tree, floats, or stands over a hole. The same
  spot always gets the same boulder, whatever the world seed.

## 0.8.3+nz.11 (TERR-14 step 3, second follow-up, 24 Oct 2026)

- Gorge walls follow the size of the stream: a small creek gets a short
  rock cut of a few blocks at the water, with its steep, wooded gully
  left above it; walls grow with the catchment, up to 16 blocks for big
  streams.
- Gravel ledges lie on the inside of bends, and the water runs against
  the wall on the outside. Both sides of a creek take their ledge width
  from the same place, so no single stone pillars stand between ledge
  and water any more.
- Wall tops vary more gently (over about 8 blocks instead of 3), so the
  walls no longer look like a fence of narrow columns.

## 0.8.3+nz.10 (TERR-14 step 3 follow-up, 23 Oct 2026)

- Gorges look less man-made: the ledges beside the creek vary from none
  (water from wall to wall) to 2 blocks, are gravel and cobbles like the
  creek bed, and sit at most 1 block above the water; the wall tops vary
  by up to 2 blocks, so the walls are no longer two flat planes.
- On any step 3 or more blocks tall (gorge walls, cliff edges), only the
  top block is soil and the rock face starts right under it: no more
  bands and pillars of dirt on rock walls. The height is the palette's
  new optional `"bare_step"` (default 3).

## 0.8.3+nz.9 (TERR-14 step 3, 23 Oct 2026)

- Slot gorges: where the elevation data already shows a deep, steep V
  along a creek, the V becomes a slot. Its sides are filled up to the
  rim, so the creek runs between vertical rock walls (up to 16 blocks
  high) with a 1-block ledge on each side, and flat ground on top. Walls
  rise gradually from the ends of a gorge. Nothing is dug out: the
  creek and its bed stay where they were. About 4 % of creek blocks in
  test area 1, mostly steep small creeks in bush, scrub and tussock.

## 0.8.3+nz.8 (TERR-14 step 2, 23 Oct 2026)

- Streams: every creek and stream of the tiles' `streams` layer is wet,
  at its own level, cut into its gully. Width is 1:1 from the size of the
  catchment (most creeks 1 block, big gravel streams up to the width of
  their gully floor, never wider), 1–3 blocks deep by size.
- Stream beds never rise downstream: the levels come from the new tile
  layer `stream_level` (needs tiles from the pipeline of 23 Oct 2026 or
  later; older tiles have no streams in game).
- Stream steps flow like river steps, and no new water sources form
  around them. Where a stream runs diagonally, a corner block joins the
  water. Low ground beside a stream is raised to a bank so the water
  can't spill.
- Stream beds have their own surface (`stream_bed`: gravel and cobbles,
  mossy in places); the palette's `water.stream` key is optional and falls
  back to the riverbed.
- The sides of channels at chunk borders show the rock mix instead of
  stone (rivers too).

## 0.8.3+nz.7 (TERR-14 step 1, 23 Oct 2026)

- River and lake steps flow: the top block at the edge of each step pours
  over onto the water below, instead of standing as a wall of still water.
  Drops of 2 blocks or more become small waterfalls.
- The bed runs on under a step: water within 2 blocks of lower water is
  1 block deeper, so a 1-block step no longer stands on a sill (the weir
  look). Taller drops keep a rock ledge for the water to fall over.
- No new water sources form around a step, so the flow can't fill the
  reach below up to the level above. Elsewhere, infinite water sources
  work as usual.

## 0.8.3+nz.6 (TERR-11, 23 Oct 2026)

- New Zealand worlds get their own ground: rock, scree, gravel flats and
  riverbeds, snow and glacier ice, and a soil per kind of vegetation, from
  the land-cover class, the slope (in game degrees) and the height in the
  tiles. Steep vegetated ground becomes a rock face, mossy below the
  treeline. Sea, lake and river floors have their own blocks.
- The sides of steps and cliffs show the rock mix instead of plain stone.
- Blocks come in weighted mixes, spread as noise, patches or bands.
  Everything (rules, thresholds, blocks, mixes, soil depths) is in one data
  file, `nz-terrain/surface-palette.jsonc` in the jar. For tuning, a copy in
  `config/nz-terrain-surface.jsonc` wins over it; it is read at every world
  start, and a broken copy is reported in the log and chat.
- Tellus's own surfaces, beaches and snow no longer run in NZ worlds. The
  blocks are vanilla placeholders until the `aotearoa` ground blocks.

## 0.8.3+nz.5 (TERR-10, 23 Oct 2026)

- New Zealand worlds take their water straight from the tiles' water
  layer: sea at sea level, every lake at its real surface, and rivers wet
  across their whole channel, each block at its LiDAR surface rounded to
  the nearest block. Rivers are level across and step down a block at a
  time downstream. Estuaries and lagoons 0–1 m high sit at sea level.
- Lake and river floors are invented until the tiles carry measured ones:
  rivers 1–3 blocks deep (deeper further from the bank), lakes 1 m deeper
  per 4 m from the shore, at most 40 m. Where the LiDAR under a lake is
  deeper, or the tiles have a measured `bed`, that wins.
- Distant Horizons shows the same water, from the coarser tile levels.
- The New Zealand preset no longer cuts banks and cliffs next to water
  down to a ramp (`shoreline_blend_cliff_limit` off).
- Tellus's own water analysis (water bodies, lake surface guesses, river
  reaches, shore blending) no longer runs in NZ worlds.

## 0.8.3+nz.4 (TERR-9, 23 Oct 2026)

- New Zealand worlds generate fully offline: no more Overture sand,
  WorldCover/Overture land cover, NOAA sea temperature, OpenWaters depth or
  ETH canopy downloads, and no prefetching around chunks or Distant Horizons
  areas. The biome source now takes cover and sea depth from the tiles too.
- NZ seas use one placeholder sea temperature (lukewarm ocean) until NZ
  biomes. The preset turns off Tellus's procedural trees, so trees are
  vanilla per biome for now. No more Tellus beach sand.
- Latitude/longitude in NZ worlds now means real NZ positions (NZTM2000):
  the preset's spawn is the real latitude/longitude of Sentinel Rock, and
  `/tellus map` (click to teleport, place search) and `/tellus weather`
  show and use the real place.

## 0.8.3+nz.3 (TERR-8, 22 Oct 2026)

- New Zealand worlds now generate from the NZ tiles: real heights at 1:3
  horizontal and 1:2 vertical (`Y = 63 + elevation / 2`, rounded away from
  sea level), from the finest tile level available, bilinear on coarser
  levels (Distant Horizons). Cover classes and the land mask come from the
  tiles too (LCDB mapped to Tellus's classes for now).
- The world runs from Y −272 to 2,031 from the first start (its own
  dimension type), and is solid down to bedrock at Y −272: deepslate fills
  the space below Tellus's cave layer, which stays 256 blocks deep.
- Sea and the area outside the tiles get a placeholder sea floor: 4 m deep
  at the coast, 1 m deeper per 50 m out, 40 m at most. No more online
  elevation, bathymetry or Overture water in NZ worlds.
- The New Zealand preset turns on caves, ores, lava pools and geodes, turns
  off roads, buildings and real-time weather, and spawns at Franz Josef.
- A New Zealand world can't be created without tiles (the Create button
  says where they were looked for); a dedicated server with an NZ world
  and no tiles refuses to start.

## 0.8.3+nz.2 (TERR-7, 22 Oct 2026)

- Reads the pipeline's NZ tiles (format version 1) from `<game dir>/nz-tiles/`,
  or the folder set as `tiles_dir` in `config/nz-terrain.json` (written with
  the defaults on first start). Nothing generates from them yet (TERR-8).
- Tile sets with an unknown format version or another origin or scale are
  refused; several sets are merged by priority. Decoded tiles are cached in
  memory (`tile_cache_mb`, default 256).
- NZ worlds save the data version of each tile set they were created with
  (`nz.data_versions`) and warn at start (log, and chat for admins) when the
  tiles differ or are missing.

## 0.8.3+nz.1 (TERR-6, 22 Oct 2026)

- New world type "New Zealand" (preset `tellus:nz`). It marks the world as NZ
  (an `nz` key in the generator settings) but still generates the same terrain
  as Earth.
- Renamed: display name "NZ Terrain", jar `nz-terrain-fabric-26.2-…`. The mod
  ID stays `tellus`, so this jar replaces Tellus.
- Version scheme `<Tellus version>+nz.<N>`.
