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
 * §11): sea at sea level over the tiles' sea floor, lakes and rivers from {@link NzInlandWater}, and streams on land
 * from {@link NzStreams} (TERR-14), including the banks they raise.
 *
 * <p>Tellus's water resolver and its DH LOD water resolver each hand NZ worlds to this class in one hook, so none of
 * their own analysis (water bodies, lake surface guesses, river reaches, shore blending) runs for NZ. Both answer
 * every block the same way: the resolver's 64 × 64 regions are just this class's column, block by block, and DH
 * asks the same column at a coarser tile level.
 *
 * <p>Steps (TERR-14): the chunk generator asks {@link #isStepSource} for every water column it fills (lakes, rivers
 * and streams), and has the top block of a step flow once the chunk is done, so the water pours over the step instead of standing as a wall. The
 * NZ {@code FlowingFluidMixin} asks {@link #suppressesSourceConversion}, so that the flow can't make new sources at a
 * step's corners and fill the reach below.
 */
public final class NzWater {
   /** Water flags as {@code WaterSurfaceResolver} stores them (its private {@code WATER_NONE}, … constants). */
   static final byte FLAG_NONE = 0;
   static final byte FLAG_INLAND = 1;
   static final byte FLAG_OCEAN = 2;
   /** No new water sources within this many blocks (a square) of a step; 1 covers every block beside one. */
   static final int NO_NEW_SOURCE_RADIUS_BLOCKS = 2;
   private static final Map<EarthGeneratorSettings, NzWater> WATERS = new ConcurrentHashMap<>();
   /** The last one handed out: the chunk generator asks for every water column, and hashing the settings is slow. */
   private static volatile NzWater last;

   private final EarthGeneratorSettings settings;
   private final NzTerrainSampler sampler;
   private volatile NzStreams streams;

   private NzWater(EarthGeneratorSettings settings) {
      this.settings = settings;
      this.sampler = NzTerrainSampler.forSettings(settings);
   }

   /** The water of an NZ world's settings, or null for any other world. */
   public static NzWater forSettings(EarthGeneratorSettings settings) {
      NzWater water = last;
      if (water != null && water.settings == settings) {
         return water;
      }
      if (!NzWorldSettings.isNzWorld(settings)) {
         return null;
      }
      water = WATERS.computeIfAbsent(settings, NzWater::new);
      last = water;
      return water;
   }

   /** The water column at block (x, z), for data of about {@code resolutionMetres} per sample (any when not finite). */
   public WaterSurfaceResolver.WaterColumnData column(int x, int z, double resolutionMetres) {
      NzSeaFloor floor = this.sampler.seaFloor();
      int level = NzTerrainSampler.levelFor(resolutionMetres);
      int terrain = NzTerrainSampler.sample(floor, this.settings, x, z, level).terrainHeight();
      return column(this.streamsFor(floor.tiles()), x, z, level, terrain);
   }

   /** Whether block (x, z) is a lake, river or stream block with lower water on one of its four sides: a step that flows. */
   public boolean isStepSource(int x, int z) {
      return isStepSource(this.streamsFor(this.sampler.seaFloor().tiles()), x, z);
   }

   /** Whether land block (x, z) is a ledge in a slot gorge, between the stream and the wall: it gets the stream bed. */
   public boolean isGorgeLedge(int x, int z) {
      return this.streamsFor(this.sampler.seaFloor().tiles()).isGorgeLedge(x, z);
   }

   /** Whether flowing water at block (x, z) may not become a source: near a step, see {@link #NO_NEW_SOURCE_RADIUS_BLOCKS}. */
   public boolean suppressesSourceConversion(int x, int z) {
      return suppressesSourceConversion(this.streamsFor(this.sampler.seaFloor().tiles()), x, z);
   }

   /** The water resolver's region: {@code size} × {@code size} columns from block (minX, minZ), row by row. */
   public Region region(int minX, int minZ, int size) {
      NzSeaFloor floor = this.sampler.seaFloor();
      return region(floor, this.streamsFor(floor.tiles()), this.settings, minX, minZ, size);
   }

   /**
    * DH's LOD water for a grid of points ({@code DhLodWaterResolver.resolveArea}): the column at each point, at the
    * tile level of the cell size, over DH's own terrain sample.
    */
   public DhLodWaterResolver.AreaResult lodArea(int cellSize, int[] worldXs, int[] worldZs, int[] baseTerrainSurface) {
      NzTiles tiles = NzTiles.global();
      NzStreams streams = this.streamsFor(tiles);
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
            WaterSurfaceResolver.WaterColumnData column = column(streams, worldXs[localX], worldZs[localZ], level, baseTerrainSurface[index]);
            terrain[index] = column.terrainSurface();
            water[index] = column.waterSurface();
            hasWater[index] = column.hasWater();
            ocean[index] = column.isOcean();
         }
      }
      return new DhLodWaterResolver.AreaResult(terrain, water, hasWater, ocean);
   }

   /**
    * The terrain Y of land block (x, z) given {@code terrain} from the tiles: a stream's bed, or a bank raised beside a
    * stream, where the streams change it ({@link NzTerrainSampler} asks, at level 0).
    */
   static int streamGround(EarthGeneratorSettings settings, NzTiles tiles, int x, int z, int terrain) {
      NzWater water = forSettings(settings);
      if (water == null) {
         return terrain;
      }
      int ground = water.streamsFor(tiles).groundY(x, z);
      return ground == NzStreams.NONE ? terrain : ground;
   }

   /** The streams of the current store, with their lakes and rivers ({@link NzBoulders} asks). */
   NzStreams streams() {
      return this.streamsFor(this.sampler.seaFloor().tiles());
   }

   /** The sea floor of the current store, which also gives the store. */
   NzSeaFloor seaFloor() {
      return this.sampler.seaFloor();
   }

   /** Streams, and through them lakes and rivers, of the current store; the store is replaced at every server start. */
   private NzStreams streamsFor(NzTiles tiles) {
      NzStreams water = this.streams;
      if (water == null || water.inland().tiles() != tiles) {
         water = new NzStreams(new NzInlandWater(tiles, this.settings), this.settings);
         this.streams = water;
      }
      return water;
   }

   static Region region(NzSeaFloor floor, NzStreams streams, EarthGeneratorSettings settings, int minX, int minZ, int size) {
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
            WaterSurfaceResolver.WaterColumnData column = column(streams, x, z, 0, raw[index]);
            terrain[index] = column.terrainSurface();
            water[index] = column.waterSurface();
            flags[index] = column.isOcean() ? FLAG_OCEAN : column.hasWater() ? FLAG_INLAND : FLAG_NONE;
         }
      }
      return new Region(terrain, water, flags, raw);
   }

   /**
    * The column at block (x, z) from the finest water level from {@code minLevel} up, over the terrain Y
    * {@code terrain}: sea and outside the data at sea level over that terrain, lakes and rivers from
    * {@link NzInlandWater}; on land at level 0 a stream or a bank raised beside one from {@code streams}; otherwise
    * land without water.
    */
   static WaterSurfaceResolver.WaterColumnData column(NzStreams streams, int x, int z, int minLevel, int terrain) {
      NzInlandWater inland = streams.inland();
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
      } else if (level == 0 && water == NzTerrainSampler.WATER_LAND) {
         int surface = streams.surfaceY(x, z);
         if (surface != NzStreams.NONE) {
            return new WaterSurfaceResolver.WaterColumnData(true, false, streams.groundY(x, z), surface);
         }
         int bank = streams.groundY(x, z);
         if (bank > terrain) {
            return new WaterSurfaceResolver.WaterColumnData(false, false, bank, bank);
         }
      }
      return new WaterSurfaceResolver.WaterColumnData(false, false, terrain, terrain);
   }

   static boolean isStepSource(NzStreams streams, int x, int z) {
      int level = streams.inland().tiles().finestLevel(NzTileLayer.WATER, 0, x, z);
      return level >= 0 && (streams.inland().isStep(level, x >> level, z >> level) || level == 0 && streams.isStep(x, z));
   }

   static boolean suppressesSourceConversion(NzStreams streams, int x, int z) {
      for (int dz = -NO_NEW_SOURCE_RADIUS_BLOCKS; dz <= NO_NEW_SOURCE_RADIUS_BLOCKS; dz++) {
         for (int dx = -NO_NEW_SOURCE_RADIUS_BLOCKS; dx <= NO_NEW_SOURCE_RADIUS_BLOCKS; dx++) {
            if (isStepSource(streams, x + dx, z + dz)) {
               return true;
            }
         }
      }
      return false;
   }

   /** Per-column arrays of one region, in the water resolver's form; {@code flags} as {@link #FLAG_NONE} …. */
   public record Region(int[] terrain, int[] water, byte[] flags, int[] raw) {
   }
}
