# Surface palette

Which blocks make up the ground: rock, scree, riverbeds, snow and ice, and the
soil under each kind of vegetation. TERR-3 worked out the rules; TERR-11
built them into the generator and tunes the blocks in game.

**Status (TERR-11, 23 Oct 2026):** the rules and thresholds come from the
test-area data (below) and are meant to last. The generator uses them
since `0.8.3+nz.6`, from the palette file
`mc262/src/main/resources/nz-terrain/surface-palette.jsonc`: **that file
is the source of truth** for rules, thresholds, blocks, mixes and depths;
the tables below are the reasoning behind its first version. The block
mixes are still the first guess: seen in saved chunks (`testing.md`), not
yet in game. (A WorldPainter prototype was planned in TERR-3 but dropped:
painting by hand was very slow, it can't show modded blocks or biome
colours, and every change means repainting.)

## Slopes: real vs in game

Slope here is the **real** slope: the pipeline's `slope.tif`, and in the
generator the slope computed from the height tiles with the same method
(Horn's 3 × 3; the tiles store no slope layer, hub `interfaces.md`). The
world is stretched 1.5×
vertically, so in game every slope is steeper: `tan(game) = 1.5 × tan(real)`.
WorldPainter measures slope on its block heights, so its slope filters use
**game** degrees.

| Real | Game | In blocks | Meaning |
|---|---|---|---|
| 15° | 22° | | Flat enough for loose gravel flats |
| 33.7° | 45° | 1-block steps | |
| 35° | 46° | | Angle of repose: scree can't lie steeper |
| 45° | 56° | | Snow mostly gone above this (data below) |
| 53° | 63° | 2-block steps | Reads as a cliff in game |
| 63° | 72° | 3-block steps | |

## What the data shows

Measured on all of test area 1 (LCDB v6 classes, 3 m heights and slopes).

- **Treeline.** Indigenous forest (69) is >90 % of land from 200 to 700 m,
  58 % at 800 m, 23 % at 1,000 m, gone by 1,300 m. Its canopy shrinks from a
  median of 13–16 m below 800 m to 4 m at 1,000 m. Above it: subalpine
  shrubland (55) peaks at 1,000–1,200 m, tall tussock (43) at 1,300–1,500 m,
  alpine herbfield (15) around 1,600 m. No beech: test area 1 is in the
  Westland beech gap.
- **Snowline.** Snow and ice (14) is over half the land from about 1,850 m
  (Y 988). South faces (shady in NZ) hold it about 150–200 m lower than north
  faces. Below 1,200 m class 14 is glacier tongues (Franz Josef, Fox), down
  to about 400 m.
- **Snow and slope.** Above 1,800 m, class 14 covers 96 % of slopes under
  10° real but only 34 % of slopes over 60°. LCDB polygons are coarse (about
  1 ha mapping unit), so they include steep rock inside snowfields.
- **Gravel and Rock (16)** is three different things:
  - below 300 m, almost all flatter than 15°: braided riverbeds and outwash
    (Waiho);
  - 600–1,000 m, 42 % flatter than 15°: valley-floor gravel and moraine
    (Tasman valley);
  - above 1,000 m, median 36–43°: scree and bluffs.
- **Steep vegetation.** The ground here is steep, and steeper still in game.
  41 % of land is steeper than 1-block steps, 10 % steeper than 2-block steps.
  Tall tussock has a median slope of 49° real (60° game): 67 % of it is
  above 1-block steps, 18 % above 2-block steps. Forest: 31 % and 5 %.
- **Landslides (12):** median slope 36°, at 150–1,100 m.
- **Sand and Gravel (10)** is the coast, at 0–7 m.

## Proposed palette

"Mix" percentages are the share of top blocks. Subsurface is what shows on
the vertical sides of steps, so it matters on steep ground. Below the
subsurface layers, the visible side of a step shows the palette's `face`
material (the rock mix, `schist`), not plain stone; mossy rock and
glacier ice have their own face. On a step 3 or more blocks tall
(`"bare_step"` in the file) the subsurface layers are left out, so only
the top block is soil above the face (TERR-14 step 3: dirt bands on
gorge walls).

### Rules, in order (later wins)

1. Each land block gets the surface type of its LCDB class (table below).
2. **Gravel and Rock (16)** by slope: < 22° game → gravel flats;
   22–46° → scree; > 46° → rock.
3. **Snow and Ice (14):** steeper than 56° game → rock with a dusting of
   snow; otherwise below 1,800 m (Y 963) → glacier ice, above → snow.
   1,800 m is about the glacier equilibrium line here: the snow survives
   summer above it, bare ice shows below it.
4. **Vegetated classes** steeper than 63° game (2-block steps) → rock face:
   mossy below the treeline (about 1,000 m, Y 563), bare above.
5. Water floors from the water masks.

### Surface types

| Surface | LCDB classes | Top mix | Subsurface |
|---|---|---|---|
| Rock (schist/greywacke) | 16 steep; steep 14; steep vegetation above treeline | stone 60, andesite 25, tuff 15 | stone |
| Mossy rock face | steep vegetation below treeline | stone 50, mossy cobblestone 25, moss block 25 | stone |
| Scree | 16, 22–46° | gravel 60, andesite 20, stone 10, cobblestone 10 | gravel 2, then stone |
| Gravel flats (riverbed, outwash, moraine) | 16 flat, 21 above water | gravel 85, andesite 10, stone 5 | gravel 3, then stone |
| Landslide | 12 | coarse dirt 40, gravel 40, stone 20 | stone |
| Snow | 14 above Y 963 | snow block | snow block 3, then packed ice |
| Glacier ice | 14 below Y 963 | packed ice 80, blue ice 10, gravel 10 | packed ice |
| Alpine herbfield | 15 | grass block 60, stone 15, moss block 15, coarse dirt 10 | dirt 1, then stone |
| Tall tussock | 43 | grass block 80, coarse dirt 20 | dirt 1–2, then stone |
| Subalpine shrubland | 55 | grass block 60, podzol 25, coarse dirt 15 | dirt 2, then stone |
| Forest floor | 69, 54 | podzol 45, coarse dirt 20, moss block 15, rooted dirt 10, grass block 10 | dirt 3, then stone |
| Mānuka scrub | 52, 50 | grass block 60, podzol 20, coarse dirt 20 | dirt 3 |
| Wetland | 45, 47 | grass block 50, mud 30, moss block 20 | mud 2, then dirt |
| Salt marsh | 46 | mud 60, grass block 40 | mud |
| Beach | 10 | gravel 70, andesite 15, sand 15 | gravel |
| Riverbed (under water) | water = river | gravel 90, andesite 10 | gravel |
| Stream bed (TERR-14, first guess) | stream water on land | gravel 60, cobblestone 25, mossy cobblestone 15 | gravel 1 |
| Lake floor | water = lake | mud 50, clay 30, gravel 20 | clay |
| Sea floor | water = sea | gravel 60, sand 40 | sand |
| Rewilded (placeholder) | 1, 2, 5, 6, 30, 33, 40, 41, 51, 56, 64, 68, 71, 0 (no polygon) | as forest floor | |

Classes not in test area 1 got a surface in TERR-11 by judgement, not from
data (check them with test area 2, TERR-16): depleted grassland 44 →
tussock; fernland 50 and matagouri/grey scrub 58 → mānuka scrub; flaxland
47 and peat shrubland 80 → wetland; mangrove 70 → salt marsh; dune
shrubland 81 → beach; LCDB lake/river 20, 21 on dry land → gravel flats,
estuary 22 → salt marsh.

Notes:

- **Tussock colour** comes from the grass tint of the biome (MODS-19), not
  from the block. Tussock should read golden, not green.
- **Steep tussock and shrubland** keep their grass top but only one or two
  blocks of soil, so the sides of steps show rock. That looks like a
  vegetated rocky slope, without turning 18 % of the tussock into bare rock.
- **Rejected: light grey concrete powder as grey sand.** It looks right, but
  it turns into concrete when water touches it.
- Boulders, cliff bands and streams are TERR-14, not this palette.

## NZ ground blocks

Vanilla can't show some NZ ground well. Custom blocks for these come from
the `aotearoa` mod in `nz-mods` (MODS-17), which this generator requires
(hub `interfaces.md`), so there are no permanent fallbacks. The vanilla
mixes below are placeholders until MODS-17 lands; then its block IDs
(`aotearoa:…`) go into the palette file, and `nz-terrain` adds the
`aotearoa` dependency.

| Custom block (candidate) | Where | Vanilla placeholder |
|---|---|---|
| Grey sand | West Coast beaches | gravel 70, andesite 15, sand 15 (the beach mix) |
| Black ironsand | Taranaki, west Auckland, parts of the West Coast | gravel 70, andesite 30 (no dark sand in vanilla) |
| Schist (stone, cobbles) | Alpine Fault zone, Otago | the rock mix above |
| Greywacke (stone, cobbles) | Main divide, Canterbury, the axial ranges | stone 50, andesite 30, cobbled deepslate 20 |

The list is a starting point; MODS-17 decides the set. More rock types
come with the `rock` tile layer (PIPE-15, below).

## Tuning in game (TERR-11)

- LCDB edges are coarse at 3 m. The class is the base and slope refines
  it, as in rules 2–4. Aspect could later move snow lower on shady
  south faces and higher on north faces; LCDB already holds most of that.
- **Tuning box:** x −76,800 to −75,776, z 94,208 to 95,232 (1,024 × 1,024,
  four regions, Y 179–995). It has every surface type except snow above
  the glacier line: Waiho riverbed, forest, subalpine shrubland, tussock,
  scree, bluffs and the Franz Josef glacier tongue. Teleport targets:
  Sentinel Rock `-76155 300 94405`, glacier terminus `-75971 350 95066`.
  For snow, go up the glacier to the névé around x −74,384, z 96,496
  (Y≈1,140). The NZ preset spawns at Sentinel Rock, inside the box.
- **How to tune** (works in the dev client and in a launcher instance):
  1. Copy `surface-palette.jsonc` from the repo to
     `<game folder>/config/nz-terrain-surface.jsonc`. While that file
     exists it wins over the mod's own; the log and chat say so at every
     world start.
  2. Edit it (its comments explain the format), then create a new New
     Zealand world, or fly to chunks not generated yet. The palette is
     read at every world start, so no restart of the game is needed;
     chunks already generated keep their old blocks.
  3. A mistake (unknown key, block or surface) shows in chat and the log,
     and the world uses the mod's own palette instead.
  4. When happy, copy the file back over the repo's
     `surface-palette.jsonc`, delete the config copy, and record the
     choices below.
- **To decide in game:**
  - Rock colour: the schist mix (lighter, a little green from tuff, like
    the schist near the Alpine Fault) vs the greywacke mix (stone 50,
    andesite 30, cobbled deepslate 20; darker, like the main divide). Both
    are in the file's `mixes`.
  - Steep vegetation cut-off: 63° game (2-block steps) vs 56°.
  - Snow vs glacier ice: 1,800 m vs a lower line.
  - Beach: gravel vs gravel and sand.
  - Noise vs patches for each mix (the file starts with patches for
    soils, noise for rock and scree), and patch sizes.
  - Rock in horizontal bands like strata on cliffs (`"pattern": "bands"`
    on the face mix; also TERR-14).
  - How deep the soil goes on steep ground (`under` depths). In the
    TERR-11 check, about 30 % of the sky-facing step sides in the tuning
    box were dirt, mostly the forest's 3 dirt layers: a candidate for
    thinner soil.
  - The snowy rock's snow cover (60 % snow layers in patches).
- **Final choices:** not tuned yet.

## Open questions

- **Rock type by geology.** One rock mix fits test area 1 (schist and
  greywacke). The rest of NZ differs: Fiordland granite and gneiss, the
  volcanic plateau, Otago schist, limestone. HUB-2 reserved an optional
  `rock` tile layer; PIPE-15 checks the GNS geological map's (QMAP)
  licence and fills it. Until then, and wherever the layer is missing, the
  generator uses test area 1's mix. QMAP boundaries are only good to about
  250 m, so blend rock mixes across them rather than switching at a line.
- **Retexture instead of new blocks?** A resource pack in `nz-modpack`
  could recolour vanilla blocks (e.g. sand), but it changes them
  everywhere, outside NZ too. New blocks are the plan; decide per block in
  MODS-17.
