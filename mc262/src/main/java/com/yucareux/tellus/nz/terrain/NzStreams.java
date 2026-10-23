package com.yucareux.tellus.nz.terrain;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.nz.tiles.NzTileFormat;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Optional;

/**
 * The streams of an NZ world (TERR-14) as block heights: the water surface and floor of each stream block, and the
 * banks raised beside them, per level-0 tile with a margin from the neighbouring tiles, and cached. Streams exist at
 * level 0 only (the {@code streams} layer has no coarse levels), so DH's coarser LODs don't show them. One instance
 * belongs to one {@link NzInlandWater}, whose lakes and rivers the streams flow into.
 *
 * <ul>
 *    <li>Course and level: the {@code streams} layer's centrelines, at the water level of the {@code stream_level}
 *    layer, rounded to the nearest block like lakes and rivers. The pipeline made that level never rise downstream
 *    (pits carved, a mouth raised to the water it flows into), and rounding keeps the order.</li>
 *    <li>Width (option C, decided 23 Oct 2026): 1:1 from the size class, {@link #WIDTH_METRES_PER_SQRT_KM2} times the
 *    square root of the catchment in km², but never wider than the gully floor: a stream spreads from its centreline
 *    only over land at most {@link #FLOOR_TOLERANCE_BLOCKS} above the centreline's ground. A block within reach of
 *    several centreline blocks takes the nearest one's level (the lowest on a tie), so a stream is level across.</li>
 *    <li>Corners: the centrelines are 8-connected, and water doesn't flow through a corner. Where two water blocks
 *    (stream, lake, river or sea) touch only at a corner, the lower of the two land blocks between them becomes stream
 *    water at the higher of their levels.</li>
 *    <li>Floor: 1 block deep at the edge, 2 inside a stream from size class {@link #DEEPER_FROM_CLASS}, 3 from
 *    {@link #DEEPEST_FROM_CLASS}; 1 block deeper with lower water within {@link NzInlandWater#SILL_RADIUS_BLOCKS}, as
 *    in rivers, so the bed runs on under a step. Never above the ground that was there.</li>
 *    <li>Banks: land beside stream water and lower than its surface is raised to it, so the water can't spill.</li>
 *    <li>Slot gorges (TERR-14 step 3, decided 23 Oct 2026): where the LiDAR already shows a deep, steep V along a
 *    stream (option (a)), the 3 m data has smoothed a slot into it. There the V's sides are filled up to its rim, so
 *    the stream runs between vertical walls. A centreline block is in a gorge when
 *    the lower side is at least {@link #GORGE_NEAR_DEPTH_BLOCKS} higher {@link #GORGE_NEAR_RADIUS_BLOCKS} blocks
 *    out and at least {@link #GORGE_DEPTH_BLOCKS} higher {@link #GORGE_RADIUS_BLOCKS} out, across the stream, in a
 *    run of at least {@link #GORGE_MIN_RUN_BLOCKS}. The rim is that lower side's height
 *    {@link #GORGE_RADIUS_BLOCKS} out, at most {@link #wallCap} above the stream's ground (a few blocks for a small
 *    creek, whose V above stays a vegetated gully; up to {@link #GORGE_MAX_WALL_BLOCKS} for a big stream), and rises
 *    by {@link #GORGE_TAPER_BLOCKS} per block from the ends of a run. Land within {@link #GORGE_RADIUS_BLOCKS} of a
 *    gorge's centreline (nearer to it than to any other) is raised to the rim, by at most
 *    {@link #GORGE_MAX_WALL_BLOCKS}, give or take {@link #rimJitter} so the walls aren't flat planes. Except the
 *    ledges: land within the ledge width of the stream's water is a gravel ledge at most 1 block above that water
 *    ({@link #isGorgeLedge}: {@code NzSurface} gives it the stream bed). The width is {@link #ledgeWidth} (0–2 by
 *    noise along the stream) at the column's centreline, 1 more on the inside of a bend and 1 less on the outside
 *    ({@link #bendSide}), so in places the water runs from wall to wall and gravel bars lie on the inside of bends. A
 *    wall column with ledge or water on 3 of its 4 sides becomes ledge too: no pillars. A gorge is level-0 detail
 *    like the streams.</li>
 *    <li>Steps: stream water with lower water (stream, lake, river or sea) on one of its four sides flows, as river
 *    steps do ({@link NzWater#isStepSource}).</li>
 * </ul>
 */
final class NzStreams {
   /** Surface or ground value where there is no stream water or no raised bank. */
   static final int NONE = Short.MIN_VALUE;
   /** Real width of a stream: this many metres times the square root of its catchment in km² (1 km² → 2 m). */
   static final double WIDTH_METRES_PER_SQRT_KM2 = 2.0;
   static final int MAX_WIDTH_BLOCKS = 31;
   /** A stream spreads over land at most this many blocks above its centreline's ground: the gully floor. */
   static final int FLOOR_TOLERANCE_BLOCKS = 1;
   /** Size classes from which the inside of a stream is 2 (1,000 ha) and 3 (10,000 ha) blocks deep. */
   static final int DEEPER_FROM_CLASS = 76;
   static final int DEEPEST_FROM_CLASS = 101;
   /** A gorge's V, measured across the stream: this deep this far out, and deeper further out (the rim). */
   static final int GORGE_NEAR_RADIUS_BLOCKS = 3;
   static final int GORGE_NEAR_DEPTH_BLOCKS = 3;
   static final int GORGE_RADIUS_BLOCKS = 6;
   static final int GORGE_DEPTH_BLOCKS = 8;
   static final int GORGE_MAX_WALL_BLOCKS = 16;
   /** Wall height by size class: {@link #SMALL_WALL_BLOCKS} up to this class (16 ha), the full height from this one (1,000 ha). */
   static final int SMALL_WALL_UP_TO_CLASS = 30;
   static final int FULL_WALL_FROM_CLASS = 76;
   static final int SMALL_WALL_BLOCKS = 4;
   /** Shorter runs of gorge blocks are left as they are; longer ones rise this many blocks per block from each end. */
   static final int GORGE_MIN_RUN_BLOCKS = 5;
   static final int GORGE_TAPER_BLOCKS = 2;
   /** The noise that varies ledges and rims: lattice points this many blocks apart, and the rim's range (±). */
   static final int LEDGE_NOISE_PERIOD_BLOCKS = 6;
   static final int RIM_NOISE_PERIOD_BLOCKS = 8;
   static final int RIM_JITTER_BLOCKS = 2;
   /** Bends: the centreline within this many blocks (a square) shows which way a stream curves. */
   static final int BEND_RADIUS_BLOCKS = 4;
   /** The mean offset of that centreline (blocks) from which a stream counts as bending. */
   static final double BEND_MIN_OFFSET_BLOCKS = 0.5;
   private static final long LEDGE_SALT = 0x6C65646765L;
   private static final long RIM_SALT = 0x72696DL;
   private static final int MAX_RADIUS_BLOCKS = MAX_WIDTH_BLOCKS / 2;
   /**
    * A tile pixel can be reached from centrelines up to {@link #MAX_RADIUS_BLOCKS} away, over paths inside their
    * reach (twice that), and needs its neighbours' water for corners, steps and sills.
    */
   private static final int MARGIN = 2 * MAX_RADIUS_BLOCKS + NzInlandWater.SILL_RADIUS_BLOCKS + 2;
   /** Height values are metres × 8. */
   private static final double STEPS_PER_METRE = 8.0;
   private static final int CACHE_TILES = 64;
   /** "No water here" in the water-top arrays. */
   private static final int DRY = Integer.MAX_VALUE;

   private final NzInlandWater inland;
   private final EarthGeneratorSettings settings;
   private final LoadingCache<Key, Optional<Tile>> cache;

   NzStreams(NzInlandWater inland, EarthGeneratorSettings settings) {
      this.inland = inland;
      this.settings = settings;
      this.cache = CacheBuilder.newBuilder().maximumSize(CACHE_TILES).build(CacheLoader.from(this::compute));
   }

   NzInlandWater inland() {
      return this.inland;
   }

   /** Y of the top water block of a stream at block (x, z); {@link #NONE} without one. */
   int surfaceY(int x, int z) {
      Tile tile = this.tile(x, z);
      return tile == null ? NONE : tile.surface[index(x, z)];
   }

   /** Under stream water the floor (the top solid block); on land the raised top of a bank, or {@link #NONE}. */
   int groundY(int x, int z) {
      Tile tile = this.tile(x, z);
      return tile == null ? NONE : tile.ground[index(x, z)];
   }

   /** Whether land block (x, z) is a ledge between a gorge's water and its wall. */
   boolean isGorgeLedge(int x, int z) {
      Tile tile = this.tile(x, z);
      return tile != null && tile.ledge.get(index(x, z));
   }

   /** Highest wall in blocks over a gorge stream of {@code sizeClass}: small creeks low, big streams up to the most. */
   static int wallCap(int sizeClass) {
      double t = (sizeClass - SMALL_WALL_UP_TO_CLASS) / (double) (FULL_WALL_FROM_CLASS - SMALL_WALL_UP_TO_CLASS);
      return (int) Math.round(SMALL_WALL_BLOCKS + Math.clamp(t, 0.0, 1.0) * (GORGE_MAX_WALL_BLOCKS - SMALL_WALL_BLOCKS));
   }

   /**
    * Which side of a bend land at offset (dx, dz) from a centreline block lies on, given the mean offset (bx, bz) of the
    * centreline around that block, which points to the inside of a bend: 1 inside, -1 outside, 0 on a straight reach or
    * along the stream.
    */
   static int bendSide(int dx, int dz, double bx, double bz) {
      double bend = Math.hypot(bx, bz);
      double length = Math.hypot(dx, dz);
      if (bend < BEND_MIN_OFFSET_BLOCKS || length == 0.0) {
         return 0;
      }
      double side = (dx * bx + dz * bz) / (bend * length);
      return side > 0.3 ? 1 : side < -0.3 ? -1 : 0;
   }

   /** Width in blocks of the ledges at centreline block (x, z) of a gorge: 0, 1 or 2, varying smoothly along it. */
   static int ledgeWidth(int x, int z) {
      double noise = NzSurfaceMix.smoothNoise(LEDGE_SALT, x, z, LEDGE_NOISE_PERIOD_BLOCKS);
      return noise < -0.15 ? 0 : noise < 0.3 ? 1 : 2;
   }

   /** How far a gorge wall's top at block (x, z) lies above (or below) the rim: ±{@link #RIM_JITTER_BLOCKS}. */
   static int rimJitter(int x, int z) {
      double noise = NzSurfaceMix.smoothNoise(RIM_SALT, x, z, RIM_NOISE_PERIOD_BLOCKS);
      return (int) Math.clamp(Math.round(noise * (RIM_JITTER_BLOCKS + 0.5)), -RIM_JITTER_BLOCKS, RIM_JITTER_BLOCKS);
   }

   /** Whether the stream block (x, z) has lower water on one of its four sides. */
   boolean isStep(int x, int z) {
      Tile tile = this.tile(x, z);
      return tile != null && tile.step.get(index(x, z));
   }

   private Tile tile(int x, int z) {
      return this.cache.getUnchecked(new Key(x >> NzTileFormat.TILE_SHIFT, z >> NzTileFormat.TILE_SHIFT)).orElse(null);
   }

   private static int index(int x, int z) {
      int mask = NzTileFormat.TILE_PIXELS - 1;
      return (z & mask) * NzTileFormat.TILE_PIXELS + (x & mask);
   }

   /** Width in blocks of a stream of {@code sizeClass} (1:1: one block per metre). */
   static int widthBlocks(int sizeClass) {
      double hectares = Math.pow(10.0, (sizeClass - 1) / 25.0);
      double metres = WIDTH_METRES_PER_SQRT_KM2 * Math.sqrt(hectares / 100.0);
      return (int) Math.clamp(Math.round(metres), 1, MAX_WIDTH_BLOCKS);
   }

   /** Depth in blocks inside a stream of {@code sizeClass}, away from its edge and steps. */
   static int depthBlocks(int sizeClass) {
      return sizeClass >= DEEPEST_FROM_CLASS ? 3 : sizeClass >= DEEPER_FROM_CLASS ? 2 : 1;
   }

   /** Surfaces, floors, banks and steps of one tile; empty where no stream comes within the margin. */
   private Optional<Tile> compute(Key key) {
      NzTiles tiles = this.inland.tiles();
      int size = NzTileFormat.TILE_PIXELS + 2 * MARGIN;
      int originX = key.tx() * NzTileFormat.TILE_PIXELS - MARGIN;
      int originZ = key.tz() * NzTileFormat.TILE_PIXELS - MARGIN;
      int[] streams = NzInlandWater.readWindow(tiles, NzTileLayer.STREAMS, 0, originX, originZ, size);
      if (Arrays.stream(streams).allMatch(value -> value == 0)) {
         return Optional.empty();
      }
      int[] level = NzInlandWater.readWindow(tiles, NzTileLayer.STREAM_LEVEL, 0, originX, originZ, size);
      int[] height = NzInlandWater.readWindow(tiles, NzTileLayer.HEIGHT, 0, originX, originZ, size);
      int[] water = NzInlandWater.readWindow(tiles, NzTileLayer.WATER, 0, originX, originZ, size);

      // Per window pixel: the ground's Y on land (DRY outside the data), and the top water block of lakes, rivers
      // and the sea (DRY elsewhere).
      int area = size * size;
      int[] ground = new int[area];
      int[] other = new int[area];
      int seaLevel = this.inland.seaLevel();
      for (int pz = 0; pz < size; pz++) {
         int z = originZ + pz;
         for (int px = 0; px < size; px++) {
            int i = pz * size + px;
            int w = water[i];
            ground[i] = NzTileLayer.HEIGHT.isNodata(height[i]) ? DRY : NzTerrainSampler.blockY(this.settings, height[i] / STEPS_PER_METRE, z);
            if (NzSeaFloor.isSea(w)) {
               other[i] = seaLevel;
            } else if (w == NzTerrainSampler.WATER_LAKE || w == NzTerrainSampler.WATER_RIVER) {
               int surface = this.inland.surfaceY(0, originX + px, z);
               other[i] = surface == NzInlandWater.NONE ? DRY : surface;
            } else {
               other[i] = DRY;
            }
         }
      }

      // Each centreline claims itself, and wide ones the gully floor within their reach.
      int[] surface = new int[area];
      Arrays.fill(surface, NONE);
      int[] distance = new int[area];
      Arrays.fill(distance, Integer.MAX_VALUE);
      byte[] depth = new byte[area];
      int[] visited = new int[area];
      int[] queue = new int[area];
      int serial = 0;
      for (int i = 0; i < area; i++) {
         if (streams[i] == 0 || NzTileLayer.STREAM_LEVEL.isNodata(level[i]) || water[i] != NzTerrainSampler.WATER_LAND || ground[i] == DRY) {
            continue;
         }
         int cx = i % size;
         int cz = i / size;
         int y = NzInlandWater.waterY(this.settings, level[i] / STEPS_PER_METRE, originZ + cz);
         int deep = depthBlocks(streams[i]);
         claim(surface, distance, depth, i, 0, y, deep);
         int width = widthBlocks(streams[i]);
         if (width < 3) {
            continue;
         }
         double reach = width * width / 4.0;
         int highest = ground[i] + FLOOR_TOLERANCE_BLOCKS;
         serial++;
         visited[i] = serial;
         queue[0] = i;
         for (int head = 0, tail = 1; head < tail; head++) {
            int p = queue[head];
            int ppx = p % size;
            int ppz = p / size;
            for (int side = 0; side < 4; side++) {
               int qx = ppx + (side == 0 ? 1 : side == 1 ? -1 : 0);
               int qz = ppz + (side == 2 ? 1 : side == 3 ? -1 : 0);
               if (qx < 0 || qx >= size || qz < 0 || qz >= size) {
                  continue;
               }
               int q = qz * size + qx;
               if (visited[q] == serial) {
                  continue;
               }
               visited[q] = serial;
               int d2 = (qx - cx) * (qx - cx) + (qz - cz) * (qz - cz);
               if (d2 >= reach || water[q] != NzTerrainSampler.WATER_LAND || ground[q] > highest) {
                  continue;
               }
               claim(surface, distance, depth, q, d2, y, deep);
               queue[tail++] = q;
            }
         }
      }

      // Corners: the lower land block between two water blocks that touch only diagonally. Ties go to the lower
      // index, so both water blocks pick the same one.
      int[] corner = new int[area];
      Arrays.fill(corner, NONE);
      for (int pz = 1; pz < size - 1; pz++) {
         for (int px = 1; px < size - 1; px++) {
            int i = pz * size + px;
            if (surface[i] == NONE) {
               continue;
            }
            for (int dz = -1; dz <= 1; dz += 2) {
               for (int dx = -1; dx <= 1; dx += 2) {
                  int j = i + dz * size + dx;
                  int top = top(surface, other, j);
                  int a = i + dx;
                  int b = i + dz * size;
                  if (top == DRY || top(surface, other, a) != DRY || top(surface, other, b) != DRY) {
                     continue;
                  }
                  int c = ground[a] < ground[b] ? a : ground[b] < ground[a] ? b : Math.min(a, b);
                  if (water[c] == NzTerrainSampler.WATER_LAND && ground[c] != DRY) {
                     corner[c] = Math.max(corner[c], Math.max(surface[i], top));
                  }
               }
            }
         }
      }
      for (int i = 0; i < area; i++) {
         if (corner[i] != NONE) {
            surface[i] = corner[i];
            depth[i] = 1;
         }
      }

      int[] wall = new int[area];
      int[] ledge = new int[area];
      gorges(streams, level, water, ground, surface, size, originX, originZ, wall, ledge);

      short[] tileSurface = new short[NzTileFormat.TILE_AREA];
      short[] tileGround = new short[NzTileFormat.TILE_AREA];
      BitSet tileStep = new BitSet(NzTileFormat.TILE_AREA);
      BitSet tileLedge = new BitSet(NzTileFormat.TILE_AREA);
      int sill = NzInlandWater.SILL_RADIUS_BLOCKS;
      for (int row = 0; row < NzTileFormat.TILE_PIXELS; row++) {
         for (int column = 0; column < NzTileFormat.TILE_PIXELS; column++) {
            int i = (row + MARGIN) * size + column + MARGIN;
            int out = row * NzTileFormat.TILE_PIXELS + column;
            int y = surface[i];
            tileSurface[out] = (short) NONE;
            tileGround[out] = (short) NONE;
            if (y == NONE) {
               // Land: a bank where stream water beside it stands higher, or a gorge wall.
               int bank = Math.max(Math.max(surface[i - 1], surface[i + 1]), Math.max(surface[i - size], surface[i + size]));
               if (water[i] != NzTerrainSampler.WATER_LAND || ground[i] == DRY) {
                  continue;
               }
               if (ledge[i] != NONE) {
                  tileGround[out] = (short) Math.max(bank, ledge[i]);
                  tileLedge.set(out);
                  continue;
               }
               int raised = Math.max(bank, wall[i]);
               if (raised > ground[i]) {
                  tileGround[out] = (short) Math.min(raised, Short.MAX_VALUE);
               }
               continue;
            }
            boolean edge = false;
            for (int j : new int[] {i - 1, i + 1, i - size, i + size}) {
               int top = top(surface, other, j);
               edge |= top == DRY;
               if (top < y) {
                  tileStep.set(out);
               }
            }
            int deep = edge ? 1 : depth[i];
            int lowest = DRY;
            for (int dz = -sill; dz <= sill; dz++) {
               for (int dx = -sill; dx <= sill; dx++) {
                  lowest = Math.min(lowest, top(surface, other, i + dz * size + dx));
               }
            }
            if (lowest < y) {
               deep++;
            }
            tileSurface[out] = (short) Math.clamp(y, Short.MIN_VALUE + 1, Short.MAX_VALUE);
            tileGround[out] = (short) Math.clamp(Math.min(y - deep, ground[i]), Short.MIN_VALUE + 1, Short.MAX_VALUE);
         }
      }
      return Optional.of(new Tile(tileSurface, tileGround, tileStep, tileLedge));
   }

   /**
    * Fills {@code wall} with the raised top of each window pixel in a slot gorge's wall and {@code ledge} with the top
    * of each ledge, {@link #NONE} elsewhere (rules in the class Javadoc). Only centrelines at least
    * {@link #GORGE_RADIUS_BLOCKS} inside the window are measured; the margin is wide enough that every run, end and
    * centreline that can reach a tile pixel lies inside it.
    */
   private static void gorges(
      int[] streams, int[] level, int[] water, int[] ground, int[] surface, int size, int originX, int originZ, int[] wall, int[] ledge
   ) {
      int area = size * size;
      int r = GORGE_RADIUS_BLOCKS;
      // Per centreline pixel: its wall height if its V is deep enough, 0 if not, -1 for no centreline.
      int[] height = new int[area];
      Arrays.fill(height, -1);
      for (int pz = r; pz < size - r; pz++) {
         for (int px = r; px < size - r; px++) {
            int i = pz * size + px;
            if (streams[i] == 0 || NzTileLayer.STREAM_LEVEL.isNodata(level[i]) || surface[i] == NONE || ground[i] == DRY) {
               continue;
            }
            int far = vDepth(water, ground, surface, size, i, r);
            boolean deep = vDepth(water, ground, surface, size, i, GORGE_NEAR_RADIUS_BLOCKS) >= GORGE_NEAR_DEPTH_BLOCKS && far >= GORGE_DEPTH_BLOCKS;
            height[i] = deep ? Math.min(far, wallCap(streams[i])) : 0;
         }
      }

      // Runs: 8-connected gorge pixels. Short ones are dropped; the rest rise from their ends, i.e. from gorge pixels
      // next to a centreline that isn't one, by the distance along the run (a run that ends in water keeps its walls).
      int[] along = new int[area];
      Arrays.fill(along, Integer.MAX_VALUE);
      int[] run = new int[area];
      int[] queue = new int[area];
      boolean[] seen = new boolean[area];
      for (int start = 0; start < area; start++) {
         if (height[start] <= 0 || seen[start]) {
            continue;
         }
         int tail = 0;
         queue[tail++] = start;
         seen[start] = true;
         for (int head = 0; head < tail; head++) {
            int p = queue[head];
            for (int q : neighbours(p, size)) {
               if (height[q] > 0 && !seen[q]) {
                  seen[q] = true;
                  queue[tail++] = q;
               }
            }
         }
         if (tail < GORGE_MIN_RUN_BLOCKS) {
            for (int k = 0; k < tail; k++) {
               height[queue[k]] = 0;
            }
            continue;
         }
         System.arraycopy(queue, 0, run, 0, tail);
         int ends = 0;
         for (int k = 0; k < tail; k++) {
            int p = run[k];
            for (int q : neighbours(p, size)) {
               if (height[q] == 0) {
                  along[p] = 0;
                  queue[ends++] = p;
                  break;
               }
            }
         }
         for (int head = 0; head < ends; head++) {
            int p = queue[head];
            for (int q : neighbours(p, size)) {
               if (height[q] > 0 && along[q] == Integer.MAX_VALUE) {
                  along[q] = along[p] + 1;
                  queue[ends++] = q;
               }
            }
         }
         for (int k = 0; k < tail; k++) {
            int p = run[k];
            if (along[p] != Integer.MAX_VALUE) {
               height[p] = Math.min(height[p], GORGE_TAPER_BLOCKS * (along[p] + 1));
            }
         }
      }

      // Land takes the rim of its nearest centreline (its owner), if that one is in a gorge.
      Arrays.fill(wall, NONE);
      Arrays.fill(ledge, NONE);
      int[] distance = new int[area];
      Arrays.fill(distance, Integer.MAX_VALUE);
      int[] owner = new int[area];
      Arrays.fill(owner, -1);
      for (int c = 0; c < area; c++) {
         if (height[c] < 0) {
            continue;
         }
         int cx = c % size;
         int cz = c / size;
         int rim = height[c] > 0 ? ground[c] + height[c] : NONE;
         for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
               int d2 = dx * dx + dz * dz;
               int q = (cz + dz) * size + cx + dx;
               if (d2 > r * r || d2 > distance[q] || d2 == distance[q] && rim >= wall[q]) {
                  continue;
               }
               distance[q] = d2;
               wall[q] = rim;
               owner[q] = c;
            }
         }
      }

      // Near the water a column is a ledge: within its owner's ledge width, wider on the inside of a bend.
      int b = BEND_RADIUS_BLOCKS;
      for (int pz = r + b; pz < size - r - b; pz++) {
         for (int px = r + b; px < size - r - b; px++) {
            int i = pz * size + px;
            if (wall[i] == NONE) {
               continue;
            }
            if (water[i] != NzTerrainSampler.WATER_LAND || surface[i] != NONE || ground[i] == DRY) {
               wall[i] = NONE;
               continue;
            }
            int c = owner[i];
            int cx = c % size;
            int cz = c / size;
            double bx = 0.0;
            double bz = 0.0;
            int count = 0;
            for (int dz = -b; dz <= b; dz++) {
               for (int dx = -b; dx <= b; dx++) {
                  if (height[c + dz * size + dx] >= 0) {
                     bx += dx;
                     bz += dz;
                     count++;
                  }
               }
            }
            int width = ledgeWidth(originX + cx, originZ + cz) + bendSide(px - cx, pz - cz, bx / count, bz / count);
            int near = nearWater(surface, size, i, width);
            if (near != NONE) {
               ledge[i] = Math.min(ground[i], near + 1);
            }
         }
      }
      // Lone wall columns between ledges and water become ledge (judged on the ledges before this pass, so the result
      // doesn't depend on the order of the scan); the rest of the wall takes its jittered rim.
      boolean[] ledgeBefore = new boolean[area];
      for (int i = 0; i < area; i++) {
         ledgeBefore[i] = ledge[i] != NONE;
      }
      for (int pz = 2; pz < size - 2; pz++) {
         for (int px = 2; px < size - 2; px++) {
            int i = pz * size + px;
            if (wall[i] == NONE || ledgeBefore[i]) {
               continue;
            }
            // A column below its centreline's ground (beside a waterfall) is raised by at most a wall's height.
            int top = Math.min(wall[i] + rimJitter(originX + px, originZ + pz), ground[i] + GORGE_MAX_WALL_BLOCKS);
            int open = 0;
            for (int q : new int[] {i - 1, i + 1, i - size, i + size}) {
               open += ledgeBefore[q] || surface[q] != NONE ? 1 : 0;
            }
            // Only a raised column can be a pillar; land the gorge leaves as it is stays.
            int near = open >= 3 && top > ground[i] ? nearWater(surface, size, i, 2) : NONE;
            if (near != NONE) {
               ledge[i] = Math.min(ground[i], near + 1);
               continue;
            }
            wall[i] = top;
         }
      }
      for (int i = 0; i < area; i++) {
         if (ledge[i] != NONE) {
            wall[i] = NONE;
         }
      }
   }

   /** The highest stream water within {@code reach} blocks (a square) of window pixel {@code i}; {@link #NONE} without. */
   private static int nearWater(int[] surface, int size, int i, int reach) {
      int near = NONE;
      for (int dz = -reach; dz <= reach; dz++) {
         for (int dx = -reach; dx <= reach; dx++) {
            near = Math.max(near, surface[i + dz * size + dx]);
         }
      }
      return near;
   }

   /**
    * How far the land rises {@code r} blocks out on both sides of centreline pixel {@code c}: the lower side, along the
    * axis (of four) where that is highest; {@link Integer#MIN_VALUE} where no axis has land without water on both sides.
    */
   private static int vDepth(int[] water, int[] ground, int[] surface, int size, int c, int r) {
      int k = (int) Math.round(r / Math.sqrt(2.0));
      int best = Integer.MIN_VALUE;
      for (int offset : new int[] {r, r * size, k * size + k, k * size - k}) {
         int a = c + offset;
         int b = c - offset;
         if (isDryLand(water, ground, surface, a) && isDryLand(water, ground, surface, b)) {
            best = Math.max(best, Math.min(ground[a], ground[b]) - ground[c]);
         }
      }
      return best;
   }

   private static boolean isDryLand(int[] water, int[] ground, int[] surface, int i) {
      return water[i] == NzTerrainSampler.WATER_LAND && ground[i] != DRY && surface[i] == NONE;
   }

   /** The 8 neighbours of window pixel {@code i}, which must not be on the window's edge. */
   private static int[] neighbours(int i, int size) {
      return new int[] {i - size - 1, i - size, i - size + 1, i - 1, i + 1, i + size - 1, i + size, i + size + 1};
   }

   /** Window pixel {@code q} becomes water of the centreline {@code d2} (squared blocks) away, if that one is nearer. */
   private static void claim(int[] surface, int[] distance, byte[] depth, int q, int d2, int y, int deep) {
      if (d2 < distance[q] || d2 == distance[q] && (y < surface[q] || y == surface[q] && deep > depth[q])) {
         surface[q] = y;
         distance[q] = d2;
         depth[q] = (byte) deep;
      }
   }

   /** The top water block of window pixel {@code i}: stream, lake, river or sea; {@link #DRY} without water. */
   private static int top(int[] surface, int[] other, int i) {
      return surface[i] != NONE ? surface[i] : other[i];
   }

   private record Tile(short[] surface, short[] ground, BitSet step, BitSet ledge) {
   }

   private record Key(int tx, int tz) {
   }
}
