package com.yucareux.tellus.nz.mixin.client;

import com.yucareux.tellus.nz.NzTerrain;
import java.util.Optional;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses to create a New Zealand world when there are no NZ tiles (hub {@code interfaces.md}): the Create button shows
 * where the tiles were looked for and goes back to the screen. Checking here, before the world exists, is the only
 * place where refusing doesn't break anything: throwing while the presets decode breaks every world type, and throwing
 * at server start crashes the client.
 */
@Mixin(CreateWorldScreen.class)
abstract class CreateWorldScreenMixin extends Screen {
   @Shadow
   @Final
   private WorldCreationUiState uiState;

   private CreateWorldScreenMixin(Component title) {
      super(title);
   }

   @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
   private void tellus$refuseNzWorldWithoutTiles(CallbackInfo callback) {
      WorldCreationUiState.WorldTypeEntry worldType = this.uiState.getWorldType();
      Holder<WorldPreset> preset = worldType == null ? null : worldType.preset();
      if (preset == null || !preset.is(NzTerrain.NZ_PRESET)) {
         return;
      }
      Optional<String> refusal = NzTerrain.creationRefusal();
      if (refusal.isEmpty()) {
         return;
      }
      callback.cancel();
      Screen createScreen = this;
      this.minecraft.gui.setScreen(new AlertScreen(
         () -> this.minecraft.gui.setScreen(createScreen),
         Component.literal("Can't create a New Zealand world"),
         Component.literal(refusal.get())
      ));
   }
}
