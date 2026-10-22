package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.TerrainShellBedrockProtection;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The underground below Tellus's terrain shell in NZ worlds (option B of {@code tellus-internals.md} §4, chosen in
 * TERR-8). Tellus keeps its caves, ores and deepslate in the shell, {@code underground_depth} blocks below the
 * surface, and leaves air below it. NZ worlds fill that space with deepslate and put the bedrock at the world floor
 * instead, so the ground is solid down to Y −272 everywhere. A later step (deep caverns) carves this zone.
 */
public final class NzUnderground {
   private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
   private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
   private static final int CHUNK_SIDE = 16;

   private NzUnderground() {
   }

   /** Writes {@code state} into one column of a chunk, from {@code fromY} to {@code toY} inclusive. */
   @FunctionalInterface
   public interface ColumnWriter {
      void fill(int localX, int localZ, int fromY, int toY, BlockState state);
   }

   /** True for worlds that are solid below the shell. Their shell needs no bedrock skin against the void. */
   public static boolean fillsBelowShell(EarthGeneratorSettings settings) {
      return NzWorldSettings.isNzWorld(settings);
   }

   /**
    * Fills below the shell of every column of a chunk. Tellus has put one bedrock block at the shell bottom; it becomes
    * deepslate, like everything down to the floor, and the floor becomes bedrock.
    *
    * @param terrainSurfaces the chunk's terrain surface Y per column, index {@code localZ * 16 + localX}
    * @param floorY the world's lowest Y
    */
   public static void fillBelowShell(int[] terrainSurfaces, int undergroundDepth, int floorY, ColumnWriter writer) {
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int surface = terrainSurfaces[localZ * CHUNK_SIDE + localX];
            // The same bedrock Y as the generator's column fill: the shell bottom, but never at or above the surface.
            int shellBedrockY = Math.min(TerrainShellBedrockProtection.supportBottomY(surface, undergroundDepth, floorY), surface - 1);
            if (shellBedrockY > floorY) {
               writer.fill(localX, localZ, floorY + 1, shellBedrockY, DEEPSLATE);
            }
            writer.fill(localX, localZ, floorY, floorY, BEDROCK);
         }
      }
   }

   /** The same for one column from {@code getBaseColumn}: {@code states[0]} is Y {@code floorY}. */
   public static void fillBelowShell(BlockState[] states, int floorY, int shellBottomY) {
      int top = Math.min(shellBottomY - floorY, states.length - 1);
      for (int index = 1; index <= top; index++) {
         states[index] = DEEPSLATE;
      }
      if (states.length > 0) {
         states[0] = BEDROCK;
      }
   }
}
