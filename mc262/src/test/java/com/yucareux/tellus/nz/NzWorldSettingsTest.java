package com.yucareux.tellus.nz;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzWorldSettingsTest {
   /** Game folder for the tests: decoding {@code "nz": {}} reads the tile manifests in its {@code nz-tiles/}. */
   @TempDir
   static Path gameDir;

   @BeforeAll
   static void useTestGameDir() throws IOException {
      System.setProperty("tellus.gameDir", gameDir.toString());
      System.setProperty("tellus.configDir", gameDir.resolve("config").toString());
      Path set = Files.createDirectories(gameDir.resolve("nz-tiles/nz-main"));
      Files.writeString(set.resolve("tileset.json"), """
         {"format": "nz-tiles", "format_version": 1, "name": "nz-main", "data_version": "2026-10-23",
          "e0": 1600000, "n0": 5470000, "metres_per_block": 3}
         """);
   }

   @AfterAll
   static void restoreGameDir() {
      System.clearProperty("tellus.gameDir");
      System.clearProperty("tellus.configDir");
   }

   @Test
   void tellusSettingsAreNotNz() {
      assertFalse(NzWorldSettings.isNzWorld(EarthGeneratorSettings.DEFAULT));

      EarthGeneratorSettings decoded = decode("{\"world_scale\":12.0}");
      JsonObject encoded = encode(decoded);

      assertFalse(NzWorldSettings.isNzWorld(decoded));
      assertFalse(encoded.has("nz"));
   }

   @Test
   void nzKeyMarksAnNzWorldAndSurvivesARoundTrip() {
      EarthGeneratorSettings decoded = decode("{\"world_scale\":12.0,\"underground_depth\":128,\"huge_red_mushrooms\":true,\"nz\":{}}");
      JsonObject encoded = encode(decoded);
      EarthGeneratorSettings again = decode(encoded.toString());

      assertTrue(NzWorldSettings.isNzWorld(decoded));
      assertEquals(12.0, decoded.worldScale());
      assertEquals(128, decoded.undergroundDepth());
      assertTrue(decoded.hugeRedMushrooms());
      assertTrue(encoded.has("nz"));
      assertEquals(decoded, again);
   }

   @Test
   void aNewWorldRecordsTheTilesFoundNow() {
      EarthGeneratorSettings decoded = decode("{\"nz\":{}}");

      assertEquals(Map.of("nz-main", "2026-10-23"), decoded.nz().orElseThrow().dataVersions());
   }

   @Test
   void savedDataVersionsAreKept() {
      EarthGeneratorSettings decoded = decode("{\"nz\":{\"data_versions\":{\"nz-main\":\"2026-10-22\"}}}");
      JsonObject encoded = encode(decoded);

      assertEquals(Map.of("nz-main", "2026-10-22"), decoded.nz().orElseThrow().dataVersions());
      assertEquals("2026-10-22", encoded.getAsJsonObject("nz").getAsJsonObject("data_versions").get("nz-main").getAsString());
      assertEquals(decoded, decode(encoded.toString()));
   }

   @Test
   void withNzChangesOnlyTheNzValue() {
      EarthGeneratorSettings tellus = decode("{\"world_scale\":12.0,\"enable_roads\":false,\"random_biomes\":true}");
      EarthGeneratorSettings nz = NzWorldSettings.withNz(tellus, Optional.of(new NzWorldSettings(Map.of())));

      assertTrue(NzWorldSettings.isNzWorld(nz));
      assertEquals(tellus, NzWorldSettings.withNz(nz, Optional.empty()));
   }

   @Test
   void nzPresetIsAnNzWorld() throws IOException {
      JsonObject overworld = readJson("/data/tellus/worldgen/world_preset/nz.json")
         .getAsJsonObject("dimensions")
         .getAsJsonObject("minecraft:overworld");
      JsonObject generator = overworld.getAsJsonObject("generator");

      assertTrue(NzWorldSettings.isNzWorld(decode(generator.get("settings").toString())));
      assertTrue(NzWorldSettings.isNzWorld(decode(generator.getAsJsonObject("biome_source").get("settings").toString())));
   }

   private static EarthGeneratorSettings decode(String json) {
      return requireSuccess(EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)));
   }

   private static JsonObject encode(EarthGeneratorSettings settings) {
      return requireSuccess(EarthGeneratorSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings)).getAsJsonObject();
   }

   private static JsonObject readJson(String path) throws IOException {
      try (InputStream stream = NzWorldSettingsTest.class.getResourceAsStream(path)) {
         assertNotNull(stream, path);
         JsonElement json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
         return json.getAsJsonObject();
      }
   }

   private static <T> T requireSuccess(DataResult<T> result) {
      return result.result().orElseThrow(() -> new AssertionError(result.error().map(Object::toString).orElse("unknown error")));
   }
}
