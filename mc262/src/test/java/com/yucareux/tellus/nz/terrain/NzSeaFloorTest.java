package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTileTestFiles;
import com.yucareux.tellus.nz.tiles.NzTiles;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NzSeaFloorTest {
   private static final long CACHE = 64L * 1024 * 1024;

   @TempDir
   Path temp;

   @Test
   void depthFollowsThePipelineRule() {
      assertEquals(4.0, NzSeaFloor.depthForDistance(3.0));
      assertEquals(4.0, NzSeaFloor.depthForDistance(200.0));
      assertEquals(10.0, NzSeaFloor.depthForDistance(500.0));
      assertEquals(40.0, NzSeaFloor.depthForDistance(2000.0));
      assertEquals(40.0, NzSeaFloor.depthForDistance(1.0e6));
   }

   @Test
   void distanceTransformMatchesBruteForce() {
      Random random = new Random(1);
      int n = 200;
      double[] f = new double[n];
      for (int i = 0; i < n; i++) {
         f[i] = random.nextInt(10) == 0 ? 0.0 : 1.0e12;
      }
      double[] d = new double[n];
      NzSeaFloor.distanceTransform(f, n, d, new int[n], new double[n + 1]);
      for (int q = 0; q < n; q++) {
         double best = Double.POSITIVE_INFINITY;
         for (int p = 0; p < n; p++) {
            best = Math.min(best, (q - p) * (double) (q - p) + f[p]);
         }
         assertEquals(best, d[q], 1.0e-6, "q=" + q);
      }
   }

   @Test
   void deepensAwayFromTheCoastAndAcrossMissingTiles() throws Exception {
      // Tile (0, 0): land in columns 0-99, sea elsewhere. Every other tile is missing, so it counts as sea.
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      int[] water = NzTileTestFiles.filled(NzTerrainSampler.WATER_SEA);
      for (int pz = 0; pz < 512; pz++) {
         for (int px = 0; px < 100; px++) {
            water[pz * 512 + px] = 0;
         }
      }
      NzTileTestFiles.writeTile(set, 0, 0, 0, Map.of(NzTileLayer.WATER, water));
      NzSeaFloor floor = new NzSeaFloor(NzTiles.open(this.temp, CACHE));

      // Column 100 is 1 pixel (3 m) from the land in column 99.
      assertEquals(4.0, floor.depthMetres(0, 100, 256));
      // 300 pixels = 900 m: 18 m deep.
      assertEquals(18.0, floor.depthMetres(0, 399, 256));
      // In the missing tile to the east, 600 pixels from land: 1,800 m, 36 m deep.
      assertEquals(36.0, floor.depthMetres(0, 699, 256));
      // West of the land, in a missing tile: 50 pixels from column 0.
      assertEquals(4.0, floor.depthMetres(0, -50, 256));
      assertEquals(40.0, floor.depthMetres(0, -5000, 256));
   }

   @Test
   void matchesBruteForceOnTheFixture() {
      NzTiles tiles = NzTiles.open(NzTileTestFiles.fixtureDir(), CACHE);
      NzSeaFloor floor = new NzSeaFloor(tiles);
      int originX = -157 * 512;
      int originZ = 175 * 512;
      // The fixture's only land lies in this tile, so a brute-force search over it finds the nearest land pixel.
      int[][] seaPixels = {{64, 128}, {70, 200}, {100, 140}, {0, 0}, {511, 511}};
      for (int[] pixel : seaPixels) {
         double best = Double.POSITIVE_INFINITY;
         for (int pz = 0; pz < 512; pz++) {
            for (int px = 0; px < 512; px++) {
               if (!NzSeaFloor.isSea(tiles.pixel(NzTileLayer.WATER, 0, originX + px, originZ + pz))) {
                  best = Math.min(best, Math.hypot(px - pixel[0], pz - pixel[1]));
               }
            }
         }
         double expected = Math.round(NzSeaFloor.depthForDistance(best * 3.0) * 4.0) / 4.0;
         assertEquals(expected, floor.depthMetres(0, originX + pixel[0], originZ + pixel[1]), "pixel " + pixel[0] + "," + pixel[1]);
      }
   }
}
