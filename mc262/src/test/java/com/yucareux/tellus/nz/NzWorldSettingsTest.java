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
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzWorldSettingsTest {
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
   void withNzChangesOnlyTheNzValue() {
      EarthGeneratorSettings tellus = decode("{\"world_scale\":12.0,\"enable_roads\":false,\"random_biomes\":true}");
      EarthGeneratorSettings nz = NzWorldSettings.withNz(tellus, Optional.of(new NzWorldSettings()));

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
