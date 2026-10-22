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
- **Distant LODs shake slightly** when moving. Cause not checked: depth
  precision at very long range, or the test world lying about 4.5 million
  blocks from 0,0 (the NZ world will be near 0,0).
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
- **Desktop client check (open):** copy the fixture's `nz-main/` folder to
  `nz-tiles/` in the dev client's game folder (`mc262/run/` in a desktop
  clone), create a New Zealand world, quit, change `data_version` in
  `tileset.json`, reopen the world. Expected: yellow chat lines with the
  same warning. Without tiles: the "no tile sets found" line in chat.
