package com.yucareux.tellus.nz.terrain;

/**
 * A stopgap mapping from LCDB v6 classes to the ESA WorldCover classes Tellus's generator, water resolver and DH code
 * understand, so an NZ world gets plausible surfaces until TERR-11/12 replace cover classes with our own vegetation
 * classes and surface rules. Human cover (towns, farms, plantations) simply becomes grassland or forest; real
 * rewilding comes from the vegetation layer (PIPE-8).
 */
final class NzCoverClasses {
   static final int ESA_NO_DATA = 0;
   static final int ESA_TREES = 10;
   static final int ESA_SHRUBLAND = 20;
   static final int ESA_GRASSLAND = 30;
   static final int ESA_BARE = 60;
   static final int ESA_SNOW_ICE = 70;
   static final int ESA_WATER = 80;
   static final int ESA_WETLAND = 90;
   static final int ESA_MANGROVES = 95;

   private NzCoverClasses() {
   }

   /**
    * The ESA class for an LCDB {@code Class_2023} code on land (the water layer decides water). LCDB water classes on
    * land the water layer calls dry become bare ground: gravel beside a river, a drained pond.
    */
   static int esaForLcdb(int lcdb) {
      return switch (lcdb) {
         case 6, 10, 12, 16, 20, 21, 22 -> ESA_BARE;
         case 14 -> ESA_SNOW_ICE;
         case 45, 46, 47 -> ESA_WETLAND;
         case 50, 51, 52, 55, 56, 58, 80, 81 -> ESA_SHRUBLAND;
         case 54, 64, 68, 69, 71 -> ESA_TREES;
         case 70 -> ESA_MANGROVES;
         // Built-up, parkland, transport, crops, pasture, tussock, alpine herbfield, no LCDB polygon, unknown codes.
         default -> ESA_GRASSLAND;
      };
   }
}
