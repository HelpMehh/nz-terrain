package com.yucareux.tellus.nz.terrain;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzSurfaceMixTest {
   private static BlockState stone;
   private static BlockState andesite;
   private static BlockState tuff;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      stone = Blocks.STONE.defaultBlockState();
      andesite = Blocks.ANDESITE.defaultBlockState();
      tuff = Blocks.TUFF.defaultBlockState();
   }

   private static NzSurfaceMix mix(NzSurfaceMix.Pattern pattern, int size) {
      return new NzSurfaceMix(new BlockState[]{stone, andesite, tuff}, new int[]{60, 25, 15}, pattern, size, 1234L);
   }

   @Test
   void everyPatternKeepsTheShares() {
      for (NzSurfaceMix.Pattern pattern : NzSurfaceMix.Pattern.values()) {
         NzSurfaceMix mix = mix(pattern, 4);
         Map<BlockState, Integer> counts = new HashMap<>();
         int total = 0;
         // Patches and bands need a big area: their unit is a patch or a band, not a block.
         for (int x = -400; x < 400; x += 1) {
            for (int z = -400; z < 400; z += 3) {
               int y = pattern == NzSurfaceMix.Pattern.BANDS ? Math.floorMod(x * 3_163 + z * 1_777, 400_000) : 70;
               counts.merge(mix.pick(x, y, z), 1, Integer::sum);
               total++;
            }
         }
         assertEquals(0.60, counts.get(stone) / (double) total, 0.03, pattern.name());
         assertEquals(0.25, counts.get(andesite) / (double) total, 0.03, pattern.name());
         assertEquals(0.15, counts.get(tuff) / (double) total, 0.03, pattern.name());
      }
   }

   @Test
   void patchesAreMoreUniformThanNoise() {
      double noise = sameAsEastNeighbour(mix(NzSurfaceMix.Pattern.NOISE, 4));
      double patches = sameAsEastNeighbour(mix(NzSurfaceMix.Pattern.PATCHES, 4));
      // Noise: 0.6² + 0.25² + 0.15² ≈ 0.45. Patches 4 wide: most neighbours share the patch.
      assertEquals(0.45, noise, 0.03);
      assertTrue(patches > 0.75, "patches: " + patches);
   }

   @Test
   void bandsAreLevelAcrossAFewBlocks() {
      NzSurfaceMix bands = mix(NzSurfaceMix.Pattern.BANDS, 3);
      int same = 0;
      int total = 0;
      for (int y = 0; y < 300; y++) {
         for (int x = 0; x < 50; x++) {
            total++;
            if (bands.pick(x, y, 0).equals(bands.pick(x + 1, y, 0))) {
               same++;
            }
         }
      }
      assertTrue(same > 0.9 * total, "same as the east neighbour: " + same + " of " + total);
   }

   @Test
   void picksDependOnlyOnThePosition() {
      NzSurfaceMix one = mix(NzSurfaceMix.Pattern.PATCHES, 5);
      NzSurfaceMix two = mix(NzSurfaceMix.Pattern.PATCHES, 5);
      for (int i = -50; i < 50; i++) {
         assertEquals(one.pick(i * 13, 64, i * -7), two.pick(i * 13, 64, i * -7));
      }
   }

   private static double sameAsEastNeighbour(NzSurfaceMix mix) {
      int same = 0;
      int total = 0;
      for (int x = -200; x < 200; x++) {
         for (int z = -200; z < 200; z += 2) {
            total++;
            if (mix.pick(x, 70, z).equals(mix.pick(x + 1, 70, z))) {
               same++;
            }
         }
      }
      return same / (double) total;
   }
}
