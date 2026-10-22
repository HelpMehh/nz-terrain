# Changelog (NZ)

NZ changes on top of upstream Tellus, one entry per version, newest first.
Upstream's own changes are not listed; the part of the version before `+nz`
is the Tellus version it is based on.

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
