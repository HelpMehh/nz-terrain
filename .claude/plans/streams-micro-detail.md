# Plan: TERR-14 — streams and micro-detail

TERR-14 holds five features (river steps, streams, slot gorges, boulders,
cliff rock bands). Too much for one conversation, so this plan splits it
into five steps, **one conversation each**, in this order. Each step
raises the NZ version by 1 (`gradle.properties` `nz_version`) and gets a
`CHANGELOG-NZ.md` entry. TERR-14 is ticked after step 5, which deletes
this plan once its lessons are in the context files.

## What the code already gives us (found while writing the plan)

- **Wet columns take their height from the water regions, dry columns
  from the sampler** (corrected in step 2: `resolveColumnHeights` gives a
  dry column the height grid's value, `tellus-internals.md` §11.2). So
  streams and gorges are carved **entirely in NZ code**, with **no new
  upstream hook**: a stream column is water from `NzWater.column`; a bank
  or a gorge wall is a dry column whose height `NzTerrainSampler.sample`
  takes from the per-tile pass (`NzWater.streamGround`, `NzStreams`).
- **The `streams` layer** is already declared (`NzTileLayer.STREAMS`,
  level 0 only) and filled by the pipeline (PIPE-14): 8-connected
  centrelines of the 3 m flow paths, value = catchment size class
  `1 + floor(25 × log10(ha))`. Test area 1: classes 8–113, median 41
  (~40 ha); 85 % of stream blocks lie in the LiDAR channel; every stream
  ends at water, the data edge or a glacier. Notes for TERR-14 are in hub
  `interfaces.md` ("Streams").
- **Steps flow (step 1, done):** every lake/river pixel with lower water
  on one of its four sides is a step (`NzInlandWater`, a `BitSet` per
  tile); `isWaterfallSourceColumn` marks its top block, and
  `FlowingFluidMixin` stops new sources within 2 blocks of a step
  (`tellus-internals.md` §11.2). Streams get this for free if their
  water goes through `NzInlandWater`'s step flags, or must set their own.
- **Risk to check in steps 1–3:** the chunk's height grid margin (4 blocks
  around the chunk) comes from the sampler, not the water regions; only
  the chunk's own 16 × 16 is copied back
  (`copyChunkTerrainSurfacesToHeightGrid`). `NzSurface` reads neighbours
  from that grid for the `face` mix, so a carved column just outside the
  chunk looks uncarved: a gorge or stream bank on a chunk border may show
  stone instead of the face mix. If it shows, fill the margin from
  `NzWater.column` in `NzSurface.forChunk` (NZ code, no hook).

## Step 1 — River steps without lips

**Done (23 Oct 2026, `0.8.3+nz.7`).** Decided: taller drops are small
waterfalls now (sill at most 1 block deeper, so they keep a ledge). Wall
by option (a). Headless and desktop checks passed (`testing.md`).
Lesson for step 2: vanilla makes sources in **air** at a step's
corners, which Tellus's source gate misses; the NZ mixin covers
it, so new kinds of flowing water must count as steps, or as a zone of
their own, in `NzWater.suppressesSourceConversion`.

Fixes the TERR-10 desktop finding (`testing.md`, §11.2 "River steps in
game"). Smallest step, and it builds the "let a step flow" mechanism that
streams (step 2) need on every slope.

- **Sill** (NZ code only, `NzInlandWater.compute`): a river column's floor
  is at most its own `top − depth` *and* at most the floor of the lower
  reach within a few blocks (e.g. a minimum filter over river floors,
  radius 2–3), so the bed runs on under a step.
- **Wall:** make the upper reach's edge flow. Options:
  - (a) Tellus's mechanism: one `// NZ:` hook in `isWaterfallSourceColumn`
    asks `NzWater` whether a horizontal neighbour's water surface is
    lower; the top block is then post-processed and vanilla spreads a thin
    sheet of flowing water over the lower reach. Cheap; the look is
    unknown until the desktop check.
  - (b) Place flowing water blocks (decreasing level) across the step
    directly in the generator: a short ramp. More control, more code in
    the column fill (an upstream file).
  - Start with (a); fall back to (b) if the sheet looks wrong.
- **Raise first:** multi-block drops (steep rivers). Do them now as
  small waterfalls (the same post-processing on a taller drop), or leave
  them as stacked 1-block steps until the desktop check shows they need it?
- **Check:** headless: no river column whose floor is above the lowest
  river floor within the radius plus `depth`; count of marked step
  blocks. Desktop: the river near spawn (`testing.md`, TERR-10 desktop
  check); does flowing water at chunk edges spill?

## Step 2 — Streams from the stream layer

**Done (23 Oct 2026, `0.8.3+nz.8`).** Decided: width (C), 1:1 capped at
the gully floor. The bed's never-rising level comes from a new pipeline
layer `stream_level` (decided: the generator can't tell downstream
reliably, and carves can be longer than a tile; hub `interfaces.md`).
Headless check passed (`testing.md`); the desktop check is open. Lessons
for step 3: gorge walls are dry columns, so they go through
`NzStreams`' ground and the sampler (first bullet above); `NzSurface`
already refills the chunk's border ring from `NzWater.column`, so the
border risk below is handled. Tellus keeps trees 5 blocks from any water,
creeks included (`tellus-internals.md` §11.2).

The biggest step. New class (e.g. `nz/terrain/NzStreams`), per tile with
a margin and cached like `NzInlandWater`; `NzWater.column` asks it for
land columns.

- **Raise first: width.** The open question from hub `interfaces.md`:
  streams are "1:1 like flora", but their gullies are 1:3 wide. Rough real
  widths: 0.5–3 m up to class ~50 (100 ha), 5–20 m from class 76
  (1,000 ha; ~8 % of stream blocks).
  - (A) **True 1:1:** most creeks 1 block, big streams 2–7 blocks. Matches
    "everything seen up close is 1:1"; big streams may be wider than their
    gully floor, so the bank has to be cut back (a wider, flatter floor
    than the LiDAR has).
  - (B) **1:3 like the landscape:** almost every stream 1 block, the
    biggest 2–3. Always fits the gully; next to 1:1 trees the big ones look
    like creeks.
  - (C) **1:1, capped at the gully floor** (the blocks within about 1
    block of the centreline's height): 1:1 where it fits, never widens the
    gully. More code (a floor-width measure per stream block).
  - Recommendation: (C), falling back to (A) for the first version if the
    floor measure is fiddly.
- **Bed and surface:** water 1 block deep (deeper for big classes), cut
  into the terrain so the banks hold it. The bed must never rise
  downstream: where the real heights rise (0.14 % of steps, up to 20 m:
  filled pits), carve down to the lowest level reached upstream
  (`interfaces.md`). Downstream = higher class, or equal class and lower
  height. Needs an order along each chain inside the tile window; chains
  that leave the window need a margin large enough or a rule at the edge
  (measure how far pits reach).
- **Flow:** on slopes a stream steps down every block or two. Use step 1's
  mechanism so each step flows (a riffle, not a staircase of walls).
- **Containment:** a stream water block must have a bank or water at its
  level on every side, or vanilla spills it. On side slopes the downhill
  bank may be too low: raise it, or lower the water. Count spills in the
  headless check.
- **Where streams end:** next to lake/river/sea water, at a glacier (no
  stream on ice) and at the data edge.
- **Check:** headless on test area 1: share of stream blocks wet; beds
  never rising downstream; no spills; time per region (TERR-18 measures
  speed later). Desktop: a forest creek and a big gravel stream near
  spawn.

## Step 3 — Slot gorges along streams

Narrow, deep, steep-walled cuts where a stream incises rock: real slots
are 1–5 m wide, so the 3 m LiDAR smooths them into a V. Carved in the same
per-tile pass as step 2 (terrain lowered beside the stream); the walls
get the `face` mix from `NzSurface` without new code.

- **Raise first: where gorges go.**
  - (a) **From the LiDAR's shape:** only where the channel is already
    incised (the stream block far below the ground 3–6 blocks away, a
    deep V); there the V becomes a slot. Data-guided, few false gorges;
    misses slots the LiDAR doesn't hint at.
  - (b) **By rule:** stream class range × along-stream gradient × cover
    (rock, bush), with noise for where a gorge starts and ends. More
    gorges, but some where the real land has none.
  - (c) Both: (a) everywhere, (b) to extend them a little.
  - Recommendation: (a), the brief says "guided by the data".
- **Shape limit:** the terrain is a height per column, so walls are
  vertical at most; real undercut walls would need a 3D carve after the
  fill (an extra pass in the column fill). Decide after seeing (a).
- **Check:** headless: count and length of gorges in test area 1, wall
  heights; nothing carved below the stream. Desktop: one gorge in bush.

## Step 4 — Boulders

1:1 rocks (1–4 blocks) on riverbeds and gravel flats, in stream beds,
at the foot of cliffs and scree, on moraine; moss-covered in forest.
Independent of steps 1–3 except for stream beds.

- **Raise first: how they are placed.**
  - (a) **An NZ decoration pass:** one `// NZ:` hook next to `placeTrees`
    (`EarthChunkGenerator`), placing rounded blobs from a position hash
    (like the palette mixes: no world-seed dependence), with densities and
    blocks in a data file (the surface palette or its own `.jsonc`) for
    tuning without a rebuild.
  - (b) **Vanilla features through NZ biomes:** waits for TERR-12, and
    biome-wide densities fit the data less well than per-column rules.
  - (c) **Bumps in the column fill:** no new pass, but a height per
    column can't make round rocks or rocks in water.
  - Recommendation: (a).
- **Blocks:** vanilla placeholders until MODS-17 (NZ greywacke, schist,
  granite); the rock type follows PIPE-15 later.
- **Check:** headless: boulders per hectare by surface; none floating or
  in trees' trunks. Desktop: a gravel riverbed and a forest stream.

## Step 5 — Cliff rock bands

On steep LiDAR faces (game slope over ~63°) the terrain is a smooth
staircase of columns. Rock bands give it 1:1 strata: ledges, steps,
small overhangs.

- **Raise first: material or shape.**
  - (a) **Material only:** the palette's `bands` pattern (TERR-11) already
    stripes faces; this step would then be palette tuning (TERR-11's
    tuning box) and nothing new.
  - (b) **Shape, height per column:** snap or jitter the top of steep
    columns to bands every few blocks, so the face shows ledges. No
    overhangs; small change in the per-tile pass or in `NzSurface`.
  - (c) **Shape, 3D:** after the fill, add and remove blocks on the face
    by a banded 3D noise: ledges and overhangs. Best look, most work and
    an extra pass in the column fill.
  - Also: granite (Fiordland) doesn't band; greywacke and schist do. With
    PIPE-15's `rock` layer the bands could follow the rock type, so (b) or
    (c) might wait for PIPE-15 and use the test-area-1 mix until then.
- **Check:** desktop: a cliff near Franz Josef.
- **Last:** move this plan's lasting lessons into `tellus-internals.md`
  (§11, §12) and `testing.md`, tick TERR-14 in both `steps.md` files,
  delete this plan.
