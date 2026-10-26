package com.yucareux.tellus.nz.terrain;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTileTestFiles;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzCavesTest {
   private static final long CACHE = 64L * 1024 * 1024;
   private static final EarthGeneratorSettings SETTINGS = NzWorldSettings.withNz(
      EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
         {"world_scale": 3.0, "terrestrial_height_scale": 1.5, "oceanic_height_scale": 1.5, "height_offset": 63,
          "automatic_height_scaling": false, "experimental_increase_height": false}
         """)).getOrThrow(),
      Optional.of(new NzWorldSettings(Map.of()))
   );
   /** A cliff along x = 0: Y 100 to the west, Y 60 from x = 0 east. */
   private static final IntBinaryOperator CLIFF = (x, z) -> x < 0 ? 100 : 60;

   /** Game folder for the cache's tile store lookup (an empty tiles folder). */
   @TempDir
   static Path gameDir;

   @TempDir
   Path temp;

   @BeforeAll
   static void useTestGameDir() throws IOException {
      System.setProperty("tellus.gameDir", gameDir.toString());
      System.setProperty("tellus.configDir", gameDir.resolve("config").toString());
      Files.createDirectories(gameDir.resolve("nz-tiles"));
   }

   @AfterAll
   static void restoreGameDir() {
      System.clearProperty("tellus.gameDir");
      System.clearProperty("tellus.configDir");
   }

   @Test
   void flatGroundKeepsItsSurface() {
      int[] surfaces = NzCaves.caveSurfaces(32, -48, (x, z) -> 200);
      int[] expected = new int[256];
      Arrays.fill(expected, 200);
      assertArrayEquals(expected, surfaces);
   }

   @Test
   void theCliffTopIsMeasuredFromTheFoot() {
      // Chunk x -16..-1, on the cliff top: the 6 columns nearest the edge see the foot, the rest keep their own top.
      int[] surfaces = NzCaves.caveSurfaces(-16, 0, CLIFF);
      for (int localX = 0; localX < 16; localX++) {
         int x = -16 + localX;
         assertEquals(x >= -NzCaves.RADIUS_BLOCKS ? 60 : 100, surfaces[5 * 16 + localX], "x " + x);
      }
   }

   @Test
   void theCaveSurfaceIsTheLowestGroundInTheSquare() {
      Random random = new Random(23);
      int[] field = new int[64 * 64];
      for (int i = 0; i < field.length; i++) {
         field[i] = 60 + random.nextInt(200);
      }
      // Blocks -24..39 in both axes.
      IntBinaryOperator heights = (x, z) -> field[(z + 24) * 64 + x + 24];

      int[] surfaces = NzCaves.caveSurfaces(-8, 8, heights);

      int r = NzCaves.RADIUS_BLOCKS;
      for (int localZ = 0; localZ < 16; localZ++) {
         for (int localX = 0; localX < 16; localX++) {
            int lowest = Integer.MAX_VALUE;
            for (int dz = -r; dz <= r; dz++) {
               for (int dx = -r; dx <= r; dx++) {
                  lowest = Math.min(lowest, heights.applyAsInt(-8 + localX + dx, 8 + localZ + dz));
               }
            }
            assertEquals(lowest, surfaces[localZ * 16 + localX]);
         }
      }
   }

   @Test
   void onlyNzWorldsLowerTheCaveSurface() {
      int[] surfaces = new int[256];
      Arrays.fill(surfaces, 100);
      NzCaves.lowerToCaveSurface(EarthGeneratorSettings.DEFAULT, -16, 0, surfaces, CLIFF);
      assertEquals(100, surfaces[15]);
      assertEquals(100, NzCaves.placementSurfaceY(EarthGeneratorSettings.DEFAULT, -1, 0, 100, CLIFF));

      NzCaves.lowerToCaveSurface(SETTINGS, -16, 0, surfaces, CLIFF);
      assertEquals(100, surfaces[0]);
      assertEquals(60, surfaces[15]);
   }

   @Test
   void aLowerSurfaceStaysLower() {
      // A wet column: the generator's surface is the bed, below the sampled height.
      int[] surfaces = new int[256];
      Arrays.fill(surfaces, 100);
      surfaces[0] = 40;
      NzCaves.lowerToCaveSurface(SETTINGS, -16, 16, surfaces, CLIFF);
      assertEquals(40, surfaces[0]);
      assertEquals(40, NzCaves.placementSurfaceY(SETTINGS, -16, 16, 40, CLIFF));
   }

   @Test
   void featuresArePlacedBelowTheCaveSurface() {
      assertEquals(60, NzCaves.placementSurfaceY(SETTINGS, -3, 37, 100, CLIFF));
      assertEquals(100, NzCaves.placementSurfaceY(SETTINGS, -9, -37, 100, CLIFF));
   }

   @Test
   void nzWorldsDropSurfaceLavaLakesAndAzaleaTrees() {
      assertTrue(NzCaves.dropsFeature(SETTINGS, "lake_lava_surface"));
      assertTrue(NzCaves.dropsFeature(SETTINGS, "rooted_azalea_tree"));
      assertFalse(NzCaves.dropsFeature(SETTINGS, "lake_lava_underground"));
      assertFalse(NzCaves.dropsFeature(SETTINGS, "spring_lava"));
      assertFalse(NzCaves.dropsFeature(EarthGeneratorSettings.DEFAULT, "rooted_azalea_tree"));
   }

   @Test
   void lushCavesOnlyUnderForest() throws Exception {
      // Tile 0, 0 without a vegetation layer: forest (LCDB 69) in the west half, tussock (43) in the east, a lake row.
      // Tile 1, 0 with one: cool lowland forest (132) in the west half, snow tussock (111) in the east.
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      int[] cover = new int[512 * 512];
      int[] water = new int[512 * 512];
      int[] vegetation = new int[512 * 512];
      for (int i = 0; i < cover.length; i++) {
         boolean west = i % 512 < 256;
         cover[i] = west ? 69 : 43;
         vegetation[i] = west ? 132 : 111;
         water[i] = i / 512 == 300 ? NzTerrainSampler.WATER_LAKE : NzTerrainSampler.WATER_LAND;
      }
      Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
      layers.put(NzTileLayer.HEIGHT, NzTileTestFiles.filled(800));
      layers.put(NzTileLayer.WATER, water);
      layers.put(NzTileLayer.LANDCOVER, cover);
      NzTileTestFiles.writeTile(set, 0, 0, 0, layers);
      Map<NzTileLayer, int[]> withVegetation = new LinkedHashMap<>(layers);
      withVegetation.put(NzTileLayer.LANDCOVER, NzTileTestFiles.filled(40));
      withVegetation.put(NzTileLayer.VEGETATION, vegetation);
      NzTileTestFiles.writeTile(set, 0, 1, 0, withVegetation);
      NzTiles tiles = NzTiles.open(this.temp, CACHE);

      assertTrue(NzCaves.lushCavesUnder(tiles, 100, 100));
      assertFalse(NzCaves.lushCavesUnder(tiles, 400, 100));
      assertFalse(NzCaves.lushCavesUnder(tiles, 100, 300), "under a lake");
      // The vegetation layer wins over the land cover (pasture, 40, here).
      assertTrue(NzCaves.lushCavesUnder(tiles, 512 + 100, 100));
      assertFalse(NzCaves.lushCavesUnder(tiles, 512 + 400, 100));
      assertFalse(NzCaves.lushCavesUnder(tiles, -100, 100), "outside the data");
   }
}
