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
  (`-79972 93933 -79845 94060`). Commands went in through
  `tail -f cmds.txt | ./gradlew … runServer262`, appending each command
  to the plain file (`printf '…\n' >> cmds.txt`; fixed in TERR-14 step 2).
  A FIFO is unreliable there: `tail` passes its lines on only when the
  writer closes it, so a writer kept open sends nothing.
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
- **Desktop client check of the river (23 Oct 2026)**, `0.8.3+nz.5`, the
  river near spawn (around −76045, 93662): much better than Tellus's
  reaches (TERR-8 check above): whole channels wet, level across, gravel
  bars dry. Still a **lip at every step**: the upper reach ends in a
  1-block wall of still water, standing on a 1-block sill whose top is
  level with the lower reach's surface, so each step looks like a small
  weir. The sill is the floor: `NzInlandWater` puts it `depth` below each
  column's own surface, so it steps down with the water. (The sill showed
  as a row of sand then: Tellus's riverbed; since TERR-11 it is the
  riverbed mix.) Fix ideas: `tellus-internals.md` §11.2.
- **DH water (desktop, 23 Oct 2026):** water in DH's distant LODs looks
  right (reported as "looks great").
- **Not checked yet (desktop):** lakes and the river mouth up close.

## Surface check (TERR-11, 23 Oct 2026)

Code: `0.8.3+nz.6`.

- **Build:** passes, 674 tests (600 upstream + 74 NZ), 0 failures,
  1 skipped (the online WorldCover test). New: the jar's palette against
  the rules in `surface-palette.md`, file mistakes named by place, the
  `config/` tuning copy and a broken one, mix shares for every pattern,
  game-degree slope on a ramp, a column's top, layers and face, water
  floors, chunk bounds.
- **Headless server check (passed)** with the test-area-1 tiles, set up
  as in TERR-8 (`tiles_dir`, a new `level-name`, `level-type=tellus\:nz`).
  Five 128 × 128 areas forceloaded through a FIFO: Sentinel Rock
  (`-76220 94340 -76093 94467`), the glacier terminus
  (`-76035 95000 -75908 95127`), the névé (`-74448 96432 -74321 96559`),
  `-76400 94700 -76273 94827` and the tuning box's north-west corner
  (`-76800 94208 -76673 94335`). Spawn as before (−76040, 212, 93845).
  - Saved chunks read with a small Python Anvil reader; each column's
    surface recomputed independently from the tiles (pipeline
    `nz_pipeline.tiles.read_tile`, Horn slope × 1.5, the palette file's
    rules) and compared with its top block, skipping plants, leaves, logs
    and snow layers. **197,914 of 200,192 columns (98.9 %) have a top
    from their surface's mix**, and the shares match the weights (rock:
    stone 59 %, andesite 24 %, tuff 15 %; forest floor: podzol 43 %,
    coarse dirt 20 %, moss 14 %, grass 11 %, rooted dirt 10 %). The
    first `under` layer was right in all but 21 columns. Snowy rock had a
    snow layer on 56 % (60 % asked, in patches).
  - The 2,278 others: dirt under vanilla tree trunks (976, forest),
    grass where Tellus's tree pass tried a tree on a rock or mossy-rock
    face (410), ores and gravel blobs from ore features at the surface
    (about 800). `tellus-internals.md` §12.
  - Sky-facing step sides (blocks between a column's top and its lowest
    neighbour's): stone 36 %, dirt 31 %, mossy cobblestone 12 %, moss
    12 %, andesite 6 %, tuff 3 %: the rock faces show the schist and
    mossy mixes, not plain stone.
  - The areas held no lake or sea floor and only 21 riverbed columns
    (most of the Waiho bed is dry LCDB gravel, i.e. gravel flats);
    water floors are covered by the unit tests only.
  - Tuning copy: a copy with an unknown block (`tuf`) logged
    `can't use config/nz-terrain-surface.jsonc, using the mod's own
    surface palette: mixes.schist.blocks.tuf "tuf" is neither a mix nor a
    block the game knows`; a valid copy logged `surface palette from
    config/nz-terrain-surface.jsonc (a tuning copy; …)`.
- **Not checked yet (desktop):** how it all looks, and the tuning itself
  (`surface-palette.md`, "Tuning in game"). DH far away still shows
  Tellus's colours (TERR-15).

## River steps check (TERR-14 step 1, 23 Oct 2026)

Code: `0.8.3+nz.7`.

- **Build:** passes, 678 tests (600 upstream + 78 NZ), 0 failures,
  1 skipped (the online WorldCover test). New in `NzWaterTest`: the bed
  level under a 1-block step (bank and middle), a 20-block drop keeping a
  ledge, step blocks only at the edge, no new sources within 2 blocks of
  a step and none further out.
- **Headless server check (passed)** with the test-area-1 tiles, set up
  as in TERR-8. Three 128 × 128 areas forceloaded through a FIFO: the
  river near spawn (`-76104 93600 -75977 93727`,
  `-76104 93780 -75977 93907`, as in TERR-10) and the area with the most
  drops of 3+ blocks (`-79792 96730 -79665 96857`); 90 s of ticking, then
  `save-all flush`. Saved chunks read with a small Python Anvil reader
  and compared with the tiles. Expected surfaces as in TERR-10, with the
  `water-surface.tif` values rounded to ⅛ m first, like the tiles: without
  that, about 500 columns at step edges differ by a block in the script.
  - Every lake and river column has exactly the expected surface; no
    water source above it, no water on land.
  - 1-block steps: **no lip** at any of 2,387 (the TERR-10 world, same
    script: 440 of 1,082). In the forceloaded chunks every step flows:
    1,813 of 1,813 have flowing water over the lower reach; drops of 2
    (329) and 3+ (688) have water falling all the way down. Steps in the
    saved ring of chunks around them don't flow: those never tick.
  - First run without the NZ mixin (only Tellus's source gate): 2,571
    sources 1–4 blocks above the expected surface, spreading from step
    corners up to 10+ blocks into the lower reach (`tellus-internals.md`
    §11.2).
  - A mixin `@Shadow` with the wrong signature compiles, but the server
    then fails at start (`@Shadow method … was not located`): start the
    server after any mixin change.
- **Desktop client check (passed, 23 Oct 2026)**, a new world in the
  launcher instance (old chunks keep their old water): 1-block and
  2-block steps on the river near spawn (−76044, 93675; −76034, 93639)
  and the 10–12-block drops in the drops area (−79712, 96836 and
  around) "look good". The steep reach comes out as one broad cascade:
  falling water down the rock face from each step edge into the pool
  below, nothing spilling onto the rock beside it.

## Streams check (TERR-14 step 2, 23 Oct 2026)

Code: `0.8.3+nz.8`, tiles with `stream_level` (pipeline of 23 Oct 2026,
data `2026-10-23`).

- **Build:** passes, 687 tests (600 upstream + 87 NZ), 0 failures,
  1 skipped (the online WorldCover test). New: `NzStreamsTest` (width and
  depth by class, a creek at its level, a step that flows over a bed that
  runs on, a raised bank, the sampler giving banks and beds, corner
  blocks, a wide stream filling exactly its gully floor across a tile
  border), the palette's optional `water.stream`, and the fixture's
  `stream_level`.
- **Headless server check (passed)**, set up as in TERR-10. Three
  128 × 128 areas forceloaded: forest creeks 470 blocks from spawn
  (`-76416 93568 -76289 93695`), dense forest creeks
  (`-74752 92928 -74625 93055`) and a big stream (class 85+,
  `-77696 92160 -77569 92287`); 90 s of ticking, `save-all flush`. The
  expected columns came from a throwaway JUnit test calling
  `NzWater.column` on the real tiles; saved chunks were read with a small
  Python Anvil reader.
  - All 1,221 stream centreline blocks are wet, with the surface exactly
    `63 + floor(stream_level / 2 + 0.5)` (so beds never rise where the
    layer doesn't); all 3,949 stream water columns hold water from the
    floor to the surface.
  - 28 banks raised, all solid and dry; no water on any dry column.
  - 270 of 271 stream steps have flowing water over the lower block (the
    other is at an area edge); water 2+ blocks above a stream's surface
    only below taller steps (waterfalls).
  - First run: the banks were missing (water spilled over 6 columns),
    because dry columns take their height from the height grid, not the
    water region (`tellus-internals.md` §11.2). Fixed in the sampler.
  - A stream tile takes 25–65 ms the first time (once per region).
- **Desktop client check:** open. A forest creek and the big stream near
  spawn (the areas above): width, banks, flow at steps, the stream-bed
  blocks, chunk borders. Expect bare strips along creeks (Tellus's tree
  distance, `tellus-internals.md` §11.2).
