package com.yucareux.tellus.nz.terrain;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTileTestFiles;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzWaterTest {
   private static final long CACHE = 64L * 1024 * 1024;
   /** The NZ preset's height values (decoding its "nz" key would need a game folder). */
   private static final EarthGeneratorSettings SETTINGS = NzWorldSettings.withNz(
      EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
         {"world_scale": 3.0, "terrestrial_height_scale": 1.5, "oceanic_height_scale": 1.5, "height_offset": 63,
          "automatic_height_scaling": false, "experimental_increase_height": false}
         """)).getOrThrow(),
      Optional.of(new NzWorldSettings(Map.of()))
   );
   private static final int NODATA = -32768;

   @TempDir
   Path temp;

   private NzSeaFloor floor;
   private NzInlandWater inland;

   /**
    * Tiles (0, 0) and (1, 0) at level 0; every other tile is missing, so it counts as sea. Land 100 m high, with:
    * <ul>
    *    <li>a lake in pixels 100–299 × 100–299 of tile (0, 0), surface 51 m, a 4 m deep spot in the LiDAR at
    *    (150, 150) and a measured bed of 30 m at (250, 250);</li>
    *    <li>a river 30 blocks wide in rows 400–429 of both tiles, 40 m high west of x 256 and 38 m east of it,
    *    with one block over a bank (46 m) at x 100.</li>
    * </ul>
    */
   @BeforeEach
   void writeTiles() throws Exception {
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      for (int tx = 0; tx <= 1; tx++) {
         int[] water = NzTileTestFiles.filled(NzTerrainSampler.WATER_LAND);
         int[] height = NzTileTestFiles.filled(metres(100.0));
         int[] waterLevel = NzTileTestFiles.filled(NODATA);
         int[] bed = NzTileTestFiles.filled(NODATA);
         for (int pz = 400; pz < 430; pz++) {
            for (int px = 0; px < 512; px++) {
               int x = tx * 512 + px;
               int surface = metres(x < 256 ? 40.0 : 38.0);
               set(water, px, pz, NzTerrainSampler.WATER_RIVER);
               set(height, px, pz, surface);
               set(waterLevel, px, pz, surface);
            }
         }
         if (tx == 0) {
            for (int pz = 100; pz < 300; pz++) {
               for (int px = 100; px < 300; px++) {
                  set(water, px, pz, NzTerrainSampler.WATER_LAKE);
                  set(height, px, pz, metres(51.0));
                  set(waterLevel, px, pz, metres(51.0));
               }
            }
            set(height, 150, 150, metres(4.0));
            set(bed, 250, 250, metres(30.0));
            set(height, 100, 400, metres(46.0));
            set(waterLevel, 100, 400, metres(46.0));
         }
         Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
         layers.put(NzTileLayer.HEIGHT, height);
         layers.put(NzTileLayer.WATER, water);
         layers.put(NzTileLayer.WATER_LEVEL, waterLevel);
         layers.put(NzTileLayer.BED, bed);
         layers.put(NzTileLayer.LANDCOVER, NzTileTestFiles.filled(43));
         NzTileTestFiles.writeTile(set, 0, tx, 0, layers);
      }
      NzTiles tiles = NzTiles.open(this.temp, CACHE);
      this.floor = new NzSeaFloor(tiles);
      this.inland = new NzInlandWater(tiles, SETTINGS);
   }

   @Test
   void onlyNzWorldsGetWater() {
      assertNull(NzWater.forSettings(EarthGeneratorSettings.DEFAULT));
      assertTrue(NzWater.forSettings(SETTINGS) != null);
   }

   @Test
   void waterSurfaceRoundsToTheNearestBlock() {
      assertEquals(63, NzInlandWater.waterY(SETTINGS, 0.0, 0));
      assertEquals(63, NzInlandWater.waterY(SETTINGS, 0.9, 0));
      assertEquals(64, NzInlandWater.waterY(SETTINGS, 1.0, 0));
      assertEquals(89, NzInlandWater.waterY(SETTINGS, 51.0, 0));
      assertEquals(62, NzInlandWater.waterY(SETTINGS, -3.0, 0));
   }

   @Test
   void depthsFollowTheDistanceToLand() {
      assertEquals(1, NzInlandWater.riverDepthBlocks(1.0));
      assertEquals(1, NzInlandWater.riverDepthBlocks(6.9));
      assertEquals(2, NzInlandWater.riverDepthBlocks(7.0));
      assertEquals(3, NzInlandWater.riverDepthBlocks(13.0));
      assertEquals(3, NzInlandWater.riverDepthBlocks(Double.POSITIVE_INFINITY));
      // 1 block = 3 m from the shore: 0.75 m, raised to the 2 m minimum = 1 block.
      assertEquals(1, this.inland.lakeDepthBlocks(1.0));
      // 10 blocks = 30 m: 7.5 m = 3.75 blocks.
      assertEquals(4, this.inland.lakeDepthBlocks(10.0));
      assertEquals(20, this.inland.lakeDepthBlocks(Double.POSITIVE_INFINITY));
   }

   @Test
   void lakeHasOneSurfaceAndDeepensFromTheShore() {
      WaterSurfaceResolver.WaterColumnData shore = this.column(100, 200);
      assertTrue(shore.hasWater());
      assertFalse(shore.isOcean());
      assertEquals(89, shore.waterSurface());
      assertEquals(88, shore.terrainSurface());
      assertEquals(89 - 4, this.column(109, 200).terrainSurface());
      WaterSurfaceResolver.WaterColumnData middle = this.column(200, 200);
      assertEquals(89, middle.waterSurface());
      assertEquals(89 - 20, middle.terrainSurface());
   }

   @Test
   void lowerLidarAndMeasuredBedWin() {
      // LiDAR 4 m → Y 65, deeper than the invented floor (51 blocks from the shore: 19 blocks deep, Y 70).
      assertEquals(65, this.column(150, 150).terrainSurface());
      // A measured bed of 30 m → Y 78, even though the invented floor would be deeper.
      assertEquals(78, this.column(250, 250).terrainSurface());
   }

   @Test
   void riverIsLevelAcrossAndStepsDownstream() {
      // 40 m → Y 83 west of x 256, 38 m → Y 82 east of it; blocks within 2 of the step take the lower level.
      for (int z = 400; z < 430; z++) {
         assertEquals(83, this.column(200, z).waterSurface());
         assertEquals(82, this.column(254, z).waterSurface());
         assertEquals(82, this.column(300, z).waterSurface());
      }
      // The block over the bank (46 m) takes the river level beside it.
      assertEquals(83, this.column(100, 400).waterSurface());
      // 1 block deep at the bank, 2 from 7 blocks out, 3 from 13 blocks out.
      assertEquals(82, this.column(200, 400).terrainSurface());
      assertEquals(81, this.column(200, 406).terrainSurface());
      assertEquals(80, this.column(200, 414).terrainSurface());
   }

   @Test
   void floorsMatchAcrossTileBorders() {
      for (int z = 400; z < 430; z++) {
         WaterSurfaceResolver.WaterColumnData west = this.column(511, z);
         WaterSurfaceResolver.WaterColumnData east = this.column(512, z);
         assertEquals(west.waterSurface(), east.waterSurface());
         assertEquals(west.terrainSurface(), east.terrainSurface());
      }
   }

   @Test
   void seaAndLand() {
      // Tile (0, 1) is missing: sea at Y 63 over the placeholder floor.
      WaterSurfaceResolver.WaterColumnData sea = this.column(10, 600);
      assertTrue(sea.isOcean());
      assertEquals(63, sea.waterSurface());
      assertEquals(NzTerrainSampler.sample(this.floor, SETTINGS, 10, 600, 0).terrainHeight(), sea.terrainSurface());
      WaterSurfaceResolver.WaterColumnData land = this.column(50, 50);
      assertFalse(land.hasWater());
      assertEquals(113, land.terrainSurface());
      assertEquals(113, land.waterSurface());
   }

   @Test
   void regionIsTheColumnBlockByBlock() {
      int minX = 64;
      int minZ = 384;
      NzWater.Region region = NzWater.region(this.floor, this.inland, SETTINGS, minX, minZ, 64);
      for (int dz = 0; dz < 64; dz++) {
         for (int dx = 0; dx < 64; dx++) {
            int index = dz * 64 + dx;
            WaterSurfaceResolver.WaterColumnData column = this.column(minX + dx, minZ + dz);
            assertEquals(column.terrainSurface(), region.terrain()[index]);
            assertEquals(column.waterSurface(), region.water()[index]);
            assertEquals(column.hasWater() ? NzWater.FLAG_INLAND : NzWater.FLAG_NONE, region.flags()[index]);
            assertEquals(NzTerrainSampler.sample(this.floor, SETTINGS, minX + dx, minZ + dz, 0).terrainHeight(), region.raw()[index]);
         }
      }
   }

   @Test
   void minimumFilterTakesTheSquareAround() {
      int big = Integer.MAX_VALUE;
      int[] values = {
         5, big, big, big,
         big, big, big, big,
         big, big, big, 1,
         big, big, big, big
      };
      int[] expected = {
         5, 5, big, big,
         5, 5, 1, 1,
         big, big, 1, 1,
         big, big, 1, 1
      };
      assertArrayEquals(expected, NzInlandWater.minimumFilter(values, 4, 1));
   }

   private WaterSurfaceResolver.WaterColumnData column(int x, int z) {
      int terrain = NzTerrainSampler.sample(this.floor, SETTINGS, x, z, 0).terrainHeight();
      return NzWater.column(this.inland, x, z, 0, terrain);
   }

   private static int metres(double metres) {
      return (int) Math.round(metres * 8.0);
   }

   private static void set(int[] values, int px, int pz, int value) {
      values[pz * 512 + px] = value;
   }
}
