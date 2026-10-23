package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileFormat;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.preload.TerrainPreloadPackage;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.TerrainHeightTransform;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The terrain inputs of an NZ world, read from the NZ tiles: terrain height, cover class and land mask per block.
 *
 * <p>It answers in the form of a Tellus preload-package sample, because the chunk generator, the water resolver and
 * the DH fast LOD all ask the preload view first for exactly these values and only fall back to the online sources
 * when it has none ({@code tellus-internals.md} §6.3). So one hook in {@code TerrainPreloadPackageRegistry} puts the
 * tiles under all of them.
 *
 * <ul>
 *    <li>Height: the finest available level for the requested resolution (DH asks coarser far away), bilinear
 *    between pixel centres, so level 0 gives each block its own pixel. Sea pixels and pixels outside the data use
 *    the placeholder floor of {@link NzSeaFloor}. Elevation becomes Y through Tellus's own transform, with the NZ
 *    preset's scales: {@code Y = 63 + elevation / 2}, rounded away from sea level (decided in TERR-8).</li>
 *    <li>Streams (TERR-14): at level 0, a stream's bed or a bank raised beside it ({@link NzStreams}) replaces the
 *    land's height.</li>
 *    <li>Land mask: land wherever the water layer isn't sea or nodata. Lakes and rivers are land with water cover,
 *    as on Earth.</li>
 *    <li>Cover: ESA no-data on the sea (Tellus's ocean), water on lakes and rivers, else LCDB mapped by
 *    {@link NzCoverClasses}.</li>
 * </ul>
 *
 * <p>The biome source reads data on its own, without the preload view, so it asks {@link #coverClass} and
 * {@link #elevationMetres} directly (TERR-9).
 */
public final class NzTerrainSampler {
   static final int WATER_LAND = 0;
   static final int WATER_SEA = 1;
   static final int WATER_LAKE = 2;
   static final int WATER_RIVER = 3;
   /** Height values are metres × 8. */
   private static final double HEIGHT_STEPS_PER_METRE = 8.0;
   /** One sampler per settings value, so the generator and the biome source share one sea floor cache. */
   private static final Map<EarthGeneratorSettings, NzTerrainSampler> SAMPLERS = new ConcurrentHashMap<>();

   private final EarthGeneratorSettings settings;
   private volatile NzSeaFloor seaFloor;

   NzTerrainSampler(EarthGeneratorSettings settings) {
      this.settings = settings;
   }

   /** A sampler for an NZ world's settings, or null for any other world. */
   public static NzTerrainSampler forSettings(EarthGeneratorSettings settings) {
      return NzWorldSettings.isNzWorld(settings) ? SAMPLERS.computeIfAbsent(settings, NzTerrainSampler::new) : null;
   }

   /** The inputs at block (x, z), for data of about {@code resolutionMetres} per sample (any when not finite). */
   public TerrainPreloadPackage.Sample sample(int x, int z, double resolutionMetres) {
      return sample(this.seaFloorFor(NzTiles.global()), this.settings, x, z, levelFor(resolutionMetres));
   }

   /** The cover class at block (x, z), as in {@link #sample}, at block resolution. */
   public int coverClass(int x, int z) {
      return coverClass(NzTiles.global(), x, z);
   }

   /** Elevation in metres at block (x, z), as in {@link #sample}: negative on the sea floor and outside the data. */
   public double elevationMetres(int x, int z, double resolutionMetres) {
      return elevationMetres(this.seaFloorFor(NzTiles.global()), x, z, levelFor(resolutionMetres));
   }

   static int coverClass(NzTiles tiles, int x, int z) {
      int level = tiles.finestLevel(NzTileLayer.HEIGHT, 0, x, z);
      return level < 0 ? NzCoverClasses.ESA_NO_DATA : coverClass(tiles, level, x, z);
   }

   static double elevationMetres(NzSeaFloor floor, int x, int z, int minLevel) {
      int level = floor.tiles().finestLevel(NzTileLayer.HEIGHT, minLevel, x, z);
      return level < 0 ? -NzSeaFloor.MAX_DEPTH_METRES : bilinearElevation(floor, level, x, z);
   }

   /** The sea floor of the current store, which also gives the store. */
   NzSeaFloor seaFloor() {
      return this.seaFloorFor(NzTiles.global());
   }

   /** The sea floor of the current store; the store is replaced at every server start. */
   private NzSeaFloor seaFloorFor(NzTiles tiles) {
      NzSeaFloor floor = this.seaFloor;
      if (floor == null || floor.tiles() != tiles) {
         floor = new NzSeaFloor(tiles);
         this.seaFloor = floor;
      }
      return floor;
   }

   /** The coarsest level whose pixels are no larger than {@code resolutionMetres}; 0 for block resolution or "any". */
   static int levelFor(double resolutionMetres) {
      if (!Double.isFinite(resolutionMetres) || resolutionMetres <= NzTileFormat.METRES_PER_BLOCK) {
         return 0;
      }
      int blocks = (int) Math.min(1 << NzTileFormat.MAX_LEVEL, Math.floor(resolutionMetres / NzTileFormat.METRES_PER_BLOCK + 1.0e-9));
      return 31 - Integer.numberOfLeadingZeros(blocks);
   }

   static TerrainPreloadPackage.Sample sample(NzSeaFloor floor, EarthGeneratorSettings settings, int x, int z, int minLevel) {
      NzTiles tiles = floor.tiles();
      int level = tiles.finestLevel(NzTileLayer.HEIGHT, minLevel, x, z);
      double elevation = level < 0 ? -NzSeaFloor.MAX_DEPTH_METRES : bilinearElevation(floor, level, x, z);
      int cover = level < 0 ? NzCoverClasses.ESA_NO_DATA : coverClass(tiles, level, x, z);
      boolean sea = cover == NzCoverClasses.ESA_NO_DATA;
      int terrainHeight = blockY(settings, elevation, z);
      if (level == 0 && !sea && cover != NzCoverClasses.ESA_WATER) {
         // The chunk generator takes a dry column's height from here, not from the water regions, so the streams'
         // raised banks (and their beds, for everything else that asks) must come in here.
         terrainHeight = NzWater.streamGround(settings, tiles, x, z, terrainHeight);
      }
      // Tellus marks samples whose height came from bathymetry; its LOD ocean floor then trusts the height instead of
      // asking the online source. Every NZ height is final, so all samples say so.
      boolean oceanElevationSelected = true;
      return new TerrainPreloadPackage.Sample(terrainHeight, cover, true, !sea, false, false, oceanElevationSelected);
   }

   /** Y of the top terrain block for an elevation: Tellus's transform, rounded away from sea level. */
   static int blockY(EarthGeneratorSettings settings, double elevation, int z) {
      return TerrainHeightTransform.blockOffset(
         elevation,
         z,
         settings.effectiveVerticalWorldScale(),
         settings.effectiveTerrestrialHeightScale(),
         settings.effectiveOceanicHeightScale(),
         settings.experimentalIncreaseHeight(),
         settings.automaticHeightScaling()
      ) + settings.effectiveHeightOffset();
   }

   /** ESA no-data on the sea, water on lakes and rivers, else the land cover; at a level that has the tile. */
   private static int coverClass(NzTiles tiles, int level, int x, int z) {
      int water = tiles.sample(NzTileLayer.WATER, level, x, z);
      if (NzSeaFloor.isSea(water)) {
         return NzCoverClasses.ESA_NO_DATA;
      } else if (water == WATER_LAKE || water == WATER_RIVER) {
         return NzCoverClasses.ESA_WATER;
      }
      return NzCoverClasses.esaForLcdb(tiles.sample(NzTileLayer.LANDCOVER, level, x, z));
   }

   /** Elevation in metres at the centre of block (x, z), bilinear between the centres of the level's pixels. */
   static double bilinearElevation(NzSeaFloor floor, int level, int x, int z) {
      double scale = 1 << level;
      double u = (x + 0.5) / scale - 0.5;
      double v = (z + 0.5) / scale - 0.5;
      int px = (int) Math.floor(u);
      int pz = (int) Math.floor(v);
      double fx = u - px;
      double fz = v - pz;
      double top = pixelElevation(floor, level, px, pz);
      if (fx == 0.0 && fz == 0.0) {
         return top;
      }
      double topRight = pixelElevation(floor, level, px + 1, pz);
      double bottom = pixelElevation(floor, level, px, pz + 1);
      double bottomRight = pixelElevation(floor, level, px + 1, pz + 1);
      double north = top + (topRight - top) * fx;
      double south = bottom + (bottomRight - bottom) * fx;
      return north + (south - north) * fz;
   }

   /** Elevation in metres of one pixel: the height layer on land, the placeholder floor on the sea and outside the data. */
   static double pixelElevation(NzSeaFloor floor, int level, int px, int pz) {
      NzTiles tiles = floor.tiles();
      int water = tiles.pixel(NzTileLayer.WATER, level, px, pz);
      int height = tiles.pixel(NzTileLayer.HEIGHT, level, px, pz);
      if (NzSeaFloor.isSea(water) || NzTileLayer.HEIGHT.isNodata(height)) {
         return -floor.depthMetres(level, px, pz);
      }
      return height / HEIGHT_STEPS_PER_METRE;
   }
}
