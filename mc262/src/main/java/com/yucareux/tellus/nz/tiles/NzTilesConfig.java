package com.yucareux.tellus.nz.tiles;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code config/nz-terrain.json}: where the tiles are and how much memory decoded tiles may use.
 *
 * <pre>
 * {
 *   "tiles_dir": "nz-tiles",
 *   "tile_cache_mb": 256
 * }
 * </pre>
 *
 * A relative {@code tiles_dir} is resolved against the game folder (the folder holding {@code mods/} and
 * {@code config/}; on a server, the server folder).
 */
public record NzTilesConfig(Path tilesDir, long cacheBytes) {
   public static final String FILE_NAME = "nz-terrain.json";
   public static final String DEFAULT_TILES_DIR = "nz-tiles";
   public static final int DEFAULT_CACHE_MB = 256;
   private static final Logger LOGGER = LoggerFactory.getLogger("tellus/nz");
   private static final String DEFAULT_FILE = "{\n  \"tiles_dir\": \"" + DEFAULT_TILES_DIR + "\",\n  \"tile_cache_mb\": " + DEFAULT_CACHE_MB + "\n}\n";

   /** Reads the config file, or the defaults where it is missing or broken (a broken file is logged). */
   public static NzTilesConfig load(Path gameDir, Path configDir) {
      String tilesDir = DEFAULT_TILES_DIR;
      long cacheMb = DEFAULT_CACHE_MB;
      Path file = configDir.resolve(FILE_NAME);
      if (Files.isRegularFile(file)) {
         try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement dir = json.get("tiles_dir");
            if (dir != null && !dir.getAsString().isBlank()) {
               tilesDir = dir.getAsString();
            }
            JsonElement cache = json.get("tile_cache_mb");
            if (cache != null) {
               cacheMb = Math.max(16, cache.getAsLong());
            }
         } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
            LOGGER.error("Can't read {}, using the defaults: {}", file, e.toString());
         }
      }
      return new NzTilesConfig(gameDir.resolve(tilesDir).toAbsolutePath().normalize(), cacheMb * 1024L * 1024L);
   }

   /** Writes the default file if there is none, so it is easy to find and edit. */
   public static void writeDefaultIfMissing(Path configDir) {
      Path file = configDir.resolve(FILE_NAME);
      if (Files.exists(file)) {
         return;
      }
      try {
         Files.createDirectories(configDir);
         Files.writeString(file, DEFAULT_FILE, StandardCharsets.UTF_8);
      } catch (IOException e) {
         LOGGER.warn("Can't write the default {}: {}", file, e.toString());
      }
   }
}
