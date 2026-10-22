package com.yucareux.tellus.nz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NzProjectionTest {
   /** About 1 mm, in degrees. */
   private static final double DEGREES = 1.0e-8;
   /** 1 mm in blocks. */
   private static final double BLOCKS = 0.001 / 3.0;

   // Reference points from pyproj (EPSG:2193 → EPSG:4326): easting, northing, latitude, longitude.
   private static final double[][] POINTS = {
      {1_371_881.5, 5_188_463.5, -43.421999655340024, 170.1819886695066}, // Sentinel Rock, Franz Josef: block centre −76040, 93845
      {1_600_000.0, 5_470_000.0, -40.921113751618826, 173.0}, // block 0, 0
      {1_572_000.0, 6_190_000.0, -34.431106536003945, 172.69525088852816}, // Cape Reinga
      {1_220_000.0, 4_740_000.0, -47.382739255862695, 167.96508101595802}, // south of Stewart Island
      {2_550_000.0, 5_130_000.0, -43.377755583640216, -175.27693430739615} // east of the Chatham Islands, past 180°
   };

   @Test
   void nztmToLatitudeLongitudeMatchesPyproj() {
      for (double[] point : POINTS) {
         double[] latLon = NzProjection.fromNztm(point[0], point[1]);

         assertEquals(point[2], latLon[0], DEGREES);
         assertEquals(point[3], latLon[1], DEGREES);
      }
   }

   @Test
   void latitudeLongitudeToNztmMatchesPyproj() {
      for (double[] point : POINTS) {
         double[] nztm = NzProjection.toNztm(point[2], point[3]);

         assertEquals(point[0], nztm[0], 0.001);
         assertEquals(point[1], nztm[1], 0.001);
      }
   }

   @Test
   void blocksFollowTheHubGrid() {
      // x = (E − 1,600,000) / 3, z = (5,470,000 − N) / 3; Sentinel Rock's block centre.
      double latitude = -43.421999655340024;
      double longitude = 170.1819886695066;

      assertEquals(-76039.5, NzProjection.blockX(latitude, longitude), BLOCKS);
      assertEquals(93845.5, NzProjection.blockZ(latitude, longitude), BLOCKS);
      assertEquals(latitude, NzProjection.latitude(-76039.5, 93845.5), DEGREES);
      assertEquals(longitude, NzProjection.longitude(-76039.5, 93845.5), DEGREES);
   }
}
