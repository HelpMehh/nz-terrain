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

      short[] tileSurface = new short[NzTileFormat.TILE_AREA];
      short[] tileGround = new short[NzTileFormat.TILE_AREA];
      BitSet tileStep = new BitSet(NzTileFormat.TILE_AREA);
      int sill = NzInlandWater.SILL_RADIUS_BLOCKS;
      for (int row = 0; row < NzTileFormat.TILE_PIXELS; row++) {
         for (int column = 0; column < NzTileFormat.TILE_PIXELS; column++) {
            int i = (row + MARGIN) * size + column + MARGIN;
            int out = row * NzTileFormat.TILE_PIXELS + column;
            int y = surface[i];
            tileSurface[out] = (short) NONE;
            tileGround[out] = (short) NONE;
            if (y == NONE) {
               // Land: a bank where stream water beside it stands higher.
               int bank = Math.max(Math.max(surface[i - 1], surface[i + 1]), Math.max(surface[i - size], surface[i + size]));
               if (water[i] == NzTerrainSampler.WATER_LAND && ground[i] != DRY && bank > ground[i]) {
                  tileGround[out] = (short) bank;
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
      return Optional.of(new Tile(tileSurface, tileGround, tileStep));
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

   private record Tile(short[] surface, short[] ground, BitSet step) {
   }

   private record Key(int tx, int tz) {
   }
}
