package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.DhLodWaterResolver;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The water of an NZ world, straight from the NZ tiles' water layer (decided in TERR-10, {@code tellus-internals.md}
 * §11): sea at sea level over the tiles' sea floor, lakes and rivers from {@link NzInlandWater}.
 *
 * <p>Tellus's water resolver and its DH LOD water resolver each hand NZ worlds to this class in one hook, so none of
 * their own analysis (water bodies, lake surface guesses, river reaches, shore blending) runs for NZ. Both answer
 * every block the same way: the resolver's 64 × 64 regions are just this class's column, block by block, and DH
 * asks the same column at a coarser tile level.
 */
public final class NzWater {
   /** Water flags as {@code WaterSurfaceResolver} stores them (its private {@code WATER_NONE}, … constants). */
   static final byte FLAG_NONE = 0;
   static final byte FLAG_INLAND = 1;
   static final byte FLAG_OCEAN = 2;
   private static final Map<EarthGeneratorSettings, NzWater> WATERS = new ConcurrentHashMap<>();

   private final EarthGeneratorSettings settings;
   private final NzTerrainSampler sampler;
   private volatile NzInlandWater inland;

   private NzWater(EarthGeneratorSettings settings) {
      this.settings = settings;
      this.sampler = NzTerrainSampler.forSettings(settings);
   }

   /** The water of an NZ world's settings, or null for any other world. */
   public static NzWater forSettings(EarthGeneratorSettings settings) {
      return NzWorldSettings.isNzWorld(settings) ? WATERS.computeIfAbsent(settings, NzWater::new) : null;
   }

   /** The water column at block (x, z), for data of about {@code resolutionMetres} per sample (any when not finite). */
   public WaterSurfaceResolver.WaterColumnData column(int x, int z, double resolutionMetres) {
      NzSeaFloor floor = this.sampler.seaFloor();
      int level = NzTerrainSampler.levelFor(resolutionMetres);
      int terrain = NzTerrainSampler.sample(floor, this.settings, x, z, level).terrainHeight();
      return column(this.inlandFor(floor.tiles()), x, z, level, terrain);
   }

   /** The water resolver's region: {@code size} × {@code size} columns from block (minX, minZ), row by row. */
   public Region region(int minX, int minZ, int size) {
      NzSeaFloor floor = this.sampler.seaFloor();
      return region(floor, this.inlandFor(floor.tiles()), this.settings, minX, minZ, size);
   }

   /**
    * DH's LOD water for a grid of points ({@code DhLodWaterResolver.resolveArea}): the column at each point, at the
    * tile level of the cell size, over DH's own terrain sample.
    */
   public DhLodWaterResolver.AreaResult lodArea(int cellSize, int[] worldXs, int[] worldZs, int[] baseTerrainSurface) {
      NzTiles tiles = NzTiles.global();
      NzInlandWater inland = this.inlandFor(tiles);
      double worldScale = this.settings.worldScale();
      int level = NzTerrainSampler.levelFor(Math.max(worldScale, cellSize * worldScale));
      int points = worldXs.length;
      int[] terrain = new int[points * points];
      int[] water = new int[points * points];
      boolean[] hasWater = new boolean[points * points];
      boolean[] ocean = new boolean[points * points];
      for (int localZ = 0; localZ < points; localZ++) {
         for (int localX = 0; localX < points; localX++) {
            int index = localZ * points + localX;
            WaterSurfaceResolver.WaterColumnData column = column(inland, worldXs[localX], worldZs[localZ], level, baseTerrainSurface[index]);
            terrain[index] = column.terrainSurface();
            water[index] = column.waterSurface();
            hasWater[index] = column.hasWater();
            ocean[index] = column.isOcean();
         }
      }
      return new DhLodWaterResolver.AreaResult(terrain, water, hasWater, ocean);
   }

   /** Lakes and rivers of the current store; the store is replaced at every server start. */
   private NzInlandWater inlandFor(NzTiles tiles) {
      NzInlandWater water = this.inland;
      if (water == null || water.tiles() != tiles) {
         water = new NzInlandWater(tiles, this.settings);
         this.inland = water;
      }
      return water;
   }

   static Region region(NzSeaFloor floor, NzInlandWater inland, EarthGeneratorSettings settings, int minX, int minZ, int size) {
      int area = size * size;
      int[] terrain = new int[area];
      int[] water = new int[area];
      byte[] flags = new byte[area];
      int[] raw = new int[area];
      for (int dz = 0; dz < size; dz++) {
         for (int dx = 0; dx < size; dx++) {
            int index = dz * size + dx;
            int x = minX + dx;
            int z = minZ + dz;
            raw[index] = NzTerrainSampler.sample(floor, settings, x, z, 0).terrainHeight();
            WaterSurfaceResolver.WaterColumnData column = column(inland, x, z, 0, raw[index]);
            terrain[index] = column.terrainSurface();
            water[index] = column.waterSurface();
            flags[index] = column.isOcean() ? FLAG_OCEAN : column.hasWater() ? FLAG_INLAND : FLAG_NONE;
         }
      }
      return new Region(terrain, water, flags, raw);
   }

   /**
    * The column at block (x, z) from the finest water level from {@code minLevel} up, over the terrain Y
    * {@code terrain}: sea and outside the data at sea level over that terrain, lakes and rivers from {@code inland},
    * land without water.
    */
   static WaterSurfaceResolver.WaterColumnData column(NzInlandWater inland, int x, int z, int minLevel, int terrain) {
      NzTiles tiles = inland.tiles();
      int level = tiles.finestLevel(NzTileLayer.WATER, minLevel, x, z);
      int water = level < 0 ? NzTileLayer.WATER.fill() : tiles.sample(NzTileLayer.WATER, level, x, z);
      if (NzSeaFloor.isSea(water)) {
         int seaLevel = inland.seaLevel();
         return new WaterSurfaceResolver.WaterColumnData(true, true, Math.min(terrain, seaLevel - 1), seaLevel);
      }
      if (water == NzTerrainSampler.WATER_LAKE || water == NzTerrainSampler.WATER_RIVER) {
         int surface = inland.surfaceY(level, x >> level, z >> level);
         if (surface != NzInlandWater.NONE) {
            return new WaterSurfaceResolver.WaterColumnData(true, false, inland.floorY(level, x >> level, z >> level), surface);
         }
      }
      return new WaterSurfaceResolver.WaterColumnData(false, false, terrain, terrain);
   }

   /** Per-column arrays of one region, in the water resolver's form; {@code flags} as {@link #FLAG_NONE} …. */
   public record Region(int[] terrain, int[] water, byte[] flags, int[] raw) {
   }
}
