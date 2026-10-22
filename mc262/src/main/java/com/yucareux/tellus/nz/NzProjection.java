package com.yucareux.tellus.nz;

import com.yucareux.tellus.nz.tiles.NzTileFormat;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;

/**
 * Block x/z ↔ latitude/longitude for NZ worlds. NZ blocks are NZTM2000 metres, {@code x = (E − E0) / 3},
 * {@code z = (N0 − N) / 3} (hub {@code interfaces.md}); NZTM is a transverse Mercator projection on the GRS80
 * ellipsoid, converted here with Krüger's series to third order (Karney 2011), which is accurate to about a millimetre
 * across NZ and the Chatham Islands. Tellus's own conversion is Web Mercator around 0°N 0°E and reads West Africa in
 * an NZ world.
 *
 * <p>Only what a person types or sees uses this: the spawn, {@code /tellus map} and its teleport, and the real-time
 * weather lookups. Terrain never needs latitude/longitude, because the tiles are in block coordinates already.
 *
 * <p>Block coordinates here are continuous: the centre of block column (x, z) is (x + 0.5, z + 0.5).
 */
public final class NzProjection {
   // NZTM2000 (EPSG:2193): GRS80, origin 0°N 173°E, scale 0.9996, false easting 1,600,000 m, false northing 10,000,000 m.
   private static final double SEMI_MAJOR_AXIS = 6_378_137.0;
   private static final double FLATTENING = 1.0 / 298.257222101;
   private static final double CENTRAL_MERIDIAN = 173.0;
   private static final double SCALE = 0.9996;
   private static final double FALSE_EASTING = 1_600_000.0;
   private static final double FALSE_NORTHING = 10_000_000.0;

   private static final double N = FLATTENING / (2.0 - FLATTENING);
   private static final double ECCENTRICITY = 2.0 * Math.sqrt(N) / (1.0 + N);
   /** Scale times the rectifying radius: metres of northing per radian of rectifying latitude. */
   private static final double K0A = SCALE * SEMI_MAJOR_AXIS / (1.0 + N) * (1.0 + N * N / 4.0 + Math.pow(N, 4) / 64.0);
   private static final double[] ALPHA = {
      N / 2.0 - 2.0 * N * N / 3.0 + 5.0 * N * N * N / 16.0,
      13.0 * N * N / 48.0 - 3.0 * N * N * N / 5.0,
      61.0 * N * N * N / 240.0
   };
   private static final double[] BETA = {
      N / 2.0 - 2.0 * N * N / 3.0 + 37.0 * N * N * N / 96.0,
      N * N / 48.0 + N * N * N / 15.0,
      17.0 * N * N * N / 480.0
   };
   private static final double[] DELTA = {
      2.0 * N - 2.0 * N * N / 3.0 - 2.0 * N * N * N,
      7.0 * N * N / 3.0 - 8.0 * N * N * N / 5.0,
      56.0 * N * N * N / 15.0
   };

   private NzProjection() {
   }

   /** Block x (continuous) of a latitude/longitude in degrees. */
   public static double blockX(double latitude, double longitude) {
      return (toNztm(latitude, longitude)[0] - NzTileFormat.E0) / NzTileFormat.METRES_PER_BLOCK;
   }

   /** Block z (continuous) of a latitude/longitude in degrees. */
   public static double blockZ(double latitude, double longitude) {
      return (NzTileFormat.N0 - toNztm(latitude, longitude)[1]) / NzTileFormat.METRES_PER_BLOCK;
   }

   /** Latitude in degrees of block position (x, z). */
   public static double latitude(double blockX, double blockZ) {
      return fromNztm(easting(blockX), northing(blockZ))[0];
   }

   /** Longitude in degrees, −180 to 180, of block position (x, z). */
   public static double longitude(double blockX, double blockZ) {
      return fromNztm(easting(blockX), northing(blockZ))[1];
   }

   /**
    * Latitude of block position (x, z) in the generator's world: NZTM in NZ worlds, else Tellus's own
    * {@code latitudeFromBlock}. Tellus's takes z alone, which can't work for a transverse projection, so hooks call
    * this with both coordinates.
    */
   public static double latitude(EarthChunkGenerator generator, double blockX, double blockZ) {
      return NzWorldSettings.isNzWorld(generator.settings()) ? latitude(blockX, blockZ) : generator.latitudeFromBlock(blockZ);
   }

   /** Longitude of block position (x, z) in the generator's world; see {@link #latitude(EarthChunkGenerator, double, double)}. */
   public static double longitude(EarthChunkGenerator generator, double blockX, double blockZ) {
      return NzWorldSettings.isNzWorld(generator.settings()) ? longitude(blockX, blockZ) : generator.longitudeFromBlock(blockX);
   }

   private static double easting(double blockX) {
      return NzTileFormat.E0 + blockX * NzTileFormat.METRES_PER_BLOCK;
   }

   private static double northing(double blockZ) {
      return NzTileFormat.N0 - blockZ * NzTileFormat.METRES_PER_BLOCK;
   }

   /** {easting, northing} in metres. */
   static double[] toNztm(double latitude, double longitude) {
      double phi = Math.toRadians(latitude);
      double lambda = Math.toRadians(normalizeLongitude(longitude - CENTRAL_MERIDIAN));
      double sinPhi = Math.sin(phi);
      double t = Math.sinh(atanh(sinPhi) - ECCENTRICITY * atanh(ECCENTRICITY * sinPhi));
      double xiPrime = Math.atan2(t, Math.cos(lambda));
      double etaPrime = atanh(Math.sin(lambda) / Math.sqrt(1.0 + t * t));
      double xi = xiPrime;
      double eta = etaPrime;
      for (int j = 1; j <= 3; j++) {
         xi += ALPHA[j - 1] * Math.sin(2 * j * xiPrime) * Math.cosh(2 * j * etaPrime);
         eta += ALPHA[j - 1] * Math.cos(2 * j * xiPrime) * Math.sinh(2 * j * etaPrime);
      }
      return new double[]{FALSE_EASTING + K0A * eta, FALSE_NORTHING + K0A * xi};
   }

   /** {latitude, longitude} in degrees. */
   static double[] fromNztm(double easting, double northing) {
      double xi = (northing - FALSE_NORTHING) / K0A;
      double eta = (easting - FALSE_EASTING) / K0A;
      double xiPrime = xi;
      double etaPrime = eta;
      for (int j = 1; j <= 3; j++) {
         xiPrime -= BETA[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
         etaPrime -= BETA[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
      }
      double chi = Math.asin(Math.sin(xiPrime) / Math.cosh(etaPrime));
      double phi = chi;
      for (int j = 1; j <= 3; j++) {
         phi += DELTA[j - 1] * Math.sin(2 * j * chi);
      }
      double lambda = Math.atan2(Math.sinh(etaPrime), Math.cos(xiPrime));
      return new double[]{Math.toDegrees(phi), normalizeLongitude(CENTRAL_MERIDIAN + Math.toDegrees(lambda))};
   }

   private static double atanh(double value) {
      return 0.5 * Math.log((1.0 + value) / (1.0 - value));
   }

   /** The same longitude in −180 to 180: the Chatham Islands lie east of 180°. */
   private static double normalizeLongitude(double degrees) {
      double wrapped = (degrees + 180.0) % 360.0;
      return (wrapped < 0.0 ? wrapped + 360.0 : wrapped) - 180.0;
   }
}
