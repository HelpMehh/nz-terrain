package com.yucareux.tellus.nz.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NzUndergroundTest {
   private static final int FLOOR = -272;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   private record Fill(int localX, int localZ, int fromY, int toY, BlockState state) {
   }

   @Test
   void fillsFromTheFloorToTheShellBottom() {
      int[] surfaces = new int[256];
      Arrays.fill(surfaces, 1922);
      surfaces[1] = 64;
      surfaces[2] = -100;
      List<Fill> fills = new ArrayList<>();

      NzUnderground.fillBelowShell(surfaces, 256, FLOOR, (x, z, from, to, state) -> fills.add(new Fill(x, z, from, to, state)));

      // Aoraki: the shell ends 256 below the summit.
      assertEquals(new Fill(0, 0, FLOOR + 1, 1922 - 256, Blocks.DEEPSLATE.defaultBlockState()), fills.get(0));
      assertEquals(new Fill(0, 0, FLOOR, FLOOR, Blocks.BEDROCK.defaultBlockState()), fills.get(1));
      // The coast: shell bottom at Y -192.
      assertEquals(new Fill(1, 0, FLOOR + 1, -192, Blocks.DEEPSLATE.defaultBlockState()), fills.get(2));
      // Deep sea floor: the shell already reaches the floor, so only the bedrock.
      assertEquals(new Fill(2, 0, FLOOR, FLOOR, Blocks.BEDROCK.defaultBlockState()), fills.get(4));
      assertEquals(256 * 2 - 1, fills.size());
   }

   @Test
   void fillsABaseColumn() {
      BlockState[] states = new BlockState[2304];
      Arrays.fill(states, Blocks.AIR.defaultBlockState());
      states[400] = Blocks.BEDROCK.defaultBlockState();

      NzUnderground.fillBelowShell(states, FLOOR, FLOOR + 400);

      assertEquals(Blocks.BEDROCK.defaultBlockState(), states[0]);
      assertEquals(Blocks.DEEPSLATE.defaultBlockState(), states[1]);
      assertEquals(Blocks.DEEPSLATE.defaultBlockState(), states[400]);
      assertEquals(Blocks.AIR.defaultBlockState(), states[401]);
   }
}
