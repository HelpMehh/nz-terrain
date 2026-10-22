package com.yucareux.tellus.nz;

import com.yucareux.tellus.compat.TellusMinecraftCompat;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.nz.tiles.NzTilesConfig;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NZ start-up: the config file, the tile store, and the tile checks when an NZ world starts. */
public final class NzTerrain {
   public static final Logger LOGGER = LoggerFactory.getLogger("tellus/nz");
   /** Lines shown to admins when they join; empty when the tiles are fine or the world isn't NZ. */
   private static volatile List<String> warnings = List.of();

   private NzTerrain() {
   }

   /** Called from the mod initializer. */
   public static void initialize() {
      NzTilesConfig.writeDefaultIfMissing(TellusPlatform.configDir());
      ServerLifecycleEvents.SERVER_STARTING.register(server -> NzTiles.reloadGlobal());
      ServerLifecycleEvents.SERVER_STARTED.register(NzTerrain::checkTiles);
      ServerLifecycleEvents.SERVER_STOPPED.register(server -> warnings = List.of());
      ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> showWarnings(server, handler.getPlayer()));
   }

   private static void checkTiles(MinecraftServer server) {
      Optional<NzWorldSettings> nz = nzSettings(server);
      if (nz.isEmpty()) {
         warnings = List.of();
         return;
      }
      List<String> lines = tileWarnings(nz.get(), NzTiles.global());
      for (String line : lines) {
         LOGGER.warn(line);
      }
      warnings = List.copyOf(lines);
   }

   /** Problems with the tiles of an NZ world, as lines for the log and the chat. */
   static List<String> tileWarnings(NzWorldSettings nz, NzTiles tiles) {
      List<String> lines = new ArrayList<>();
      if (tiles.isEmpty()) {
         lines.add("NZ Terrain: no tile sets found in " + tiles.dir() + " (folders with a tileset.json). Set tiles_dir in config/"
            + NzTilesConfig.FILE_NAME + " if they are elsewhere.");
      }
      for (String problem : tiles.problems()) {
         lines.add("NZ Terrain: tile set refused: " + problem);
      }
      if (!tiles.isEmpty()) {
         List<String> changes = NzTiles.describeDataVersionChanges(nz.dataVersions(), tiles.dataVersions());
         if (!changes.isEmpty()) {
            lines.add("NZ Terrain: the tiles differ from those this world was created with. New chunks may not match older ones where they meet.");
            for (String change : changes) {
               lines.add("  " + change);
            }
         }
      }
      return lines;
   }

   private static Optional<NzWorldSettings> nzSettings(MinecraftServer server) {
      LevelStem overworld = TellusMinecraftCompat.overworldStem(server);
      if (overworld != null && overworld.generator() instanceof EarthChunkGenerator generator) {
         return generator.settings().nz();
      }
      return Optional.empty();
   }

   private static void showWarnings(MinecraftServer server, ServerPlayer player) {
      List<String> lines = warnings;
      if (lines.isEmpty()) {
         return;
      }
      if (!server.isSingleplayer() && !TellusMinecraftCompat.hasGamemasterPermission(player.createCommandSourceStack())) {
         return;
      }
      for (String line : lines) {
         player.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.YELLOW));
      }
   }
}
