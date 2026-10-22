# Testing

## Scale test (TERR-1, run 21 Oct 2026)

Uses the released Tellus jar for 26.2 (not our code) to check the chosen
scale: 3 m/block horizontal, 2 m/block vertical, sea level Y 63, world
Y −272 to 2,031.

- Places: the Remarkables (does it look dramatic?), the Canterbury Plains
  (does it still look believable?) and Milford Sound.
- Record: settings used, screenshots taken, impressions, which DH build
  works, DH distance vs frame rate, DH database and world folder sizes. The
  hub decisions are updated in HUB-1.

### Tellus settings for 1:3 / 1:2

Tellus uses Web Mercator. `world_scale` is metres per block **at the
equator**; on the ground it is `world_scale × cos(latitude)`. With automatic
height scaling on, vertical metres per block = ground scale ÷ terrestrial
height scale. Block Y = `height_offset` + scaled elevation, and sea level =
`height_offset`. (Code: `EarthProjection`, `TerrainHeightTransform`,
`EarthGeneratorSettings.resolveHeightLimits`.)

| Setting | Value | Why |
|---|---|---|
| World scale | **4.2** (hold Shift while dragging: 0.1 steps) | 3.0 m/block at about 44.5° S |
| Automatic height scaling | **On** | Vertical then follows the local ground scale |
| Terrestrial height scale | **1.5** | 3 m ÷ 1.5 = 2 m/block |
| Height offset | **63** | Sea level Y 63 |
| Max altitude | **2031** | On automatic, Tellus sizes for Everest and falls back to the full Y −2032..2031 |
| Min altitude | **−272** | Both are multiples of 16 from the slider start, so world = Y −272..2031, 2,304 tall |
| Increase Height | **Off** | Only for worlds taller than vanilla's 4,064 blocks; it needs a launch flag and the Tellus DH build |
| Auto adjust button | **Don't press** | It overwrites scale, offset and altitudes |
| Roads, buildings | Off | No towns or roads |

Resulting scale (horizontal / vertical metres per block): Canterbury Plains
3.03 / 2.02, Milford Sound 2.99 / 1.99, Remarkables 2.97 / 1.98. Mercator
varies across NZ: Cape Reinga 3.46 / 2.31, Stewart Island 2.85 / 1.90. The NZ
generator will use NZTM instead, so the scale will be exact everywhere.

Teleport targets at world scale 4.2 (`/tp @s x Y z`; arrive high, in
spectator or creative mode). The world sits about 4.5 million blocks from
0,0; the NZ world will be near 0,0.

| Place | x | z | Ground Y |
|---|---|---|---|
| Remarkables, Single Cone | 4474274 | 1340857 | ≈1236 |
| Milford Sound, Freshwater Basin | 4450818 | 1326198 | 63 |
| Mitre Peak | 4448406 | 1324856 | ≈908 |
| Canterbury Plains, Rakaia (SH1) | 4559328 | 1292103 | ≈113 |
| White Horse Hill (view of Aoraki, DH check) | 4508253 | 1290929 | ≈459 |
| Aoraki summit | 4509552 | 1286423 | ≈1900 |

Elevation comes online from Mapterhorn, which has a 1 m NZ source and is
fetched down to zoom 18, so detail is close to the final data where LiDAR
exists. Where it doesn't, it is coarser.

Sizes to measure, relative to the instance's game folder:
`saves/<world>/` (the world, including the DH database) and `tellus/cache/`
(downloaded data). DH keeps one database per dimension, e.g.
`saves/<world>/dimensions/minecraft/overworld/data/DistantHorizons.sqlite`,
with `-wal` and `-shm` files next to it (SQLite's working files; count them
too).

### Results (21 Oct 2026)

Settings as above; Fabric 26.2, released Tellus, Sodium, stock DH
`3.3.3-26.2`.

- **Scale:** looks believable everywhere and dramatic in the mountains. The
  settings are kept; no change to the hub scale.
- **World height:** the world from Y −272 to 2,031 was created and generated
  without Increase Height. The floor itself was not inspected. TERR-4
  found that Tellus only builds a rock shell `underground_depth` blocks
  thick under the surface (default 256, as here), with air below it, so
  this world reached Y −272 only under deep sea (`tellus-internals.md` §4).
- **Distant Horizons:** stock DH renders the peaks up to Y≈1,900 correctly. The
  Tellus DH build is not needed.
- **Frame rate:** above 120 FPS at every DH distance up to 2,048 chunks, which
  is further than planned. Frame rate does not limit the DH distance.
- **Fill time:** this limits the DH distance. Coarse distant terrain appears
  in about a minute. Full detail then generates at about 128–256 chunks/s,
  and DH estimated 24–80 h for 2,048 chunks. Scaled by area from that
  estimate (not measured): 1,024 ≈ 6–20 h, 512 ≈ 1.5–5 h, 256 ≈ 20–75 min.
- **Disk, DH only partly filled:** world folder 311 MB, `tellus/cache/`
  438 MB. Measured a little later, the overworld DH database alone was
  378 MB, so DH, not the chunks, takes most of the world folder, and it keeps
  growing while the distance fills in. Full-size figures are still unknown.
- **Distant LODs shake slightly** when moving. In the NZ world, where test
  area 1 lies about 120,000 blocks from 0,0, they don't (TERR-8 desktop
  check). So the cause was the test world's distance from 0,0 (about
  4.5 million blocks), not depth precision at long range.
- **Rivers:** channels are carved but left as stone, with no water. This is a
  Tellus water issue, not a scale issue. TERR-5 found the likely cause in
  the code (not checked in game). The channels are the real LiDAR riverbeds.
  Water comes only from Overture shapes, and centreline rivers get just a
  1-block strip. The rest is drawn as stone (`tellus-internals.md` §11).
  The fix is TERR-10.

## Build check (TERR-2, run 22 Oct 2026)

Upstream Tellus 0.8.3 code, no NZ changes, system OpenJDK 25.0.5.

- **`./gradlew -PtellusTargets=mc262 :mc262:build`** passes. It compiles
  and runs the unit tests in `src/test`, the shared layers and
  `mc262/src/test`: 600 tests, 0 failures, 1 skipped.
  - Cold first run, with empty Gradle caches: **about 2 min**, including
    downloading Gradle 9.4.0, Minecraft and mappings. A rerun with nothing
    changed: about 1 s.
  - Output: `mc262/build/libs/Tellus-fabric-26.2-0.8.3.jar` (about 18 MB).
  - Gradle uses the installed JDK 25 and doesn't download its own.
    `~/.gradle/caches` is about 400 MB afterwards.
- **Expected noise, not problems:**
  - The skipped test, `WorldCoverCogSourceTest.readsTheOfficialNativeHalfDomePixelThroughHttpRanges`,
    reads WorldCover online and runs only with
    `TELLUS_LIVE_WORLDCOVER_TEST=true`.
  - `compileTestJava` prints a deprecated-API note. Gradle's "problems
    report" (`build/reports/problems/`) contains only that note.
  - Protobuf 4.28.2 prints `sun.misc.Unsafe` warnings in the tests on
    Java 25.
  - At launch, the Fabric mixin warning `ClassNotFoundException:
    com/seibel/distanthorizons/...AbstractDhLevel` appears when Distant
    Horizons isn't installed. Tellus's DH mixins are optional.
- **`runServer262`** (headless, so it works without a display) starts with
  43 mods (Tellus plus the Fabric API modules) and reaches `Done` about 10 s
  after launch. It needs `eula=true` in `mc262/run/eula.txt` first. `run/`
  is git-ignored.
  - To run it from a script and stop it cleanly (TERR-6): Gradle passes its
    input on to the server console, so pipe `stop` in once `Done (` shows:
    `bash -c '(until grep -q "Done (" out.log; do sleep 2; done; echo stop) | ./gradlew -PtellusTargets=mc262 runServer262 > out.log 2>&1'`.
  - To test a world type without the client, set `level-name` to a new name
    and `level-type` (e.g. `tellus\:nz`) in `mc262/run/server.properties`,
    and put both back afterwards.
- **`runClient262`** needs a desktop session. A sandbox without a display
  can't open the window, so run it from a clone on the desktop. It starts
  (51 mods: the client adds Fabric API's client modules), and an Earth world
  with default settings generates. A cold run took about 2 min 45 s,
  including downloading Gradle and the game assets. Expected log noise:
  - a `Failed to load library flite` stack trace (twice): the narrator's
    text-to-speech library isn't installed. It's harmless; installing
    `flite` removes it;
  - `Could not authorize you against Realms`: the dev client uses an offline
    player name;
  - mixin `ClassNotFoundException` warnings for Distant Horizons and Voxy:
    both integrations are optional.
- **GitHub Actions:** the upstream workflow (`.github/workflows/gradle.yml`)
  builds all eight targets on every push. It is turned off in the fork's
  repository settings, not by editing the workflow file, so upstream merges
  stay clean. Actions were on when checked and were turned off on
  22 Oct 2026. Check: `gh api repos/<owner>/<fork>/actions/permissions`
  should show `"enabled": false`.

## NZ skeleton check (TERR-6, 22 Oct 2026)

Code: `0.8.3+nz.1`.

- **Build:** `./gradlew -PtellusTargets=mc262 :mc262:build` passes:
  604 tests (600 upstream + 4 in `NzWorldSettingsTest`), 0 failures,
  1 skipped (the online WorldCover test, as in TERR-2). Jar:
  `mc262/build/libs/nz-terrain-fabric-26.2-0.8.3+nz.1.jar`.
- **Headless server check (passed):** in `mc262/run/server.properties` set
  `level-name=nz-check` and `level-type=tellus\:nz`, then run
  `runServer262`. The world was created with `EarthChunkGenerator`, and both
  settings copies in `nz-check/data/minecraft/world_gen_settings.dat` held
  `nz: {}` (NBT, gzip; in 26.2 the generator settings are there, not in
  `level.dat`). A second start loaded it with `nz` kept. No errors beyond
  the usual DH/Voxy mixin warnings. `server.properties` was put back
  afterwards.
  - First start logged `Overworld dimension type: minY=-368,height=800`:
    the NZ preset's height isn't applied until the next start (TERR-8,
    `tellus-internals.md` §6.2).
- **Desktop client check (passed, 22 Oct 2026):** run `runClient262`, then
  Singleplayer → Create New World → World tab. Checked:
  - the World Type button offers **New Zealand** (next to Earth);
  - with New Zealand selected, Customize is greyed out;
  - the world creates and generates Earth terrain (Mount Everest spawn,
    as Earth's defaults; NZ terrain comes in TERR-8);
  - Mod Menu, if installed, shows "NZ Terrain" `0.8.3+nz.1`.

## Tile source check (TERR-7, 22 Oct 2026)

Code: `0.8.3+nz.2`.

- **Build:** passes, 625 tests (600 upstream + 25 NZ), 0 failures,
  1 skipped (the online WorldCover test). The reader test decodes all 11
  layers of the pipeline fixture to the SHA-256 values in its
  `expected.json`.
- **Headless server check (passed):** copy the fixture set to
  `mc262/run/nz-tiles/nz-main/` (it stays there for TERR-8; `run/` is
  git-ignored), set `level-name` to a new name and `level-type=tellus\:nz`,
  run `runServer262`:
  - first start: `config/nz-terrain.json` written with the defaults, log
    line `NZ tile set: nz-main (data 2026-10-22, …)`, and both settings
    copies in `world_gen_settings.dat` hold `data_versions` `nz-main` =
    `2026-10-22`;
  - `data_version` in the set's `tileset.json` changed, second start: WARN
    "the tiles differ from those this world was created with" plus the
    per-set line; the world keeps `2026-10-22`;
  - tiles folder renamed away: WARN "no tile sets found in …/nz-tiles";
  - `SERVER_STARTED` fires after `Done (`, so the warnings come just after
    it in the log.
- **Desktop client check (passed, 22 Oct 2026):** copy the fixture's
  `nz-main/` folder to `nz-tiles/` in the dev client's game folder
  (`mc262/run/` in a desktop clone), create a New Zealand world, quit,
  change `data_version` in `tileset.json`, reopen the world.
  - First start: `NZ tile set: nz-main (data 2026-10-22, …)` logged, no
    chat message.
  - Reopened: the two warning lines in the log and as yellow chat lines
    (`[System] [CHAT] NZ Terrain: the tiles differ …`), so the single
    player owner gets them. The "no tiles" case was not tried on the
    desktop (checked on the server).
  - **Found, not caused by TERR-7:** the reopened world logged
    `Failed to load chunk … ArrayIndexOutOfBoundsException: Index 38 out
    of bounds for length 33` for chunks from the first session. The first
    start ran at Y −368..431 (800 tall), the second at Y −64..463 (528):
    the preset's height problem from TERR-6, with this consequence
    (`tellus-internals.md` §6.2, fixed in TERR-8). Until then, NZ test
    worlds are throwaway; create a new one per test.

## NZ heights check (TERR-8, 22 Oct 2026)

Code: `0.8.3+nz.3`.

- **Build:** passes, 642 tests (600 upstream + 42 NZ), 0 failures,
  1 skipped (the online WorldCover test). New: sea floor (against brute
  force on the fixture), sampler (fixture pixels, coarse-level bilinear,
  outside the data), underground fill, preset values (formula, rounding,
  world height, dimension type).
- **Headless server check (passed)** with the real test-area-1 tile set
  (`nz-data-pipeline` `tiles test-area-1` output, data `2026-10-22`):
  point `tiles_dir` in `mc262/run/config/nz-terrain.json` at the set's
  parent folder, set `level-name` to a new name and `level-type=tellus\:nz`,
  run `runServer262` and send `forceload add <x> <z>` for a few places once
  `Done (` shows. Put the config and `server.properties` back afterwards.
  - First start: `Overworld dimension type: minY=-272,height=2304` and
    `fillFromNoise … minY=-272, height=2304`. Spawn saved at
    −76040, 212, 93845: Sentinel Rock, 294.5 m in the tiles → surface
    Y 211.
  - Saved chunks read with a small Python Anvil/NBT reader and compared
    with the pipeline's `height.tif` (rounded to ⅛ m, then
    `63 + ceil(m / 2)`), for land columns at least 4 blocks from any
    water: **18,236 of 18,236 exact** (skip plants when finding the
    terrain top). Aoraki's chunk peaks at **Y 1,922**.
  - Every column: bedrock only at Y −272, deepslate up to the shell, caves
    in the shell. Ore blobs and gravel reach up to 16 blocks below the
    shell bottom.
  - Sea: floor Y 60–61 next to the coast, Y 43 with water to Y 63 in the
    open ocean outside the data. Before the ocean floor hook it was Y −214
    (`tellus-internals.md` §11).
  - Reopened the same world, generated new chunks: same height, no
    "Failed to load chunk", no height datapack written.
  - Tiles folder pointed elsewhere, NZ world: the server refuses to start
    (`IllegalStateException: NZ Terrain: no tile sets found in …`, as a
    crash report), before any level exists.
  - To see what the tiles hold at a spot (water class, height…), read
    them with the pipeline's reference reader
    (`nz_pipeline.tiles.read_tile`, in its virtual env), or export one
    tile to GeoTIFF with `nz-pipeline export`. Tile of block x at level 0:
    `x >> 9`, pixel `x & 511`.
  - Downloads still made (`tellus/cache/`): Overture sand and land cover;
    ETH canopy answered 404 (trees fall back to biome trees). TERR-9.
  - Five forceloaded spots plus spawn: "Can't keep up … 12 s behind" once,
    while those chunks generated. Speed isn't measured yet (TERR-18).
- **Desktop client check (passed, 23 Oct 2026)**, in a launcher instance
  with Sodium and stock Distant Horizons (the jar replaces Tellus there):
  - Empty `nz-tiles/`: Create World showed "Can't create a New Zealand
    world" and went back.
  - With the test-area-1 set (`nz-main/`) in `nz-tiles/`: the valley at
    spawn, Aoraki (summit about Y 1,920) and the beach look right. The sea
    deepens steadily from the beach to Y 44 (floor 43) offshore.
  - Underground: caves and lava down to about 256 blocks under the surface
    (Y ≈ −193 at the coast), solid deepslate below. Caves also run inside
    the mountains.
  - Reopened the world: no "Failed to load chunk" in `latest.log`.
  - DH: distant terrain has the same shapes as near terrain, and the
    distant LODs **no longer shake** (see TERR-1 above). Filling in is slow,
    as expected.
  - Stutter right after loading in, gone after switching the client JVM
    to ZGC (`-XX:+UseZGC`). Not looked into; a candidate for the pack's
    JVM arguments.
  - **River near spawn** (around −76045, 93662): the water surface comes
    in flat reaches with a step of a block or more between them, and a
    row of sand blocks across the channel at each step. This is Tellus's
    non-Overture water path, seen in game for the first time:
    `InlandWaterFlowAnalyzer` levels a river into flat reaches with
    waterfalls (`tellus-internals.md` §11). TERR-10.
  - Lighter, straight-edged patches in the shallow sea floor, seen from
    about 600 blocks off the beach (looking east from −80559, 89387).
    Generated headless, the same stretch of floor is a smooth slope
    (Y 56 → 61 towards the coast, no edges), so probably the boundary
    between DH LODs and loaded chunks. Not confirmed in game.
  - Known and expected: biomes, grass colours and trees are still Tellus's
    Earth ones read by Mercator (TERR-12/13); gravel riverbeds show as
    flat stone (TERR-11).

## Offline check (TERR-9, 23 Oct 2026)

Code: `0.8.3+nz.4`.

- **Build:** passes, 647 tests (600 upstream + 47 NZ), 0 failures,
  1 skipped (the online WorldCover test). New: NZTM ↔ lat/lon against
  pyproj to 1 mm (also east of 180°), the sampler's cover and elevation
  queries against its samples, the preset's spawn (real lat/lon → block
  −76040, 93845) and `custom_trees` off.
- **Online server run, cache must stay empty (passed):** test-area-1 tiles,
  new world, `tellus/cache/` deleted first, spawn plus four forceloaded
  spots (`forceload add -76040 93845`, `-80559 89387`, `-78000 90000`,
  `-90000 85000`), 60 s of generation. Afterwards `tellus/cache/` held only
  `koppen/` (Tellus copies its bundled Köppen file there; not a download).
  The same run with the TERR-8 code had downloaded Overture sand and land
  cover, WorldCover, OISST and OpenWaters tiles and tried ETH canopy (404).
  Spawn: `Using Tellus initial spawn at BlockPos{x=-76040, y=212, z=93845}`,
  the same block as before.
- **Networking blocked (passed):** the same run inside `unshare -rn` (a
  network namespace with only loopback; run `ip link set lo up` inside).
  World generated (29 region files, as online), same spawn, `tellus/cache/`
  again only `koppen/`. The only network errors in the log are Minecraft's
  own (`api.minecraftservices.com/publickeys`).
  - **Gotcha:** don't run Gradle inside the namespace. Gradle processes
    coordinate their shared cache locks (`~/.gradle/caches/journal-1`, used
    by every Gradle version) over loopback, so a Gradle inside can't get a
    lock held by any Gradle daemon outside ("Timeout waiting to lock
    journal cache"). Instead, start `runServer262` once normally, copy the
    server JVM's command line from `/proc/<pid>/cmdline`, and run only that
    inside the namespace, from `mc262/run`, with `-Duser.home=<home>` added
    before the main class (inside, Java's home is `/root`).
  - Distant Horizons isn't on the dev server, so the DH-area prefetch hook
    (`prefetchForArea`) was not exercised there.
- **Client check on a virtual display (passed):** `runClient262` under
  Xvfb (software OpenGL), driven with `xdotool`, screenshots with
  ImageMagick `import -window root`. With an `options.txt` holding
  `onboardAccessibility:false` and `guiScale:2`, the game window sits at
  854 × 480 in the middle of a 1280 × 720 screen, so click positions are
  screen positions. Steps: Create World → Game Mode Creative (commands on) →
  World tab → World Type until "New Zealand" → Create → "experimental
  features" warning → Yes.
  - Spawn `−76040, 212, 93845`; trees are vanilla oak and birch
    (`custom_trees` off).
  - `/tellus weather`: "Location: Westland District, West Coast, New
    Zealand (−43.4219, 170.1823)", real time UTC+13:00.
  - `/tellus map`: the OSM map opens centred on Franz Josef. Searching
    "Fox Glacier" and pressing Teleport lands on snow and ice at
    −43.5170, 170.1387 (the glacier).
  - Not checked: Distant Horizons (not in the dev client), so the
    `prefetchForArea` hook still needs the desktop instance with DH:
    after flying around an NZ world, `tellus/cache/` should hold only
    `koppen/`.

## Water check (TERR-10, 23 Oct 2026)

Code: `0.8.3+nz.5`.

- **Build:** passes, 657 tests (600 upstream + 57 NZ), 0 failures,
  1 skipped (the online WorldCover test). New (`NzWaterTest`, on small
  tile sets written by the test): water rounding, river and lake depths,
  a lake's one surface and its floor, LiDAR and `bed` floors, a river
  level across with a step, a bank block, floors matching across a tile
  border, sea and land, and the resolver region = the column block by
  block.
- **Headless server check (passed)** with the test-area-1 tiles
  (`tiles_dir` = the folder holding `nz-main/`; `mc262/run/nz-tiles/`
  only holds the one-tile fixture, where everything is sea and spawn
  lands at Y 64). Spawn as before (−76040, 212, 93845). Five 128 × 128
  areas forceloaded: the river near spawn (`-76104 93600 -75977 93727`
  and `-76104 93780 -75977 93907`), the biggest river's mouth
  (`-79758 88841 -79631 88968`), the Lake Mapourika shore
  (`-75832 90324 -75705 90451`) and the Lake Mueller shore
  (`-79972 93933 -79845 94060`). Commands went in through a FIFO
  (`tail -f fifo | ./gradlew … runServer262`).
  - Saved chunks read with a small Python Anvil reader and compared with
    the tiles (pipeline `nz_pipeline.tiles.read_tile`), recomputing the
    expected surface (lakes: `63 + floor(m / 2 + 0.5)`; rivers: the
    lowest of that within 2 blocks): 48,511 lake, 19,442 river and 16,859
    sea columns, **all wet, all with exactly the expected surface**, every
    floor below its water. Lake depths 1–20 blocks (most at 20, the
    40 m limit), river depths 1–3, sea 2–3 near the coast.
  - 2,102 land columns had water on top: 2,051 are LiDAR land below 0 m
    beside the sea (terrain Y 62 or lower, flooded to Y 63 once the chunks
    ticked); the rest is flowing water spreading over low ground.
  - "Can't keep up … 43 s behind" once while the ~400 chunks generated.
    Speed isn't measured yet (TERR-18).
- **Not checked yet (desktop, with DH):** how the river steps near spawn
  look (around −76045, 93662; the TERR-8 desktop check saw sand rows
  there), lakes and the river mouth up close and from DH, and that DH
  water matches the loaded chunks at the boundary.

