package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.world.data.ocean.OisstOceanClimateSource;

/**
 * Sea temperature for the ocean biomes of NZ worlds, in place of Tellus's NOAA OISST download, which would read the
 * Gulf of Guinea by Mercator. A placeholder until NZ biomes (TERR-12): one value for all NZ waters, about their mean
 * surface temperature, never ice. With Tellus's thresholds that makes every NZ sea a lukewarm ocean.
 */
public final class NzOceanClimate {
   public static final OisstOceanClimateSource.Sample SAMPLE = new OisstOceanClimateSource.Sample(14.0, 0.0, true);

   private NzOceanClimate() {
   }
}
