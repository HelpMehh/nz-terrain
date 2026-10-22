package com.yucareux.tellus.nz.terrain;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTileTestFiles;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.preload.TerrainPreloadPackage;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzTerrainSamplerTest {
   private static final long CACHE = 64L * 1024 * 1024;
   /** The NZ preset's height values (decoding its "nz" key would need a game folder). */
   private static final EarthGeneratorSettings SETTINGS = NzWorldSettings.withNz(
      EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
         {"world_scale": 3.0, "terrestrial_height_scale": 1.5, "oceanic_height_scale": 1.5, "height_offset": 63,
          "automatic_height_scaling": false, "experimental_increase_height": false}
         """)).getOrThrow(),
      Optional.of(new NzWorldSettings(Map.of()))
   );
   private static final int FIXTURE_X = -157 * 512;
   private static final int FIXTURE_Z = 175 * 512;

   @TempDir
   Path temp;

   @Test
   void onlyNzWorldsGetASampler() {
      assertNull(NzTerrainSampler.forSettings(EarthGeneratorSettings.DEFAULT));
      assertTrue(NzTerrainSampler.forSettings(SETTINGS) != null);
   }

   @Test
   void levelMatchesTheRequestedResolution() {
      assertEquals(0, NzTerrainSampler.levelFor(Double.POSITIVE_INFINITY));
      assertEquals(0, NzTerrainSampler.levelFor(Double.NaN));
      assertEquals(0, NzTerrainSampler.levelFor(3.0));
      assertEquals(0, NzTerrainSampler.levelFor(5.9));
      assertEquals(1, NzTerrainSampler.levelFor(6.0));
      assertEquals(4, NzTerrainSampler.levelFor(48.0));
      assertEquals(4, NzTerrainSampler.levelFor(95.0));
      assertEquals(5, NzTerrainSampler.levelFor(96.0));
   }

   @Test
   void landTakesItsOwnPixelAtLevelZero() {
      NzSeaFloor floor = new NzSeaFloor(NzTiles.open(NzTileTestFiles.fixtureDir(), CACHE));
      // Pixel (192, 256): height 29 = 3.625 m, LCDB 69 (indigenous forest). Y = 63 + 1.8125, rounded up.
      TerrainPreloadPackage.Sample sample = NzTerrainSampler.sample(floor, SETTINGS, FIXTURE_X + 192, FIXTURE_Z + 256, 0);

      assertEquals(65, sample.terrainHeight());
      assertEquals(NzCoverClasses.ESA_TREES, sample.coverClass());
      assertTrue(sample.landMaskKnown());
      assertTrue(sample.land());
      // Pixel (310, 147): 198 = 24.75 m, so Y 63 + 12.375 → 76.
      assertEquals(76, NzTerrainSampler.sample(floor, SETTINGS, FIXTURE_X + 310, FIXTURE_Z + 147, 0).terrainHeight());
   }

   @Test
   void seaGetsThePlaceholderFloor() {
      NzSeaFloor floor = new NzSeaFloor(NzTiles.open(NzTileTestFiles.fixtureDir(), CACHE));
      int x = FIXTURE_X + 64;
      int z = FIXTURE_Z + 128;
      TerrainPreloadPackage.Sample sample = NzTerrainSampler.sample(floor, SETTINGS, x, z, 0);
      double depth = floor.depthMetres(0, x, z);

      assertEquals(63 + (int) Math.floor(-depth / 2.0), sample.terrainHeight());
      assertEquals(NzCoverClasses.ESA_NO_DATA, sample.coverClass());
      assertTrue(sample.landMaskKnown());
      assertFalse(sample.land());
   }

   @Test
   void outsideTheDataIsFlatOcean() {
      NzSeaFloor floor = new NzSeaFloor(NzTiles.open(NzTileTestFiles.fixtureDir(), CACHE));
      TerrainPreloadPackage.Sample sample = NzTerrainSampler.sample(floor, SETTINGS, 100_000, -100_000, 0);

      // 40 m below sea level = 20 blocks.
      assertEquals(43, sample.terrainHeight());
      assertFalse(sample.land());
      assertEquals(NzCoverClasses.ESA_NO_DATA, sample.coverClass());
   }

   @Test
   void coarseLevelsInterpolateBetweenPixelCentres() throws Exception {
      // A level-1 tile with a west-east ramp: pixel column c has c metres. A level-1 pixel covers 2 blocks, so block
      // x's centre lies at pixel coordinate (x + 0.5) / 2 - 0.5 and gets that many metres.
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      int[] height = new int[512 * 512];
      for (int i = 0; i < height.length; i++) {
         height[i] = (i % 512) * 8;
      }
      Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
      layers.put(NzTileLayer.HEIGHT, height);
      layers.put(NzTileLayer.WATER, NzTileTestFiles.filled(0));
      layers.put(NzTileLayer.LANDCOVER, NzTileTestFiles.filled(43));
      NzTileTestFiles.writeTile(set, 1, 0, 0, layers);
      NzSeaFloor floor = new NzSeaFloor(NzTiles.open(this.temp, CACHE));

      assertEquals(99.75, NzTerrainSampler.bilinearElevation(floor, 1, 200, 30), 1.0e-9);
      assertEquals(100.25, NzTerrainSampler.bilinearElevation(floor, 1, 201, 30), 1.0e-9);
      // No level-0 tile, so a block-resolution request reads level 1: 100.25 m → Y 63 + 50.125, rounded up.
      TerrainPreloadPackage.Sample sample = NzTerrainSampler.sample(floor, SETTINGS, 201, 30, 0);
      assertEquals(114, sample.terrainHeight());
      assertEquals(NzCoverClasses.ESA_GRASSLAND, sample.coverClass());
   }

   @Test
   void lakesAndRiversAreLandWithWaterCover() throws Exception {
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      int[] water = NzTileTestFiles.filled(0);
      water[0] = NzTerrainSampler.WATER_LAKE;
      water[1] = NzTerrainSampler.WATER_RIVER;
      Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
      layers.put(NzTileLayer.HEIGHT, NzTileTestFiles.filled(80));
      layers.put(NzTileLayer.WATER, water);
      layers.put(NzTileLayer.LANDCOVER, NzTileTestFiles.filled(21));
      NzTileTestFiles.writeTile(set, 0, 0, 0, layers);
      NzSeaFloor floor = new NzSeaFloor(NzTiles.open(this.temp, CACHE));

      TerrainPreloadPackage.Sample lake = NzTerrainSampler.sample(floor, SETTINGS, 0, 0, 0);
      assertTrue(lake.land());
      assertEquals(NzCoverClasses.ESA_WATER, lake.coverClass());
      assertEquals(68, lake.terrainHeight());
      assertEquals(NzCoverClasses.ESA_WATER, NzTerrainSampler.sample(floor, SETTINGS, 1, 0, 0).coverClass());
      // LCDB river where the water layer says land: dry gravel.
      assertEquals(NzCoverClasses.ESA_BARE, NzTerrainSampler.sample(floor, SETTINGS, 2, 0, 0).coverClass());
   }
}
