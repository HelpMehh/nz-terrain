package com.yucareux.tellus.nz.terrain;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.nz.tiles.NzLayerData;
import com.yucareux.tellus.nz.tiles.NzTileFormat;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.TerrainHeightTransform;
import java.util.Arrays;
import java.util.Optional;

/**
 * The lakes and rivers of an NZ world as block heights: the water surface and the floor under it, per pixel of a tile
 * level, computed per tile with a margin from the neighbouring tiles, and cached. One instance belongs to one
 * {@link NzTiles} store and one settings value.
 *
 * <ul>
 *    <li>Surface: the {@code water_level} layer (a lake's one surface, a river's LiDAR surface) turned into the Y of
 *    the top water block, rounded to the nearest block. Terrain rounds away from sea level instead, so the land
 *    beside water is never below it, and an estuary 0–1 m high sits at sea level.</li>
 *    <li>A river block takes the lowest river surface within {@link #RIVER_LEVEL_RADIUS_BLOCKS}, so a channel is
 *    level across and river blocks over a bank or gravel bar take the water level beside them.</li>
 *    <li>Floor: the {@code bed} layer where measured. Elsewhere invented from the distance to the nearest land
 *    pixel (sea and lakes don't count as land, so a river mouth doesn't shallow): rivers 1 block deep at the bank and
 *    1 block deeper per {@link #RIVER_DEPTH_STEP_BLOCKS}, lakes 1 m deeper per 4 m. Where the LiDAR under a lake is
 *    lower than that (a lake that isn't flattened), the LiDAR wins. Always at least 1 block below the surface.</li>
 * </ul>
 */
final class NzInlandWater {
   /** Surface value of pixels without lake or river water. */
   static final int NONE = Short.MIN_VALUE;
   /** River blocks take the lowest river surface within this many blocks (a square). */
   static final int RIVER_LEVEL_RADIUS_BLOCKS = 2;
   /** Rivers get 1 block deeper per this many blocks of distance to the bank, starting 1 block deep. */
   static final double RIVER_DEPTH_STEP_BLOCKS = 6.0;
   static final int RIVER_MAX_DEPTH_BLOCKS = 3;
   /** Lake depth per metre of distance to the shore, and its limits. */
   static final double LAKE_SLOPE = 0.25;
   static final double LAKE_MIN_DEPTH_METRES = 2.0;
   static final double LAKE_MAX_DEPTH_METRES = 40.0;
   /** Land further away than this doesn't change the depth any more. */
   private static final int REACH_BLOCKS = (int) Math.ceil(Math.max(
      LAKE_MAX_DEPTH_METRES / LAKE_SLOPE / NzTileFormat.METRES_PER_BLOCK,
      1 + RIVER_DEPTH_STEP_BLOCKS * (RIVER_MAX_DEPTH_BLOCKS - 1)
   ));
   /** Height values are metres × 8. */
   private static final double STEPS_PER_METRE = 8.0;
   private static final int CACHE_TILES = 64;
   /** "No land in this line": far beyond any squared distance in a window, small enough to keep double precision. */
   private static final double FAR = 1.0e12;

   private final NzTiles tiles;
   private final EarthGeneratorSettings settings;
   private final double blocksPerMetre;
   private final LoadingCache<Key, Optional<Tile>> cache;

   NzInlandWater(NzTiles tiles, EarthGeneratorSettings settings) {
      this.tiles = tiles;
      this.settings = settings;
      this.blocksPerMetre = scaled(settings, 1.0, 0);
      this.cache = CacheBuilder.newBuilder().maximumSize(CACHE_TILES).build(CacheLoader.from(this::compute));
   }

   NzTiles tiles() {
      return this.tiles;
   }

   int seaLevel() {
      return this.settings.effectiveHeightOffset();
   }

   /** Y of the top water block at pixel (px, pz) of {@code level}, counted across tiles; {@link #NONE} without water. */
   int surfaceY(int level, int px, int pz) {
      Tile tile = this.tile(level, px, pz);
      return tile == null ? NONE : tile.surface[index(px, pz)];
   }

   /** Y of the floor (the top solid block) under the water at pixel (px, pz); only meaningful where there is water. */
   int floorY(int level, int px, int pz) {
      Tile tile = this.tile(level, px, pz);
      return tile == null ? NONE : tile.floor[index(px, pz)];
   }

   private Tile tile(int level, int px, int pz) {
      return this.cache.getUnchecked(new Key(level, px >> NzTileFormat.TILE_SHIFT, pz >> NzTileFormat.TILE_SHIFT)).orElse(null);
   }

   private static int index(int px, int pz) {
      int mask = NzTileFormat.TILE_PIXELS - 1;
      return (pz & mask) * NzTileFormat.TILE_PIXELS + (px & mask);
   }

   /** Y of the top water block for a water surface {@code metres} high: nearest block. */
   static int waterY(EarthGeneratorSettings settings, double metres, int z) {
      return settings.effectiveHeightOffset() + (int) Math.floor(scaled(settings, metres, z) + 0.5);
   }

   private static double scaled(EarthGeneratorSettings settings, double metres, int z) {
      return TerrainHeightTransform.scaledElevationBlocks(
         metres,
         z,
         settings.effectiveVerticalWorldScale(),
         settings.effectiveTerrestrialHeightScale(),
         settings.effectiveOceanicHeightScale(),
         settings.experimentalIncreaseHeight(),
         settings.automaticHeightScaling()
      );
   }

   static int riverDepthBlocks(double distanceBlocks) {
      return (int) Math.min(RIVER_MAX_DEPTH_BLOCKS, 1.0 + Math.floor((distanceBlocks - 1.0) / RIVER_DEPTH_STEP_BLOCKS));
   }

   int lakeDepthBlocks(double distanceBlocks) {
      double metres = Math.clamp(distanceBlocks * NzTileFormat.METRES_PER_BLOCK * LAKE_SLOPE, LAKE_MIN_DEPTH_METRES, LAKE_MAX_DEPTH_METRES);
      return Math.max(1, (int) Math.round(metres * this.blocksPerMetre));
   }

   /** Surfaces and floors of one tile; empty where the tile has no lake or river pixel. */
   private Optional<Tile> compute(Key key) {
      int level = key.level();
      NzLayerData centre = this.tiles.layer(NzTileLayer.WATER, level, key.tx(), key.tz());
      if (centre == null || !hasInlandWater(centre)) {
         return Optional.empty();
      }
      int pixelBlocks = 1 << level;
      int radius = RIVER_LEVEL_RADIUS_BLOCKS >> level;
      int margin = (REACH_BLOCKS + pixelBlocks - 1) / pixelBlocks + radius;
      int size = NzTileFormat.TILE_PIXELS + 2 * margin;
      int originX = key.tx() * NzTileFormat.TILE_PIXELS - margin;
      int originZ = key.tz() * NzTileFormat.TILE_PIXELS - margin;
      int[] water = this.readWindow(NzTileLayer.WATER, level, originX, originZ, size);
      int[] waterLevel = this.readWindow(NzTileLayer.WATER_LEVEL, level, originX, originZ, size);

      // Water surface per window pixel; rivers then take the lowest river surface around them.
      int area = size * size;
      int[] surface = new int[area];
      int[] river = new int[area];
      Arrays.fill(river, Integer.MAX_VALUE);
      boolean[] land = new boolean[area];
      for (int pz = 0; pz < size; pz++) {
         int z = (originZ + pz) << level;
         for (int px = 0; px < size; px++) {
            int i = pz * size + px;
            int w = water[i];
            land[i] = w == NzTerrainSampler.WATER_LAND;
            surface[i] = NONE;
            if ((w == NzTerrainSampler.WATER_LAKE || w == NzTerrainSampler.WATER_RIVER) && !NzTileLayer.WATER_LEVEL.isNodata(waterLevel[i])) {
               surface[i] = waterY(this.settings, waterLevel[i] / STEPS_PER_METRE, z);
               if (w == NzTerrainSampler.WATER_RIVER) {
                  river[i] = surface[i];
               }
            }
         }
      }
      int[] riverLevel = minimumFilter(river, size, radius);

      double[] distances = landDistances(land, size, margin);
      int[] height = this.readWindow(NzTileLayer.HEIGHT, level, originX, originZ, size);
      int[] bed = this.readWindow(NzTileLayer.BED, level, originX, originZ, size);
      short[] tileSurface = new short[NzTileFormat.TILE_AREA];
      short[] tileFloor = new short[NzTileFormat.TILE_AREA];
      for (int row = 0; row < NzTileFormat.TILE_PIXELS; row++) {
         int z = (originZ + margin + row) << level;
         for (int column = 0; column < NzTileFormat.TILE_PIXELS; column++) {
            int i = (row + margin) * size + column + margin;
            int out = row * NzTileFormat.TILE_PIXELS + column;
            int top = surface[i];
            if (top == NONE) {
               tileSurface[out] = (short) NONE;
               continue;
            }
            double distanceBlocks = distances[out] * pixelBlocks;
            int floor;
            if (water[i] == NzTerrainSampler.WATER_RIVER) {
               top = riverLevel[i];
               floor = top - riverDepthBlocks(distanceBlocks);
            } else {
               floor = top - this.lakeDepthBlocks(distanceBlocks);
            }
            if (!NzTileLayer.HEIGHT.isNodata(height[i])) {
               floor = Math.min(floor, NzTerrainSampler.blockY(this.settings, height[i] / STEPS_PER_METRE, z));
            }
            if (!NzTileLayer.BED.isNodata(bed[i])) {
               floor = NzTerrainSampler.blockY(this.settings, bed[i] / STEPS_PER_METRE, z);
            }
            floor = Math.min(floor, top - 1);
            tileSurface[out] = (short) Math.clamp(top, Short.MIN_VALUE + 1, Short.MAX_VALUE);
            tileFloor[out] = (short) Math.clamp(floor, Short.MIN_VALUE + 1, Short.MAX_VALUE);
         }
      }
      return Optional.of(new Tile(tileSurface, tileFloor));
   }

   private static boolean hasInlandWater(NzLayerData water) {
      for (int i = 0; i < NzTileFormat.TILE_AREA; i++) {
         int w = water.get(i);
         if (w == NzTerrainSampler.WATER_LAKE || w == NzTerrainSampler.WATER_RIVER) {
            return true;
         }
      }
      return false;
   }

   /** One layer over a window of pixels, across tiles; the layer's fill value where there is no tile. */
   private int[] readWindow(NzTileLayer layer, int level, int originX, int originZ, int size) {
      int[] values = new int[size * size];
      Arrays.fill(values, layer.fill());
      int firstTx = originX >> NzTileFormat.TILE_SHIFT;
      int lastTx = (originX + size - 1) >> NzTileFormat.TILE_SHIFT;
      int firstTz = originZ >> NzTileFormat.TILE_SHIFT;
      int lastTz = (originZ + size - 1) >> NzTileFormat.TILE_SHIFT;
      for (int tz = firstTz; tz <= lastTz; tz++) {
         for (int tx = firstTx; tx <= lastTx; tx++) {
            NzLayerData data = this.tiles.layer(layer, level, tx, tz);
            if (data == null) {
               continue;
            }
            int tileX = tx * NzTileFormat.TILE_PIXELS;
            int tileZ = tz * NzTileFormat.TILE_PIXELS;
            int fromX = Math.max(originX, tileX);
            int toX = Math.min(originX + size, tileX + NzTileFormat.TILE_PIXELS);
            int fromZ = Math.max(originZ, tileZ);
            int toZ = Math.min(originZ + size, tileZ + NzTileFormat.TILE_PIXELS);
            for (int pz = fromZ; pz < toZ; pz++) {
               for (int px = fromX; px < toX; px++) {
                  values[(pz - originZ) * size + (px - originX)] = data.get(px - tileX, pz - tileZ);
               }
            }
         }
      }
      return values;
   }

   /** The smallest value within {@code radius} pixels (a square) of each pixel; rows first, then columns. */
   static int[] minimumFilter(int[] values, int size, int radius) {
      if (radius <= 0) {
         return values;
      }
      int[] rows = new int[values.length];
      for (int pz = 0; pz < size; pz++) {
         for (int px = 0; px < size; px++) {
            int min = Integer.MAX_VALUE;
            for (int dx = Math.max(0, px - radius); dx <= Math.min(size - 1, px + radius); dx++) {
               min = Math.min(min, values[pz * size + dx]);
            }
            rows[pz * size + px] = min;
         }
      }
      int[] result = new int[values.length];
      for (int pz = 0; pz < size; pz++) {
         for (int px = 0; px < size; px++) {
            int min = Integer.MAX_VALUE;
            for (int dz = Math.max(0, pz - radius); dz <= Math.min(size - 1, pz + radius); dz++) {
               min = Math.min(min, rows[dz * size + px]);
            }
            result[pz * size + px] = min;
         }
      }
      return result;
   }

   /**
    * Distance in pixels from each pixel of the tile (the window without its margin) to the nearest land pixel of the
    * window, exact Euclidean (as {@link NzSeaFloor}); {@link Double#POSITIVE_INFINITY} where the window has no land.
    */
   private static double[] landDistances(boolean[] land, int size, int margin) {
      int rows = NzTileFormat.TILE_PIXELS;
      float[] columnPass = new float[rows * size];
      double[] f = new double[size];
      double[] d = new double[size];
      int[] v = new int[size];
      double[] boundaries = new double[size + 1];
      for (int column = 0; column < size; column++) {
         for (int row = 0; row < size; row++) {
            f[row] = land[row * size + column] ? 0.0 : FAR;
         }
         NzSeaFloor.distanceTransform(f, size, d, v, boundaries);
         for (int row = 0; row < rows; row++) {
            columnPass[row * size + column] = (float) d[row + margin];
         }
      }
      double[] distances = new double[NzTileFormat.TILE_AREA];
      for (int row = 0; row < rows; row++) {
         for (int column = 0; column < size; column++) {
            f[column] = columnPass[row * size + column];
         }
         NzSeaFloor.distanceTransform(f, size, d, v, boundaries);
         for (int column = 0; column < NzTileFormat.TILE_PIXELS; column++) {
            double squared = d[column + margin];
            distances[row * NzTileFormat.TILE_PIXELS + column] = squared >= FAR / 2 ? Double.POSITIVE_INFINITY : Math.sqrt(squared);
         }
      }
      return distances;
   }

   private record Tile(short[] surface, short[] floor) {
   }

   private record Key(int level, int tx, int tz) {
   }
}
