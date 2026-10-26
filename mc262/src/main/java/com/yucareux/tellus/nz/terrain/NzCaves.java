package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;

/**
 * Keeps Tellus's cave layer inside the ground in NZ worlds (TERR-23, decided 26 Oct 2026).
 *
 * <p>Tellus measures its caves from each column's own surface: noise caves and carver caves both leave its top 5
 * blocks. On a slope the downhill neighbour is lower, so a cave at a safe depth in one column opens out of the next
 * column's face: holes in cliffs, 1-block crusts, lava running down mountainsides. NZ worlds measure the cave layer
 * from the <b>cave surface</b> instead: the lowest ground within {@link #RADIUS_BLOCKS} (a square) of the column. No
 * cave then comes within {@link #RADIUS_BLOCKS} of a face sideways, nor within Tellus's cover of any ground above it.
 * The skin thickens by itself where the ground is steep; on flat ground nothing changes.
 *
 * <ul>
 *    <li>Noise caves and carver caves (and their lava and water) get the cave surface in place of the chunk's terrain
 *    surface ({@link #lowerToCaveSurface}). The noise caves' vanilla column is mapped onto the shorter column, so
 *    under steep ground they sit a little lower and squeezed. The carvers' height range still comes from Tellus's
 *    surface at the source chunk's middle; the mask cuts them off at the cave surface.</li>
 *    <li>Height-range features (lava and water springs, underground lava lakes, geodes, ores, lush-cave plants) are
 *    placed below the cave surface too ({@link #placementSurfaceY}). A spring needs one open side; inside the cave
 *    surface that can only be a cave, so lava and water no longer fall from cliff faces.</li>
 *    <li>Two vanilla features leave NZ worlds ({@link #dropsFeature}): surface lava lakes, and the azalea trees that
 *    lush caves grow on the surface above them (roots up to 100 blocks), which are not native.</li>
 *    <li>Lush caves only under forest and mangroves ({@link #lushCavesUnder}).</li>
 * </ul>
 *
 * <p>No cave mouths: Tellus's {@code caves_reach_surface} stays off. Real NZ caves are in karst, so mouths wait for
 * the rock layer (PIPE-15). Glaciers (TERR-22) can lower the cave surface further, to their bed.
 */
public final class NzCaves {
   private static final int CHUNK_SIDE = 16;
   private static final int CHUNK_AREA = CHUNK_SIDE * CHUNK_SIDE;
   /** The cave surface is the lowest ground within this many blocks (a square). */
   static final int RADIUS_BLOCKS = 6;
   private static final int WINDOW = CHUNK_SIDE + 2 * RADIUS_BLOCKS;
   /** Cave surfaces of this many chunks are kept (about 1 KiB each); the cache is emptied when it fills. */
   private static final int MAX_CACHED_CHUNKS = 4096;
   /** Placed features NZ worlds leave out, by ID path. */
   static final Set<String> DROPPED_FEATURES = Set.of("lake_lava_surface", "rooted_azalea_tree");
   /** Vegetation classes (hub {@code interfaces.md}) with lush caves under them: forests and mangroves. */
   private static final Set<Integer> LUSH_VEGETATION = Set.of(123, 130, 131, 132, 133, 134, 135, 140, 141, 142, 143, 153);
   /** One instance per settings value, so the carvers and the feature placement share one cache. */
   private static final Map<EarthGeneratorSettings, NzCaves> CAVES = new ConcurrentHashMap<>();

   private final Map<Long, int[]> chunks = new ConcurrentHashMap<>();
   private volatile NzTiles tiles;

   private NzCaves() {
   }

   /**
    * Lowers each column of a chunk's surface array to its cave surface, in NZ worlds; leaves it as it is in any
    * other. The array is what the chunk generator hands Tellus's cave runner.
    *
    * @param surfaceYByColumn the chunk's terrain surface Y per column, index {@code localZ * 16 + localX}
    * @param heights the generator's terrain height at a block
    */
   public static void lowerToCaveSurface(
      EarthGeneratorSettings settings, int chunkMinX, int chunkMinZ, int[] surfaceYByColumn, IntBinaryOperator heights
   ) {
      if (!NzWorldSettings.isNzWorld(settings)) {
         return;
      }
      int[] caveSurfaces = forSettings(settings).chunk(chunkMinX, chunkMinZ, heights);
      for (int index = 0; index < CHUNK_AREA; index++) {
         surfaceYByColumn[index] = Math.min(surfaceYByColumn[index], caveSurfaces[index]);
      }
   }

   /**
    * The surface that height-range features are placed under at block (x, z): Tellus's, lowered to the cave surface
    * in NZ worlds.
    */
   public static int placementSurfaceY(EarthGeneratorSettings settings, int x, int z, int surfaceY, IntBinaryOperator heights) {
      if (!NzWorldSettings.isNzWorld(settings)) {
         return surfaceY;
      }
      int[] caveSurfaces = forSettings(settings).chunk(x & -CHUNK_SIDE, z & -CHUNK_SIDE, heights);
      return Math.min(surfaceY, caveSurfaces[(z & (CHUNK_SIDE - 1)) * CHUNK_SIDE + (x & (CHUNK_SIDE - 1))]);
   }

   /** True for the placed features ({@link #DROPPED_FEATURES}, by ID path) that NZ worlds leave out. */
   public static boolean dropsFeature(EarthGeneratorSettings settings, String path) {
      return NzWorldSettings.isNzWorld(settings) && DROPPED_FEATURES.contains(path);
   }

   /**
    * Whether lush caves may lie under block (x, z) of an NZ world: under forest or mangroves, by the vegetation
    * layer where it has a value, else by the land cover (LCDB classes Tellus's stopgap maps to trees, which includes
    * the plantations that rewilding turns into forest).
    */
   public static boolean lushCavesUnder(int x, int z) {
      return lushCavesUnder(NzTiles.global(), x, z);
   }

   static boolean lushCavesUnder(NzTiles tiles, int x, int z) {
      int level = tiles.finestLevel(NzTileLayer.HEIGHT, 0, x, z);
      if (level < 0 || tiles.sample(NzTileLayer.WATER, level, x, z) != NzTerrainSampler.WATER_LAND) {
         return false;
      }
      int vegetation = tiles.sample(NzTileLayer.VEGETATION, level, x, z);
      if (!NzTileLayer.VEGETATION.isNodata(vegetation)) {
         return LUSH_VEGETATION.contains(vegetation);
      }
      int cover = NzCoverClasses.esaForLcdb(tiles.sample(NzTileLayer.LANDCOVER, level, x, z));
      return cover == NzCoverClasses.ESA_TREES || cover == NzCoverClasses.ESA_MANGROVES;
   }

   /**
    * The cave surface of every column of the chunk at (chunkMinX, chunkMinZ), index {@code localZ * 16 + localX}:
    * the lowest of {@code heights} within {@link #RADIUS_BLOCKS} (a square).
    */
   static int[] caveSurfaces(int chunkMinX, int chunkMinZ, IntBinaryOperator heights) {
      int[] window = new int[WINDOW * WINDOW];
      for (int wz = 0; wz < WINDOW; wz++) {
         for (int wx = 0; wx < WINDOW; wx++) {
            window[wz * WINDOW + wx] = heights.applyAsInt(chunkMinX - RADIUS_BLOCKS + wx, chunkMinZ - RADIUS_BLOCKS + wz);
         }
      }
      // A square minimum is a minimum along x, then along z.
      int[] alongX = new int[WINDOW * CHUNK_SIDE];
      for (int wz = 0; wz < WINDOW; wz++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int lowest = Integer.MAX_VALUE;
            for (int dx = 0; dx <= 2 * RADIUS_BLOCKS; dx++) {
               lowest = Math.min(lowest, window[wz * WINDOW + localX + dx]);
            }
            alongX[wz * CHUNK_SIDE + localX] = lowest;
         }
      }
      int[] result = new int[CHUNK_AREA];
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int lowest = Integer.MAX_VALUE;
            for (int dz = 0; dz <= 2 * RADIUS_BLOCKS; dz++) {
               lowest = Math.min(lowest, alongX[(localZ + dz) * CHUNK_SIDE + localX]);
            }
            result[localZ * CHUNK_SIDE + localX] = lowest;
         }
      }
      return result;
   }

   private static NzCaves forSettings(EarthGeneratorSettings settings) {
      return CAVES.computeIfAbsent(settings, ignored -> new NzCaves());
   }

   /** The cached cave surfaces of a chunk; the cache starts over when the tile store changes (every server start). */
   private int[] chunk(int chunkMinX, int chunkMinZ, IntBinaryOperator heights) {
      NzTiles current = NzTiles.global();
      if (this.tiles != current) {
         this.chunks.clear();
         this.tiles = current;
      }
      long key = (long) (chunkMinX >> 4) << 32 | (chunkMinZ >> 4) & 0xFFFFFFFFL;
      int[] cached = this.chunks.get(key);
      if (cached == null) {
         cached = caveSurfaces(chunkMinX, chunkMinZ, heights);
         if (this.chunks.size() >= MAX_CACHED_CHUNKS) {
            this.chunks.clear();
         }
         this.chunks.put(key, cached);
      }
      return cached;
   }
}
