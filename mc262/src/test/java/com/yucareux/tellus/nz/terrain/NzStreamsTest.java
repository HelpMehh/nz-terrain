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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzStreamsTest {
   private static final long CACHE = 64L * 1024 * 1024;
   /** The NZ preset's height values, as in {@code NzWaterTest}. */
   private static final EarthGeneratorSettings SETTINGS = NzWorldSettings.withNz(
      EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
         {"world_scale": 3.0, "terrestrial_height_scale": 1.5, "oceanic_height_scale": 1.5, "height_offset": 63,
          "automatic_height_scaling": false, "experimental_increase_height": false}
         """)).getOrThrow(),
      Optional.of(new NzWorldSettings(Map.of()))
   );
   private static final int NODATA = -32768;
   private static final int NONE = NzStreams.NONE;

   @TempDir
   Path temp;

   private NzSeaFloor floor;
   private NzStreams streams;

   /**
    * Tiles (0, 0) and (1, 0) at level 0, land 100 m high (Y 113), with:
    * <ul>
    *    <li>a creek (class 40, 1 block wide) along row 50 from x 10 to 200, 80 m high (Y 103) west of x 100 and 78 m
    *    (Y 102) east of it, in a shallow channel (rows 40–60 at 84 m: Y 105, too shallow for a gorge), into a river at
    *    x 201–210 (rows 45–55, 77 m: Y 102), with a hollow at 70 m beside it at (150, 51);</li>
    *    <li>a diagonal creek (class 30) at 90 m from (300, 100) to (320, 120);</li>
    *    <li>a big stream (class 90, 12 blocks wide) along row 300 from x 400 to 700, across the tile border, at 60 m
    *    (Y 93) in a gully whose floor is 7 blocks wide (rows 297–303, 61 m: Y 94);</li>
    *    <li>a creek (class 50: 1 block wide, walls up to 9 blocks) along row 400 from x 780 to 1000 at 60 m (Y 93), in
    *    a shallow valley (0.5 m per block) except for a deep V (4 m per block: 2 blocks per block in game) from x 800 to 900 and from x 950 to 952.</li>
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
         int[] streams = NzTileTestFiles.filled(0);
         int[] level = NzTileTestFiles.filled(NODATA);
         for (int x = tx * 512; x < (tx + 1) * 512; x++) {
            int px = x - tx * 512;
            if (x >= 10 && x <= 200) {
               for (int pz = 40; pz <= 60; pz++) {
                  set(height, px, pz, metres(84.0));
               }
               double creek = x < 100 ? 80.0 : 78.0;
               stream(streams, level, height, px, 50, 40, creek);
            }
            if (x >= 201 && x <= 210) {
               for (int pz = 45; pz <= 55; pz++) {
                  set(water, px, pz, NzTerrainSampler.WATER_RIVER);
                  set(height, px, pz, metres(77.0));
                  set(waterLevel, px, pz, metres(77.0));
               }
            }
            if (x >= 300 && x <= 320) {
               stream(streams, level, height, px, 100 + x - 300, 30, 90.0);
            }
            if (x >= 400 && x <= 700) {
               for (int pz = 297; pz <= 303; pz++) {
                  set(height, px, pz, metres(61.0));
               }
               stream(streams, level, height, px, 300, 90, 60.0);
            }
            if (x >= 780 && x <= 1000) {
               double rise = x >= 800 && x <= 900 || x >= 950 && x <= 952 ? 4.0 : 0.5;
               for (int pz = 380; pz <= 420; pz++) {
                  set(height, px, pz, metres(Math.min(100.0, 60.0 + rise * Math.abs(pz - 400))));
               }
               stream(streams, level, height, px, 400, 50, 60.0);
            }
         }
         if (tx == 0) {
            set(height, 150, 51, metres(70.0));
         }
         Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
         layers.put(NzTileLayer.HEIGHT, height);
         layers.put(NzTileLayer.WATER, water);
         layers.put(NzTileLayer.WATER_LEVEL, waterLevel);
         layers.put(NzTileLayer.LANDCOVER, NzTileTestFiles.filled(69));
         layers.put(NzTileLayer.STREAMS, streams);
         layers.put(NzTileLayer.STREAM_LEVEL, level);
         NzTileTestFiles.writeTile(set, 0, tx, 0, layers);
      }
      NzTiles tiles = NzTiles.open(this.temp, CACHE);
      this.floor = new NzSeaFloor(tiles);
      this.streams = new NzStreams(new NzInlandWater(tiles, SETTINGS), SETTINGS);
   }

   @Test
   void widthIsOneToOneFromTheCatchment() {
      assertEquals(1, NzStreams.widthBlocks(8));
      assertEquals(1, NzStreams.widthBlocks(40));
      // Class 51 is 100 ha (1 km²): 2 m.
      assertEquals(2, NzStreams.widthBlocks(51));
      // Class 76 is 1,000 ha: 6.3 m.
      assertEquals(6, NzStreams.widthBlocks(76));
      assertEquals(NzStreams.MAX_WIDTH_BLOCKS, NzStreams.widthBlocks(254));
      assertEquals(1, NzStreams.depthBlocks(75));
      assertEquals(2, NzStreams.depthBlocks(76));
      assertEquals(3, NzStreams.depthBlocks(101));
   }

   @Test
   void aCreekIsOneBlockWideAtItsLevel() {
      assertEquals(103, this.streams.surfaceY(50, 50));
      assertEquals(102, this.streams.groundY(50, 50));
      assertEquals(NONE, this.streams.surfaceY(50, 49));
      assertEquals(NONE, this.streams.surfaceY(50, 51));
      assertEquals(102, this.streams.surfaceY(150, 50));
      // Land beside it stays as it is.
      assertEquals(NONE, this.streams.groundY(50, 49));
   }

   @Test
   void aStepFlowsOverABedThatRunsOn() {
      assertTrue(this.streams.isStep(99, 50));
      assertFalse(this.streams.isStep(98, 50));
      assertFalse(this.streams.isStep(100, 50));
      // The upper reach is 1 block deeper within the sill radius: its bed is level with the lower reach's.
      assertEquals(101, this.streams.groundY(99, 50));
      assertEquals(101, this.streams.groundY(98, 50));
      assertEquals(102, this.streams.groundY(97, 50));
      assertEquals(101, this.streams.groundY(100, 50));
      assertTrue(NzWater.isStepSource(this.streams, 99, 50));
      assertTrue(NzWater.suppressesSourceConversion(this.streams, 101, 51));
   }

   @Test
   void aLowBankIsRaisedToHoldTheWater() {
      assertEquals(102, this.streams.groundY(150, 51));
      WaterSurfaceResolver.WaterColumnData bank = this.column(150, 51);
      assertFalse(bank.hasWater());
      assertEquals(102, bank.terrainSurface());
   }

   @Test
   void theSamplerGivesBanksAndBedsToDryColumns() {
      // The chunk generator takes a dry column's height from the sampler, not from the water column.
      assertEquals(102, NzTerrainSampler.sample(this.floor, SETTINGS, 150, 51, 0).terrainHeight());
      assertEquals(102, NzTerrainSampler.sample(this.floor, SETTINGS, 50, 50, 0).terrainHeight());
      assertEquals(105, NzTerrainSampler.sample(this.floor, SETTINGS, 50, 49, 0).terrainHeight());
   }

   @Test
   void theColumnIsStreamWaterOnLand() {
      WaterSurfaceResolver.WaterColumnData creek = this.column(50, 50);
      assertTrue(creek.hasWater());
      assertFalse(creek.isOcean());
      assertEquals(103, creek.waterSurface());
      assertEquals(102, creek.terrainSurface());
      WaterSurfaceResolver.WaterColumnData land = this.column(50, 49);
      assertFalse(land.hasWater());
      assertEquals(105, land.terrainSurface());
   }

   @Test
   void diagonalLinksGetACornerBlock() {
      for (int k = 0; k < 20; k++) {
         int x = 300 + k;
         int z = 100 + k;
         assertEquals(108, this.streams.surfaceY(x, z));
         // Of the two blocks between (x, z) and (x + 1, z + 1), the one in the lower row (lower index) on a tie.
         assertEquals(108, this.streams.surfaceY(x + 1, z), "corner at " + (x + 1) + ", " + z);
         assertEquals(NONE, this.streams.surfaceY(x, z + 1));
      }
   }

   @Test
   void aBigStreamFillsItsGullyFloorAcrossTheTileBorder() {
      for (int x = 400; x <= 700; x++) {
         for (int z = 297; z <= 303; z++) {
            assertEquals(93, this.streams.surfaceY(x, z), x + ", " + z);
         }
         // Capped at the floor: 12 blocks would reach rows 295–305, the walls stay.
         assertEquals(NONE, this.streams.surfaceY(x, 296), x + ", 296");
         assertEquals(NONE, this.streams.surfaceY(x, 304), x + ", 304");
         // 1 block deep at the edge, 2 inside (class 90).
         if (x > 400 && x < 700) {
            assertEquals(92, this.streams.groundY(x, 297));
            assertEquals(91, this.streams.groundY(x, 300));
         }
      }
   }

   @Test
   void aDeepVBecomesASlotWithVerticalWalls() {
      // Middle of the gorge: water at Y 93, the LiDAR's V 6 blocks out is 12 blocks higher, the wall at most 9: rim Y 102.
      assertEquals(9, NzStreams.wallCap(50));
      assertEquals(93, this.streams.surfaceY(850, 400));
      assertEquals(92, this.streams.groundY(850, 400));
      int[] widths = new int[3];
      for (int x = 810; x <= 890; x++) {
         for (int d = 1; d <= 7; d++) {
            for (int z : new int[] {400 - d, 400 + d}) {
               widths[NzStreams.ledgeWidth(x, 400)]++;
               this.assertBeside(x, z, d, 102);
            }
         }
      }
      // The ledges vary along the gorge: none, 1 and 2 blocks all occur.
      assertTrue(widths[0] > 0 && widths[1] > 0 && widths[2] > 0, java.util.Arrays.toString(widths));
      // The sampler gives the wall or ledge to the chunk generator, and the column is dry.
      for (int z = 401; z <= 405; z++) {
         int ground = this.streams.groundY(850, z);
         int expected = ground == NONE ? 93 + 2 * (z - 400) : ground;
         assertEquals(expected, NzTerrainSampler.sample(this.floor, SETTINGS, 850, z, 0).terrainHeight());
         WaterSurfaceResolver.WaterColumnData column = this.column(850, z);
         assertFalse(column.hasWater());
         assertEquals(expected, column.terrainSurface());
      }
   }

   @Test
   void smallCreeksGetLowWallsBigStreamsHighOnes() {
      assertEquals(NzStreams.SMALL_WALL_BLOCKS, NzStreams.wallCap(1));
      assertEquals(NzStreams.SMALL_WALL_BLOCKS, NzStreams.wallCap(30));
      assertEquals(10, NzStreams.wallCap(53));
      assertEquals(NzStreams.GORGE_MAX_WALL_BLOCKS, NzStreams.wallCap(76));
      assertEquals(NzStreams.GORGE_MAX_WALL_BLOCKS, NzStreams.wallCap(200));
   }

   @Test
   void bendsWidenTheInsideAndNarrowTheOutside() {
      // A stream turning from west to south around (0, 0): its centreline lies to the west and the south, so the mean
      // offset points south-west, to the inside of the bend.
      double bx = -10.0 / 9.0;
      double bz = 10.0 / 9.0;
      assertEquals(1, NzStreams.bendSide(-1, 1, bx, bz));
      assertEquals(-1, NzStreams.bendSide(1, -1, bx, bz));
      // Straight: no bend.
      assertEquals(0, NzStreams.bendSide(0, 2, 0.0, 0.0));
   }

   @Test
   void rimsVaryWithinTheirRange() {
      int[] seen = new int[2 * NzStreams.RIM_JITTER_BLOCKS + 1];
      for (int x = 0; x < 200; x++) {
         for (int z = 0; z < 200; z++) {
            seen[NzStreams.rimJitter(x, z) + NzStreams.RIM_JITTER_BLOCKS]++;
         }
      }
      for (int count : seen) {
         assertTrue(count > 0, java.util.Arrays.toString(seen));
      }
   }

   @Test
   void aGorgeRisesFromItsEnds() {
      // The run starts at x 800 next to the shallow valley and ends at x 900: walls of 2, 4, 6, … blocks over the
      // stream's ground (Y 93), up to the wall's cap 9 above it.
      for (int x = 800; x <= 900; x++) {
         int along = Math.min(x - 800, 900 - x);
         for (int d = 1; d <= 5; d++) {
            this.assertBeside(x, 400 + d, d, 93 + Math.min(9, 2 * (along + 1)));
         }
      }
      // Beside the stream outside the gorge, nearer to a centreline that isn't one: untouched.
      assertEquals(NONE, this.streams.groundY(797, 402));
      assertFalse(this.streams.isGorgeLedge(797, 401));
      assertEquals(NONE, this.streams.groundY(903, 402));
   }

   @Test
   void aShortVIsNoGorge() {
      assertEquals(NONE, this.streams.groundY(951, 402));
      assertEquals(97, NzTerrainSampler.sample(this.floor, SETTINGS, 951, 402, 0).terrainHeight());
   }

   /**
    * Block (x, z), {@code d} blocks beside the gorge's water (Y 93) in the test V (LiDAR Y 93 + 2d), whose wall would
    * reach {@code rim}: a ledge within the ledge width (at most 1 above the water), else the rim give or take the
    * jitter where that is above the LiDAR, else untouched.
    */
   private void assertBeside(int x, int z, int d, int rim) {
      String at = x + ", " + z;
      int lidar = 93 + 2 * d;
      // Ledge width from the column's centreline (x, 400); the creek is straight, so no bend changes it.
      if (d <= NzStreams.ledgeWidth(x, 400)) {
         assertTrue(this.streams.isGorgeLedge(x, z), at);
         assertEquals(Math.min(lidar, 94), this.streams.groundY(x, z), at);
         return;
      }
      assertFalse(this.streams.isGorgeLedge(x, z), at);
      int wall = d * d > NzStreams.GORGE_RADIUS_BLOCKS * NzStreams.GORGE_RADIUS_BLOCKS ? NONE : rim + NzStreams.rimJitter(x, z);
      assertEquals(wall > lidar ? wall : NONE, this.streams.groundY(x, z), at);
   }

   private WaterSurfaceResolver.WaterColumnData column(int x, int z) {
      int terrain = NzTerrainSampler.sample(this.floor, SETTINGS, x, z, 0).terrainHeight();
      return NzWater.column(this.streams, x, z, 0, terrain);
   }

   /** A centreline block: size class, level and ground at {@code metres}. */
   private static void stream(int[] streams, int[] level, int[] height, int px, int pz, int sizeClass, double metres) {
      set(streams, px, pz, sizeClass);
      set(level, px, pz, metres(metres));
      set(height, px, pz, metres(metres));
   }

   private static int metres(double metres) {
      return (int) Math.round(metres * 8.0);
   }

   private static void set(int[] values, int px, int pz, int value) {
      values[pz * 512 + px] = value;
   }
}
