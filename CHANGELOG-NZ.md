# Changelog (NZ)

NZ changes on top of upstream Tellus, one entry per version, newest first.
Upstream's own changes are not listed; the part of the version before `+nz`
is the Tellus version it is based on.

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
