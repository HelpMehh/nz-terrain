package com.yucareux.tellus.nz.mixin;

import com.yucareux.tellus.nz.terrain.NzWater;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No new water sources around the steps of NZ lakes and rivers (TERR-14, {@link NzWater#suppressesSourceConversion}).
 * A step's top blocks flow, and at its corners vanilla would make a source from two source neighbours, in air as well
 * as in flowing water; one source makes the next, and the reach below fills up to the level above. Tellus's own
 * {@code WaterSourceConversionMixin} only stops flowing water from turning into a source, not a source placed into air.
 */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
   @Shadow
   public abstract FluidState getFlowing(int amount, boolean falling);

   @Shadow
   protected abstract int getDropOff(LevelReader level);

   @Inject(
      method = "getNewLiquid(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/material/FluidState;",
      at = @At("RETURN"),
      cancellable = true
   )
   private void nz$noSourcesAtSteps(ServerLevel level, BlockPos pos, BlockState state, CallbackInfoReturnable<FluidState> cir) {
      if (!cir.getReturnValue().isSourceOfType(Fluids.WATER)
         || level.getFluidState(pos).isSource()
         || !(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)) {
         return;
      }
      NzWater water = NzWater.forSettings(generator.settings());
      if (water != null && water.suppressesSourceConversion(pos.getX(), pos.getZ())) {
         // What vanilla returns without the source rule: two source neighbours make the highest neighbour 8.
         boolean falling = level.getFluidState(pos.above()).getType().isSame(Fluids.WATER);
         cir.setReturnValue(falling ? this.getFlowing(8, true) : this.getFlowing(8 - this.getDropOff(level), false));
      }
   }
}
