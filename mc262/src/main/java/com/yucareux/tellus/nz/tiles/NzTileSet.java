package com.yucareux.tellus.nz.tiles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.Map;

/**
 * One tile set: a folder with a {@code tileset.json} manifest and its tiles. Opening it checks the manifest; a set
 * that would put NZ in the wrong place, or that this reader can't understand, is refused.
 */
public final class NzTileSet {
   private final Path dir;
   private final String name;
   private final String dataVersion;
   private final int priority;
   /** Which levels each layer has, from the manifest's {@code layers}; null when the manifest doesn't say. */
   private final Map<NzTileLayer, BitSet> levels;

   private NzTileSet(Path dir, String name, String dataVersion, int priority, Map<NzTileLayer, BitSet> levels) {
      this.dir = dir;
      this.name = name;
      this.dataVersion = dataVersion;
      this.priority = priority;
      this.levels = levels;
   }

   /** Reads and checks {@code <dir>/tileset.json}. */
   public static NzTileSet open(Path dir) throws NzTileException {
      Path manifestPath = dir.resolve(NzTileFormat.MANIFEST_FILE);
      JsonObject manifest;
      try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
         manifest = JsonParser.parseReader(reader).getAsJsonObject();
      } catch (IOException | JsonParseException | IllegalStateException e) {
         throw new NzTileException(manifestPath + ": can't read the manifest (" + e.getMessage() + ")", e);
      }

      String format = string(manifest, "format", manifestPath);
      if (!NzTileFormat.FORMAT.equals(format)) {
         throw new NzTileException(manifestPath + ": format is \"" + format + "\", not \"" + NzTileFormat.FORMAT + "\"");
      }
      long version = number(manifest, "format_version", manifestPath);
      if (version != NzTileFormat.FORMAT_VERSION) {
         throw new NzTileException(
            manifestPath + ": format version " + version + "; this NZ Terrain reads only version " + NzTileFormat.FORMAT_VERSION
               + (version > NzTileFormat.FORMAT_VERSION ? " (the tiles are newer: update NZ Terrain)" : "")
         );
      }
      requireNumber(manifest, "e0", NzTileFormat.E0, manifestPath);
      requireNumber(manifest, "n0", NzTileFormat.N0, manifestPath);
      requireNumber(manifest, "metres_per_block", NzTileFormat.METRES_PER_BLOCK, manifestPath);

      String name = string(manifest, "name", manifestPath);
      if (name.isBlank()) {
         throw new NzTileException(manifestPath + ": name is empty");
      }
      String dataVersion = string(manifest, "data_version", manifestPath);
      int priority = manifest.has("priority") ? Math.toIntExact(number(manifest, "priority", manifestPath)) : 0;
      return new NzTileSet(dir, name, dataVersion, priority, levels(manifest, manifestPath));
   }

   public Path dir() {
      return this.dir;
   }

   public String name() {
      return this.name;
   }

   public String dataVersion() {
      return this.dataVersion;
   }

   /** Lower comes first: where two sets both have data, the lower priority wins. */
   public int priority() {
      return this.priority;
   }

   /** False when the manifest says this set has no such layer at that level, so the file lookup can be skipped. */
   public boolean mayHave(NzTileLayer layer, int level) {
      if (this.levels == null) {
         return true;
      }
      BitSet layerLevels = this.levels.get(layer);
      return layerLevels != null && layerLevels.get(level);
   }

   /** Decodes one layer of one tile; null where this set has no data (no file, or no such layer in it). */
   public NzLayerData read(NzTileLayer layer, int level, int tx, int tz) throws IOException, NzTileException {
      NzTileFormat.checkLevel(level);
      if (!this.mayHave(layer, level)) {
         return null;
      }
      try {
         return NzTileReader.readLayer(NzTileFormat.tilePath(this.dir, level, tx, tz), level, tx, tz, layer);
      } catch (NoSuchFileException e) {
         return null;
      }
   }

   @Override
   public String toString() {
      return this.name + " (data " + this.dataVersion + ", priority " + this.priority + ", " + this.dir + ")";
   }

   private static Map<NzTileLayer, BitSet> levels(JsonObject manifest, Path manifestPath) throws NzTileException {
      if (!manifest.has("layers")) {
         return null;
      }
      try {
         Map<NzTileLayer, BitSet> levels = new EnumMap<>(NzTileLayer.class);
         for (Map.Entry<String, JsonElement> entry : manifest.getAsJsonObject("layers").entrySet()) {
            NzTileLayer layer = NzTileLayer.byFileName(entry.getKey()).orElse(null);
            if (layer == null) {
               continue;
            }
            BitSet layerLevels = new BitSet();
            JsonArray array = entry.getValue().getAsJsonArray();
            for (JsonElement level : array) {
               int value = level.getAsInt();
               if (value >= 0 && value <= NzTileFormat.MAX_LEVEL) {
                  layerLevels.set(value);
               }
            }
            levels.put(layer, layerLevels);
         }
         return levels;
      } catch (ClassCastException | IllegalStateException | NumberFormatException e) {
         throw new NzTileException(manifestPath + ": layers must map layer names to lists of levels", e);
      }
   }

   private static String string(JsonObject manifest, String key, Path manifestPath) throws NzTileException {
      JsonElement value = manifest.get(key);
      if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
         throw new NzTileException(manifestPath + ": " + key + " is missing or not a string");
      }
      return value.getAsString();
   }

   private static long number(JsonObject manifest, String key, Path manifestPath) throws NzTileException {
      JsonElement value = manifest.get(key);
      if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
         throw new NzTileException(manifestPath + ": " + key + " is missing or not a number");
      }
      double number = value.getAsDouble();
      if (number != Math.rint(number)) {
         throw new NzTileException(manifestPath + ": " + key + " is " + number + ", not a whole number");
      }
      return (long) number;
   }

   /** Tiles from another origin or scale would put NZ in the wrong place without any error, so refuse them. */
   private static void requireNumber(JsonObject manifest, String key, long expected, Path manifestPath) throws NzTileException {
      long value = number(manifest, key, manifestPath);
      if (value != expected) {
         throw new NzTileException(manifestPath + ": " + key + " is " + value + ", this NZ Terrain needs " + expected);
      }
   }
}
