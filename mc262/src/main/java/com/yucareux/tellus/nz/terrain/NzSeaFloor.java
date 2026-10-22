package com.yucareux.tellus.nz.terrain;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.nz.tiles.NzLayerData;
import com.yucareux.tellus.nz.tiles.NzTileFormat;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTiles;
import java.util.Optional;

/**
 * A placeholder sea floor until the tiles carry real bathymetry (PIPE-10): the same rule as the pipeline's WorldPainter
 * export ({@code worldpainter.py} {@code placeholder_depth}). Depth is 1 m per 50 m of distance to the nearest
 * non-sea pixel, at least 4 m, at most 40 m. Outside the data counts as sea, so the floor runs on at 40 m there: the
 * flat ocean around the tiles.
 *
 * <p>Distances are exact Euclidean, from pixel centre to pixel centre, computed per tile with a margin from the
 * neighbouring tiles, and cached. One instance belongs to one {@link NzTiles} store.
 */
final class NzSeaFloor {
   static final double SLOPE = 1.0 / 50.0;
   static final double MIN_DEPTH_METRES = 4.0;
   static final double MAX_DEPTH_METRES = 40.0;
   /** Land further away than this doesn't change the depth any more. */
   private static final double REACH_METRES = MAX_DEPTH_METRES / SLOPE;
   /** Depths are cached in ¼ m steps, one byte per pixel. */
   private static final double STEPS_PER_METRE = 4.0;
   private static final int CACHE_TILES = 128;
   /** "No land in this line": far beyond any squared distance in a window, small enough to keep double precision. */
   private static final double FAR = 1.0e12;

   private final NzTiles tiles;
   private final LoadingCache<Key, Optional<byte[]>> cache;

   NzSeaFloor(NzTiles tiles) {
      this.tiles = tiles;
      this.cache = CacheBuilder.newBuilder().maximumSize(CACHE_TILES).build(CacheLoader.from(this::compute));
   }

   NzTiles tiles() {
      return this.tiles;
   }

   /** True where the water layer says sea, or where there is no data. */
   static boolean isSea(int water) {
      return water == NzTerrainSampler.WATER_SEA || NzTileLayer.WATER.isNodata(water);
   }

   /** Depth in metres (positive) of the floor below sea level at pixel (px, pz) of {@code level}, counted across tiles. */
   double depthMetres(int level, int px, int pz) {
      byte[] depths = this.cache.getUnchecked(new Key(level, px >> NzTileFormat.TILE_SHIFT, pz >> NzTileFormat.TILE_SHIFT)).orElse(null);
      if (depths == null) {
         return MAX_DEPTH_METRES;
      }
      int mask = NzTileFormat.TILE_PIXELS - 1;
      return (depths[(pz & mask) * NzTileFormat.TILE_PIXELS + (px & mask)] & 0xFF) / STEPS_PER_METRE;
   }

   /** Depth for a distance to the shore, in metres. */
   static double depthForDistance(double distanceMetres) {
      return Math.clamp(distanceMetres * SLOPE, MIN_DEPTH_METRES, MAX_DEPTH_METRES);
   }

   /** The depths of one tile; empty where every pixel is at the maximum (no land within reach) or there is no sea. */
   private Optional<byte[]> compute(Key key) {
      int level = key.level();
      double pixelMetres = (double) NzTileFormat.METRES_PER_BLOCK * (1 << level);
      int margin = (int) Math.ceil(REACH_METRES / pixelMetres);
      int size = NzTileFormat.TILE_PIXELS + 2 * margin;
      int originX = key.tx() * NzTileFormat.TILE_PIXELS - margin;
      int originZ = key.tz() * NzTileFormat.TILE_PIXELS - margin;

      // A missing tile is all sea. A tile without sea is never asked for a depth.
      NzLayerData centre = this.tiles.layer(NzTileLayer.WATER, level, key.tx(), key.tz());
      if (centre != null && !hasSea(centre)) {
         return Optional.empty();
      }
      boolean[] land = new boolean[size * size];
      if (!this.readLand(level, originX, originZ, size, land)) {
         return Optional.empty();
      }

      // Exact squared distance transform (Felzenszwalb & Huttenlocher): columns first, keeping only the tile's rows,
      // then rows.
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
         distanceTransform(f, size, d, v, boundaries);
         for (int row = 0; row < rows; row++) {
            columnPass[row * size + column] = (float) d[row + margin];
         }
      }
      byte[] depths = new byte[NzTileFormat.TILE_AREA];
      for (int row = 0; row < rows; row++) {
         for (int column = 0; column < size; column++) {
            f[column] = columnPass[row * size + column];
         }
         distanceTransform(f, size, d, v, boundaries);
         for (int column = 0; column < NzTileFormat.TILE_PIXELS; column++) {
            double depth = depthForDistance(Math.sqrt(d[column + margin]) * pixelMetres);
            depths[row * NzTileFormat.TILE_PIXELS + column] = (byte) Math.round(depth * STEPS_PER_METRE);
         }
      }
      return Optional.of(depths);
   }

   private static boolean hasSea(NzLayerData water) {
      for (int i = 0; i < NzTileFormat.TILE_AREA; i++) {
         if (isSea(water.get(i))) {
            return true;
         }
      }
      return false;
   }

   /** Fills {@code land} for the window; false when the window has no land at all. */
   private boolean readLand(int level, int originX, int originZ, int size, boolean[] land) {
      boolean anyLand = false;
      int firstTx = originX >> NzTileFormat.TILE_SHIFT;
      int lastTx = (originX + size - 1) >> NzTileFormat.TILE_SHIFT;
      int firstTz = originZ >> NzTileFormat.TILE_SHIFT;
      int lastTz = (originZ + size - 1) >> NzTileFormat.TILE_SHIFT;
      for (int tz = firstTz; tz <= lastTz; tz++) {
         for (int tx = firstTx; tx <= lastTx; tx++) {
            NzLayerData water = this.tiles.layer(NzTileLayer.WATER, level, tx, tz);
            if (water == null) {
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
                  if (!isSea(water.get(px - tileX, pz - tileZ))) {
                     land[(pz - originZ) * size + (px - originX)] = true;
                     anyLand = true;
                  }
               }
            }
         }
      }
      return anyLand;
   }

   /**
    * One-dimensional squared distance transform of {@code f} into {@code d}: {@code d[q] = min_p (q − p)² + f[p]}.
    * {@code v} and {@code boundaries} are work arrays.
    */
   static void distanceTransform(double[] f, int n, double[] d, int[] v, double[] boundaries) {
      int k = 0;
      v[0] = 0;
      boundaries[0] = Double.NEGATIVE_INFINITY;
      boundaries[1] = Double.POSITIVE_INFINITY;
      for (int q = 1; q < n; q++) {
         double s = intersection(f, q, v[k]);
         while (s <= boundaries[k]) {
            k--;
            s = intersection(f, q, v[k]);
         }
         k++;
         v[k] = q;
         boundaries[k] = s;
         boundaries[k + 1] = Double.POSITIVE_INFINITY;
      }
      k = 0;
      for (int q = 0; q < n; q++) {
         while (boundaries[k + 1] < q) {
            k++;
         }
         double offset = q - v[k];
         d[q] = offset * offset + f[v[k]];
      }
   }

   private static double intersection(double[] f, int q, int p) {
      return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
   }

   private record Key(int level, int tx, int tz) {
   }
}
