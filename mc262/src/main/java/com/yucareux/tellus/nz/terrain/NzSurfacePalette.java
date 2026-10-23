package com.yucareux.tellus.nz.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The NZ surface palette (TERR-11): which surface each column gets, from its LCDB class, slope and height on land and
 * from the water layer under water, and the blocks each surface is made of. All of it comes from one data file,
 * {@code nz-terrain/surface-palette.jsonc} in the jar, whose comments explain the format. The rules and thresholds
 * are from {@code surface-palette.md}.
 *
 * <p>For tuning without rebuilding the mod, {@code config/nz-terrain-surface.jsonc} wins over the jar's file while it
 * exists. The palette is read again at every server start ({@link #reloadGlobal}), so a change shows in the next
 * world opened, in chunks generated from then on.
 */
public final class NzSurfacePalette {
   /** The palette in the jar. */
   static final String RESOURCE = "/nz-terrain/surface-palette.jsonc";
   /** The tuning copy in the config folder. */
   public static final String OVERRIDE_FILE = "nz-terrain-surface.jsonc";
   /** The file layout this reader knows ({@code "format"}). */
   static final int FORMAT = 1;
   private static final Logger LOGGER = LoggerFactory.getLogger("tellus/nz");
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();
   private static volatile NzSurfacePalette global;
   private static volatile List<String> globalWarnings = List.of();

   private final List<Rule> land;
   private final Surface sea;
   private final Surface lake;
   private final Surface river;

   private NzSurfacePalette(List<Rule> land, Surface sea, Surface lake, Surface river) {
      this.land = List.copyOf(land);
      this.sea = sea;
      this.lake = lake;
      this.river = river;
   }

   /** One kind of ground: its top block, the layers under it, its step sides, and what lies on top (null: nothing). */
   record Surface(String name, NzSurfaceMix top, List<Layer> under, NzSurfaceMix face, NzSurfaceMix cover) {
   }

   /** A layer under the top block, {@code minDepth} to {@code maxDepth} blocks thick (varying per column). */
   record Layer(NzSurfaceMix block, int minDepth, int maxDepth, long salt) {
      int depth(int x, int z) {
         if (this.minDepth == this.maxDepth) {
            return this.minDepth;
         }
         return this.minDepth + (int) Long.remainderUnsigned(NzSurfaceMix.hash(this.salt, x, 0, z), this.maxDepth - this.minDepth + 1L);
      }
   }

   /**
    * A land rule: matches a column whose LCDB class is in {@code lcdb} (any when null), with {@code minSlope <= slope
    * < maxSlope} (game degrees) and {@code minHeight <= height < maxHeight} (metres).
    */
   record Rule(Set<Integer> lcdb, double minSlope, double maxSlope, double minHeight, double maxHeight, Surface surface) {
      boolean matches(int lcdbClass, double slopeDegrees, double heightMetres) {
         return (this.lcdb == null || this.lcdb.contains(lcdbClass))
            && slopeDegrees >= this.minSlope
            && slopeDegrees < this.maxSlope
            && heightMetres >= this.minHeight
            && heightMetres < this.maxHeight;
      }

      boolean matchesEverything() {
         return this.lcdb == null
            && this.minSlope == Double.NEGATIVE_INFINITY
            && this.maxSlope == Double.POSITIVE_INFINITY
            && this.minHeight == Double.NEGATIVE_INFINITY
            && this.maxHeight == Double.POSITIVE_INFINITY;
      }
   }

   /** The surface of a land column: the first matching rule's (the last rule matches every column). */
   Surface land(int lcdbClass, double slopeDegrees, double heightMetres) {
      for (Rule rule : this.land) {
         if (rule.matches(lcdbClass, slopeDegrees, heightMetres)) {
            return rule.surface();
         }
      }
      return this.land.getLast().surface();
   }

   /** The floor of the sea, a lake or a river. */
   Surface sea() {
      return this.sea;
   }

   Surface lake() {
      return this.lake;
   }

   Surface river() {
      return this.river;
   }

   /** The palette in use: the one read at the last server start, else the jar's. */
   public static NzSurfacePalette global() {
      NzSurfacePalette palette = global;
      if (palette == null) {
         palette = bundled();
         global = palette;
      }
      return palette;
   }

   /** Lines for the log and chat about the palette in use: a tuning copy, or one that couldn't be read. */
   public static List<String> globalWarnings() {
      return globalWarnings;
   }

   /** Reads the palette again: the tuning copy in {@code configDir} if there is one and it reads, else the jar's. */
   public static synchronized void reloadGlobal(Path configDir) {
      Loaded loaded = load(configDir);
      for (String line : loaded.warnings()) {
         LOGGER.warn(line);
      }
      global = loaded.palette();
      globalWarnings = loaded.warnings();
   }

   /** A palette and what to tell the admin about where it came from. */
   record Loaded(NzSurfacePalette palette, List<String> warnings) {
   }

   static Loaded load(Path configDir) {
      Path file = configDir.resolve(OVERRIDE_FILE);
      if (!Files.isRegularFile(file)) {
         return new Loaded(bundled(), List.of());
      }
      try {
         NzSurfacePalette palette = parse(Files.readString(file, StandardCharsets.UTF_8));
         return new Loaded(palette, List.of("NZ Terrain: surface palette from config/" + OVERRIDE_FILE
            + " (a tuning copy; delete it to use the mod's own)."));
      } catch (IOException | IllegalArgumentException e) {
         return new Loaded(bundled(), List.of("NZ Terrain: can't use config/" + OVERRIDE_FILE + ", using the mod's own surface palette: "
            + e.getMessage()));
      }
   }

   /** The palette in the jar. It is checked by the tests, so failing to read it is a bug. */
   static NzSurfacePalette bundled() {
      try (InputStream in = NzSurfacePalette.class.getResourceAsStream(RESOURCE)) {
         if (in == null) {
            throw new IllegalStateException("The jar has no " + RESOURCE);
         }
         return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException | IllegalArgumentException e) {
         throw new IllegalStateException("Can't read the NZ surface palette " + RESOURCE + ": " + e.getMessage(), e);
      }
   }

   /**
    * Reads a palette file. Comments are allowed (Gson's lenient mode); unknown keys, unknown blocks and surfaces, and
    * values out of range throw {@link IllegalArgumentException} naming the place in the file.
    */
   static NzSurfacePalette parse(String text) {
      JsonObject root;
      try {
         JsonElement element = JsonParser.parseString(text);
         if (!element.isJsonObject()) {
            throw new IllegalArgumentException("the file must hold one {…} object");
         }
         root = element.getAsJsonObject();
      } catch (JsonParseException e) {
         throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
      }
      return new Parser().palette(root);
   }

   /** Reads one file; holds the named mixes while the surfaces refer to them. */
   private static final class Parser {
      private final Map<String, NzSurfaceMix> mixes = new HashMap<>();

      NzSurfacePalette palette(JsonObject root) {
         keys(root, "", "format", "mixes", "face", "land", "water", "surfaces");
         int format = integer(required(root, "", "format"), "format");
         if (format != FORMAT) {
            throw error("format", "is " + format + ", this version of the mod reads format " + FORMAT);
         }
         if (root.has("mixes")) {
            JsonObject mixes = object(root.get("mixes"), "mixes");
            for (Map.Entry<String, JsonElement> entry : mixes.entrySet()) {
               String path = "mixes." + entry.getKey();
               this.mixes.put(entry.getKey(), this.inlineMix(object(entry.getValue(), path), path));
            }
         }
         NzSurfaceMix face = this.material(required(root, "", "face"), "face");
         Map<String, Surface> surfaces = new HashMap<>();
         JsonObject surfaceObjects = object(required(root, "", "surfaces"), "surfaces");
         for (Map.Entry<String, JsonElement> entry : surfaceObjects.entrySet()) {
            surfaces.put(entry.getKey(), this.surface(entry.getKey(), object(entry.getValue(), "surfaces." + entry.getKey()), face));
         }
         List<Rule> land = new ArrayList<>();
         JsonArray rules = array(required(root, "", "land"), "land");
         for (int i = 0; i < rules.size(); i++) {
            land.add(rule(object(rules.get(i), "land[" + i + "]"), "land[" + i + "]", surfaces));
         }
         if (land.isEmpty() || !land.getLast().matchesEverything()) {
            throw error("land", "must end with a rule that matches every column (only a \"surface\")");
         }
         JsonObject water = object(required(root, "", "water"), "water");
         keys(water, "water", "sea", "lake", "river");
         return new NzSurfacePalette(
            land,
            surfaceRef(required(water, "water", "sea"), "water.sea", surfaces),
            surfaceRef(required(water, "water", "lake"), "water.lake", surfaces),
            surfaceRef(required(water, "water", "river"), "water.river", surfaces)
         );
      }

      private Surface surface(String name, JsonObject json, NzSurfaceMix defaultFace) {
         String path = "surfaces." + name;
         keys(json, path, "top", "under", "face", "cover");
         NzSurfaceMix top = this.material(required(json, path, "top"), path + ".top");
         List<Layer> under = new ArrayList<>();
         if (json.has("under")) {
            JsonArray layers = array(json.get("under"), path + ".under");
            for (int i = 0; i < layers.size(); i++) {
               String layerPath = path + ".under[" + i + "]";
               JsonObject layer = object(layers.get(i), layerPath);
               keys(layer, layerPath, "block", "depth");
               NzSurfaceMix block = this.material(required(layer, layerPath, "block"), layerPath + ".block");
               int[] depth = depth(required(layer, layerPath, "depth"), layerPath + ".depth");
               under.add(new Layer(block, depth[0], depth[1], salt(layerPath + ".depth")));
            }
         }
         NzSurfaceMix face = json.has("face") ? this.material(json.get("face"), path + ".face") : defaultFace;
         NzSurfaceMix cover = json.has("cover") ? this.material(json.get("cover"), path + ".cover") : null;
         if (cover != null && isAirOnly(cover)) {
            cover = null;
         }
         return new Surface(name, top, List.copyOf(under), face, cover);
      }

      /** A block ID, the name of a mix, or a mix in place. */
      private NzSurfaceMix material(JsonElement json, String path) {
         if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
            String name = json.getAsString();
            NzSurfaceMix named = this.mixes.get(name);
            return named != null ? named : NzSurfaceMix.single(block(name, path));
         }
         if (json.isJsonObject()) {
            return this.inlineMix(json.getAsJsonObject(), path);
         }
         throw error(path, "must be a block ID, the name of a mix, or a mix {\"blocks\": …}");
      }

      private NzSurfaceMix inlineMix(JsonObject json, String path) {
         keys(json, path, "blocks", "pattern", "size");
         JsonObject blocks = object(required(json, path, "blocks"), path + ".blocks");
         if (blocks.isEmpty()) {
            throw error(path + ".blocks", "is empty");
         }
         BlockState[] states = new BlockState[blocks.size()];
         int[] weights = new int[blocks.size()];
         int i = 0;
         for (Map.Entry<String, JsonElement> entry : blocks.entrySet()) {
            String blockPath = path + ".blocks." + entry.getKey();
            states[i] = block(entry.getKey(), blockPath);
            weights[i] = integer(entry.getValue(), blockPath);
            if (weights[i] <= 0) {
               throw error(blockPath, "must be above 0");
            }
            i++;
         }
         NzSurfaceMix.Pattern pattern = NzSurfaceMix.Pattern.NOISE;
         if (json.has("pattern")) {
            String name = string(json.get("pattern"), path + ".pattern");
            pattern = switch (name) {
               case "noise" -> NzSurfaceMix.Pattern.NOISE;
               case "patches" -> NzSurfaceMix.Pattern.PATCHES;
               case "bands" -> NzSurfaceMix.Pattern.BANDS;
               default -> throw error(path + ".pattern", "is \"" + name + "\"; use \"noise\", \"patches\" or \"bands\"");
            };
         }
         int size = pattern == NzSurfaceMix.Pattern.BANDS ? 3 : 4;
         if (json.has("size")) {
            size = integer(json.get("size"), path + ".size");
            if (size < 1 || size > 256) {
               throw error(path + ".size", "must be 1 to 256");
            }
         }
         return new NzSurfaceMix(states, weights, pattern, size, salt(path));
      }

      private static Rule rule(JsonObject json, String path, Map<String, Surface> surfaces) {
         keys(json, path, "lcdb", "min_slope", "max_slope", "min_height", "max_height", "surface");
         Set<Integer> lcdb = null;
         if (json.has("lcdb")) {
            JsonArray classes = array(json.get("lcdb"), path + ".lcdb");
            List<Integer> codes = new ArrayList<>();
            for (int i = 0; i < classes.size(); i++) {
               int code = integer(classes.get(i), path + ".lcdb[" + i + "]");
               if (code < 0 || code > 254) {
                  throw error(path + ".lcdb[" + i + "]", "must be an LCDB class code, 0 to 254");
               }
               codes.add(code);
            }
            lcdb = Set.copyOf(codes);
         }
         double minSlope = number(json, path, "min_slope", Double.NEGATIVE_INFINITY);
         double maxSlope = number(json, path, "max_slope", Double.POSITIVE_INFINITY);
         double minHeight = number(json, path, "min_height", Double.NEGATIVE_INFINITY);
         double maxHeight = number(json, path, "max_height", Double.POSITIVE_INFINITY);
         return new Rule(lcdb, minSlope, maxSlope, minHeight, maxHeight, surfaceRef(required(json, path, "surface"), path + ".surface", surfaces));
      }

      private static Surface surfaceRef(JsonElement json, String path, Map<String, Surface> surfaces) {
         String name = string(json, path);
         Surface surface = surfaces.get(name);
         if (surface == null) {
            throw error(path, "names surface \"" + name + "\", which \"surfaces\" doesn't have");
         }
         return surface;
      }

      private static int[] depth(JsonElement json, String path) {
         int min;
         int max;
         if (json.isJsonArray()) {
            JsonArray range = json.getAsJsonArray();
            if (range.size() != 2) {
               throw error(path, "must be a number or [min, max]");
            }
            min = integer(range.get(0), path + "[0]");
            max = integer(range.get(1), path + "[1]");
         } else {
            min = integer(json, path);
            max = min;
         }
         if (min < 0 || max < min || max > 64) {
            throw error(path, "must be 0 to 64 blocks, with min no more than max");
         }
         return new int[]{min, max};
      }

      private static boolean isAirOnly(NzSurfaceMix mix) {
         for (BlockState state : mix.blocks()) {
            if (!state.isAir()) {
               return false;
            }
         }
         return true;
      }

      private static BlockState block(String name, String path) {
         Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
         Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
         if (block.isEmpty()) {
            throw error(path, "\"" + name + "\" is neither a mix nor a block the game knows");
         }
         return block.get() == Blocks.AIR ? AIR : block.get().defaultBlockState();
      }

      /** A salt per place in the file, so two mixes never pick in step. Stable while the place keeps its name. */
      private static long salt(String path) {
         return NzSurfaceMix.mix(path.hashCode() * 0x9E3779B97F4A7C15L + 0x5DEECE66DL);
      }

      private static void keys(JsonObject json, String path, String... allowed) {
         Set<String> known = Set.of(allowed);
         for (String key : json.keySet()) {
            if (!known.contains(key)) {
               throw error(path.isEmpty() ? key : path + "." + key, "is not a known key (known here: " + String.join(", ", allowed) + ")");
            }
         }
      }

      private static JsonElement required(JsonObject json, String path, String key) {
         JsonElement value = json.get(key);
         if (value == null || value.isJsonNull()) {
            throw error(path.isEmpty() ? key : path + "." + key, "is missing");
         }
         return value;
      }

      private static JsonObject object(JsonElement json, String path) {
         if (!json.isJsonObject()) {
            throw error(path, "must be an object {…}");
         }
         return json.getAsJsonObject();
      }

      private static JsonArray array(JsonElement json, String path) {
         if (!json.isJsonArray()) {
            throw error(path, "must be a list […]");
         }
         return json.getAsJsonArray();
      }

      private static String string(JsonElement json, String path) {
         if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
            throw error(path, "must be a string \"…\"");
         }
         return json.getAsString();
      }

      private static int integer(JsonElement json, String path) {
         if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) {
            throw error(path, "must be a whole number");
         }
         double value = json.getAsDouble();
         if (value != Math.rint(value) || Math.abs(value) > Integer.MAX_VALUE) {
            throw error(path, "must be a whole number");
         }
         return (int) value;
      }

      private static double number(JsonObject json, String path, String key, double fallback) {
         JsonElement value = json.get(key);
         if (value == null) {
            return fallback;
         }
         if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw error(path + "." + key, "must be a number");
         }
         return value.getAsDouble();
      }

      private static IllegalArgumentException error(String path, String message) {
         return new IllegalArgumentException(path + " " + message);
      }
   }
}
