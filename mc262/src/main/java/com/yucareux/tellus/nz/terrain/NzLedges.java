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
 * Rock ledges on cliffs (TERR-14 step 5, option (b), decided 25 Oct 2026): on a steep face the terrain is one height
 * per column, so the 3 m LiDAR's smooth slope comes out as an even staircase. Here some columns move up or down onto
 * ledge levels, so the face shows flat ledges with taller rock between them, as blocky schist and greywacke bluffs do.
 * No overhangs: a 3D pass is left for later, if the desktop look asks for one.
 *
 * <ul>
 *    <li>Where: dry land whose surface (by the palette's land rules, on the tile heights and LiDAR slope, as in
 *    {@link NzSurface}) is one of the palette's {@code "ledges"} surfaces (the rock faces), from its {@code min_slope}
 *    up, at full width from its {@code full_slope}. Never within {@link #KEEP_AWAY_BLOCKS} of water or of a column the
 *    streams change, so banks, gorges and shores stay as they are.</li>
 *    <li>Ledge levels: horizontal planes every {@code spacing} blocks (irregular gaps from its range, the same
 *    everywhere), which rise and fall across the land by {@link #WAVE_BLOCKS} over {@link #WAVE_PERIOD_BLOCKS} and
 *    drift by {@link #DRIFT_BLOCKS} over {@link #DRIFT_PERIOD_BLOCKS}, so the two sides of a valley don't line
 *    up.</li>
 *    <li>Each level's ledge comes and goes along a cliff: its width in columns follows a noise per level (lattice
 *    {@link #WIDTH_PERIOD_BLOCKS}), up to the palette's {@code width}, missing along about a third of it. A column
 *    whose height lies within reach of the nearest level moves onto it; the reach is half the tread's width times the
 *    column's rise per block, but never more than half the gap to the next level, so a column moves at most that far
 *    and the face's mean height stays.</li>
 *    <li>No column ends up above a neighbour that is higher in the tiles, or below one that is lower: where the reach
 *    changes from column to column (the top and foot of a cliff, a soil column beside the rock, the keep-away edge,
 *    the width noise), a column on its own could otherwise stand as a fin in front of the slope or sink into a hollow
 *    behind a rim (desktop check, 25 Oct 2026). Such a column is pulled back towards its own height, as far as its
 *    neighbours allow, in up to {@link #SETTLE_PASSES} passes; it is then no tread.</li>
 *    <li>A column on a ledge is a tread: {@link NzSurface} gives it the palette's tread surface (scree, moss, snow) in
 *    place of bare rock.</li>
 * </ul>
 *
 * <p>Per level-0 tile, cached, like {@link NzStreams}; level-0 detail only, so DH's coarser LODs don't show it. The
 * tile is worked in parts of {@link #PART} × {@link #PART} blocks, so no window is big enough to be a humongous object
 * in G1 (TERR-18 note in {@code steps.md}).
 */
final class NzLedges {
   /** "Not on a ledge". */
   static final int NONE = Integer.MIN_VALUE;
   /** No ledge within this many blocks (a square) of water or of a column the streams change. */
   static final int KEEP_AWAY_BLOCKS = 3;
   /** The ledge levels rise and fall by up to this many blocks, with lattice points this far apart. */
   static final double WAVE_BLOCKS = 4.0;
   static final int WAVE_PERIOD_BLOCKS = 48;
   /** And drift by up to this many blocks over long distances. */
   static final double DRIFT_BLOCKS = 12.0;
   static final int DRIFT_PERIOD_BLOCKS = 512;
   /** The width noise of each ledge: lattice points this far apart, and its bias (0: ledges along half a cliff). */
   static final int WIDTH_PERIOD_BLOCKS = 14;
   static final double PRESENCE = 0.6;
   /** Steeper slopes count as this one (about 11 blocks of rise per block), so a near-vertical face's reach stays finite. */
   static final double MAX_SLOPE_DEGREES = 85.0;
   /** The ledge levels cover these Y, beyond any world height plus waves and drift. */
   static final int LOWEST_LEVEL = -1024;
   static final int HIGHEST_LEVEL = 3072;
   static final int PART = 128;
   /** Passes that pull back columns out of order with a neighbour; each pass can reach one column further. */
   static final int SETTLE_PASSES = 8;
   /** Columns this close to a window's edge can't see all water within the keep-away distance: they never move. */
   private static final int EDGE = KEEP_AWAY_BLOCKS + 1;
   /** Enough for the settle passes too, so a part's columns come out as if the whole land were worked at once. */
   private static final int MARGIN = EDGE + SETTLE_PASSES;
   private static final long LEVEL_SALT = 0x6C6576656CL;
   private static final long WAVE_SALT = 0x77617665L;
   private static final long DRIFT_SALT = 0x6472696674L;
   private static final long WIDTH_SALT = 0x7769647468L;
   /** Height values are metres × 8. */
   private static final double STEPS_PER_METRE = 8.0;
   private static final int CACHE_TILES = 64;

   private final NzStreams streams;
   private final NzSurfacePalette palette;
   private final NzSurfacePalette.Ledges ledges;
   private final EarthGeneratorSettings settings;
   private final double blocksPerMetre;
   private final int[] levels;
   private final LoadingCache<Key, Optional<Tile>> cache;

   NzLedges(NzStreams streams, NzSurfacePalette palette, EarthGeneratorSettings settings) {
      this.streams = streams;
      this.palette = palette;
      this.ledges = palette.ledges();
      this.settings = settings;
      this.blocksPerMetre = settings.effectiveTerrestrialHeightScale() / settings.effectiveVerticalWorldScale();
      this.levels = this.ledges == null ? new int[0] : levels(this.ledges.minSpacing(), this.ledges.maxSpacing());
      this.cache = CacheBuilder.newBuilder().maximumSize(CACHE_TILES).build(CacheLoader.from(this::compute));
   }

   NzStreams streams() {
      return this.streams;
   }

   NzSurfacePalette palette() {
      return this.palette;
   }

   /** How many blocks the ledges move land block (x, z) up (above 0) or down; 0 off the ledges. */
   int offset(int x, int z) {
      Tile tile = this.tile(x, z);
      return tile == null ? 0 : tile.offset[index(x, z)];
   }

   /** Whether land block (x, z) is a ledge's tread. */
   boolean isTread(int x, int z) {
      Tile tile = this.tile(x, z);
      return tile != null && tile.tread.get(index(x, z));
   }

   /**
    * The Y of the ledge that a rock-faced column at (x, z) with top Y {@code y} and game slope {@code slopeDegrees}
    * moves onto, or {@link #NONE} when it stays where it is (rules in the class Javadoc).
    */
   int treadY(int x, int z, int y, double slopeDegrees) {
      if (this.ledges == null || slopeDegrees <= this.ledges.minSlope()) {
         return NONE;
      }
      double strength = Math.min(1.0, (slopeDegrees - this.ledges.minSlope()) / (this.ledges.fullSlope() - this.ledges.minSlope()));
      double rise = Math.tan(Math.toRadians(Math.min(slopeDegrees, MAX_SLOPE_DEGREES)));
      double shift = NzSurfaceMix.smoothNoise(WAVE_SALT, x, z, WAVE_PERIOD_BLOCKS) * WAVE_BLOCKS
         + NzSurfaceMix.smoothNoise(DRIFT_SALT, x, z, DRIFT_PERIOD_BLOCKS) * DRIFT_BLOCKS;
      double u = y + shift;
      int k = this.nearestLevel(u);
      int level = this.levels[k];
      int gap = Math.min(level - this.levels[k - 1], this.levels[k + 1] - level);
      double presence = NzSurfaceMix.smoothNoise(NzSurfaceMix.hash(WIDTH_SALT, k, 0, 0), x, z, WIDTH_PERIOD_BLOCKS);
      double columns = Math.clamp((presence + PRESENCE) / (1.0 + PRESENCE), 0.0, 1.0) * this.ledges.width();
      double reach = Math.min(strength * columns * rise / 2.0, gap / 2.0);
      if (reach < 0.5 || Math.abs(u - level) > reach) {
         return NONE;
      }
      return (int) Math.round(level - shift);
   }

   /**
    * The ledge levels from {@link #LOWEST_LEVEL} to the first one above {@link #HIGHEST_LEVEL}: gaps of
    * {@code minSpacing} to {@code maxSpacing}, by a hash.
    */
   static int[] levels(int minSpacing, int maxSpacing) {
      int[] levels = new int[(HIGHEST_LEVEL - LOWEST_LEVEL) / minSpacing + 2];
      int count = 0;
      for (int y = LOWEST_LEVEL; count == 0 || levels[count - 1] <= HIGHEST_LEVEL; count++) {
         levels[count] = y;
         y += minSpacing + (int) Long.remainderUnsigned(NzSurfaceMix.hash(LEVEL_SALT, count, 0, 0), maxSpacing - minSpacing + 1L);
      }
      return Arrays.copyOf(levels, count);
   }

   /** The index of the level nearest to {@code u}, never the first or last (so both neighbours exist). */
   private int nearestLevel(double u) {
      int found = Arrays.binarySearch(this.levels, (int) Math.floor(u));
      int below = found >= 0 ? found : -found - 2;
      below = Math.clamp(below, 1, this.levels.length - 3);
      return u - this.levels[below] <= this.levels[below + 1] - u ? below : below + 1;
   }

   private Tile tile(int x, int z) {
      if (this.ledges == null) {
         return null;
      }
      return this.cache.getUnchecked(new Key(x >> NzTileFormat.TILE_SHIFT, z >> NzTileFormat.TILE_SHIFT)).orElse(null);
   }

   private static int index(int x, int z) {
      int mask = NzTileFormat.TILE_PIXELS - 1;
      return (z & mask) * NzTileFormat.TILE_PIXELS + (x & mask);
   }

   /** The offsets and treads of one tile; empty where it has no ledge. */
   private Optional<Tile> compute(Key key) {
      Tile tile = new Tile(new byte[NzTileFormat.TILE_AREA], new BitSet(NzTileFormat.TILE_AREA));
      boolean any = false;
      for (int partZ = 0; partZ < NzTileFormat.TILE_PIXELS; partZ += PART) {
         for (int partX = 0; partX < NzTileFormat.TILE_PIXELS; partX += PART) {
            any |= this.computePart(tile, key.tx() * NzTileFormat.TILE_PIXELS + partX, key.tz() * NzTileFormat.TILE_PIXELS + partZ);
         }
      }
      return any ? Optional.of(tile) : Optional.empty();
   }

   /** Fills the part of {@code tile} from block (minX, minZ); returns whether it holds a ledge. */
   private boolean computePart(Tile tile, int minX, int minZ) {
      NzTiles tiles = this.streams.inland().tiles();
      int size = PART + 2 * MARGIN;
      int originX = minX - MARGIN;
      int originZ = minZ - MARGIN;
      int[] height = NzInlandWater.readWindow(tiles, NzTileLayer.HEIGHT, 0, originX, originZ, size);
      int[] water = NzInlandWater.readWindow(tiles, NzTileLayer.WATER, 0, originX, originZ, size);
      int[] cover = NzInlandWater.readWindow(tiles, NzTileLayer.LANDCOVER, 0, originX, originZ, size);
      int area = size * size;
      boolean[] wet = new boolean[area];
      double[] metres = new double[area];
      for (int i = 0; i < area; i++) {
         wet[i] = water[i] != NzTerrainSampler.WATER_LAND || NzTileLayer.HEIGHT.isNodata(height[i]);
         metres[i] = wet[i] ? 0.0 : height[i] / STEPS_PER_METRE;
      }

      // Columns that would move, before looking for water nearby: most parts have none, and then need no streams.
      int[] raw = new int[area];
      int[] target = new int[area];
      Arrays.fill(raw, NONE);
      Arrays.fill(target, NONE);
      boolean any = false;
      for (int wz = 0; wz < size; wz++) {
         for (int wx = 0; wx < size; wx++) {
            int i = wz * size + wx;
            if (wet[i]) {
               continue;
            }
            int z = originZ + wz;
            raw[i] = NzTerrainSampler.blockY(this.settings, metres[i], z);
            if (wx < EDGE || wz < EDGE || wx >= size - EDGE || wz >= size - EDGE) {
               continue;
            }
            double slope = NzSurface.slopeDegrees(metres, i, size, this.blocksPerMetre);
            if (slope <= this.ledges.minSlope()) {
               continue;
            }
            int lcdb = NzTileLayer.LANDCOVER.isNodata(cover[i]) ? 0 : cover[i];
            if (this.palette.ledgeTread(this.palette.land(lcdb, slope, metres[i])) == null) {
               continue;
            }
            target[i] = this.treadY(originX + wx, z, raw[i], slope);
            any |= target[i] != NONE;
         }
      }
      if (!any) {
         return false;
      }

      this.streams.markChanged(originX, originZ, size, wet);
      boolean[] near = dilate(wet, size, KEEP_AWAY_BLOCKS);
      int[] offset = new int[area];
      boolean[] tread = new boolean[area];
      for (int i = 0; i < area; i++) {
         if (target[i] != NONE && !near[i]) {
            offset[i] = target[i] - raw[i];
            tread[i] = true;
         }
      }
      settle(raw, offset, tread, size);

      any = false;
      for (int pz = 0; pz < PART; pz++) {
         for (int px = 0; px < PART; px++) {
            int i = (pz + MARGIN) * size + px + MARGIN;
            if (!tread[i] && offset[i] == 0) {
               continue;
            }
            int out = index(minX + px, minZ + pz);
            tile.offset[out] = (byte) offset[i];
            tile.tread.set(out, tread[i]);
            any = true;
         }
      }
      return any;
   }

   /**
    * Pulls back moved columns that end up above a neighbour that is higher in {@code raw} or below one that is lower
    * (8 neighbours; {@link #NONE} in {@code raw} is water, which has no say): each pass moves such a column to the
    * nearest height its neighbours allow, or back to its own height when none lies between the two. Columns only
    * ever move back, so the passes end; a column pulled back is no tread.
    */
   static void settle(int[] raw, int[] offset, boolean[] tread, int size) {
      int[] next = offset.clone();
      for (int pass = 0; pass < SETTLE_PASSES; pass++) {
         boolean changed = false;
         for (int z = 1; z < size - 1; z++) {
            for (int x = 1; x < size - 1; x++) {
               int i = z * size + x;
               if (offset[i] == 0) {
                  continue;
               }
               int y = raw[i] + offset[i];
               int low = Integer.MIN_VALUE;
               int high = Integer.MAX_VALUE;
               for (int dz = -1; dz <= 1; dz++) {
                  for (int dx = -1; dx <= 1; dx++) {
                     int n = i + dz * size + dx;
                     if (raw[n] == NONE || raw[n] == raw[i]) {
                        continue;
                     }
                     if (raw[n] > raw[i]) {
                        high = Math.min(high, raw[n] + offset[n]);
                     } else {
                        low = Math.max(low, raw[n] + offset[n]);
                     }
                  }
               }
               if (y >= low && y <= high) {
                  continue;
               }
               int settled = low <= high ? Math.clamp(y, low, high) : raw[i];
               if (Integer.signum(settled - raw[i]) != Integer.signum(offset[i]) || Math.abs(settled - raw[i]) > Math.abs(offset[i])) {
                  settled = raw[i];
               }
               next[i] = settled - raw[i];
               tread[i] = false;
               changed = true;
            }
         }
         if (!changed) {
            return;
         }
         System.arraycopy(next, 0, offset, 0, offset.length);
      }
   }

   /** Whether each pixel of a {@code size} × {@code size} window has a set pixel within {@code radius} (a square). */
   static boolean[] dilate(boolean[] set, int size, int radius) {
      boolean[] rows = new boolean[set.length];
      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; x++) {
            for (int d = Math.max(0, x - radius); d <= Math.min(size - 1, x + radius) && !rows[z * size + x]; d++) {
               rows[z * size + x] = set[z * size + d];
            }
         }
      }
      boolean[] out = new boolean[set.length];
      for (int z = 0; z < size; z++) {
         for (int x = 0; x < size; x++) {
            for (int d = Math.max(0, z - radius); d <= Math.min(size - 1, z + radius) && !out[z * size + x]; d++) {
               out[z * size + x] = rows[d * size + x];
            }
         }
      }
      return out;
   }

   /** Per tile pixel: how far the column moves (blocks), and whether it is a tread. */
   private record Tile(byte[] offset, BitSet tread) {
   }

   private record Key(int tx, int tz) {
   }
}
