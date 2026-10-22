package com.yucareux.tellus.nz.tiles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzTileReaderTest {
   @TempDir
   Path temp;

   /** Every layer of both fixture tiles decodes to the values the pipeline's reference reader decoded. */
   @Test
   void decodesThePipelineFixture() throws Exception {
      Path fixture = NzTileTestFiles.fixtureDir();
      JsonObject expected = JsonParser.parseString(Files.readString(fixture.resolve("expected.json"), StandardCharsets.UTF_8)).getAsJsonObject();
      int checkedLayers = 0;
      for (JsonElement tileElement : expected.getAsJsonArray("tiles")) {
         JsonObject tile = tileElement.getAsJsonObject();
         Path file = fixture.resolve("nz-main").resolve(tile.get("path").getAsString());
         int level = tile.get("level").getAsInt();
         int tx = tile.get("tx").getAsInt();
         int tz = tile.get("tz").getAsInt();
         for (Map.Entry<String, JsonElement> layerEntry : tile.getAsJsonObject("layers").entrySet()) {
            NzTileLayer layer = NzTileLayer.byFileName(layerEntry.getKey()).orElseThrow();
            JsonObject layerExpected = layerEntry.getValue().getAsJsonObject();
            NzLayerData data = NzTileReader.readLayer(file, level, tx, tz, layer);
            String where = tile.get("path").getAsString() + " " + layer.fileName();
            assertNotNull(data, where);
            assertEquals(layerExpected.get("sha256").getAsString(), sha256(data), where);
            for (JsonElement pixelElement : layerExpected.getAsJsonArray("pixels")) {
               JsonArray pixel = pixelElement.getAsJsonArray();
               assertEquals(pixel.get(2).getAsInt(), data.get(pixel.get(0).getAsInt(), pixel.get(1).getAsInt()), where + " " + pixel);
            }
            checkedLayers++;
         }
      }
      assertEquals(11, checkedLayers);
   }

   @Test
   void aLayerTheFileLacksIsNull() throws Exception {
      Path file = NzTileTestFiles.fixtureDir().resolve("nz-main/L1/x-79/t.-79.87.nzt");
      assertNull(NzTileReader.readLayer(file, 1, -79, 87, NzTileLayer.CANOPY));
   }

   @Test
   void aMissingFileIsNoSuchFile() {
      assertThrows(NoSuchFileException.class, () -> NzTileReader.readLayer(this.temp.resolve("t.0.0.nzt"), 0, 0, 0, NzTileLayer.HEIGHT));
   }

   @Test
   void allTypesRoundTripThroughTheWriter() throws Exception {
      Random random = new Random(7);
      int[] height = new int[NzTileFormat.TILE_AREA];
      int[] water = new int[NzTileFormat.TILE_AREA];
      int[] coast = new int[NzTileFormat.TILE_AREA];
      for (int i = 0; i < NzTileFormat.TILE_AREA; i++) {
         height[i] = random.nextInt(65536) - 32768;
         water[i] = random.nextInt(256);
         coast[i] = random.nextInt(65536);
      }
      Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
      layers.put(NzTileLayer.HEIGHT, height);
      layers.put(NzTileLayer.WATER, water);
      layers.put(NzTileLayer.COAST_DISTANCE, coast);
      Path file = NzTileTestFiles.writeTile(this.temp, 3, -2, 5, layers);

      for (Map.Entry<NzTileLayer, int[]> entry : layers.entrySet()) {
         NzLayerData data = NzTileReader.readLayer(file, 3, -2, 5, entry.getKey());
         int[] decoded = new int[NzTileFormat.TILE_AREA];
         for (int i = 0; i < decoded.length; i++) {
            decoded[i] = data.get(i);
         }
         assertArrayEquals(entry.getValue(), decoded, entry.getKey().fileName());
      }
   }

   @Test
   void refusesATileWhoseHeaderDoesNotMatchItsPath() throws Exception {
      Path file = NzTileTestFiles.writeTile(this.temp, 0, 1, 2, Map.of(NzTileLayer.WATER, NzTileTestFiles.filled(0)));
      NzTileException e = assertThrows(NzTileException.class, () -> NzTileReader.readLayer(file, 0, 1, 3, NzTileLayer.WATER));
      assertTrue(e.getMessage().contains("header says level 0, tile 1, 2"), e.getMessage());
   }

   @Test
   void refusesAnUnknownFormatVersion() throws Exception {
      Path file = NzTileTestFiles.writeTile(this.temp, 0, 0, 0, Map.of(NzTileLayer.WATER, NzTileTestFiles.filled(0)));
      byte[] bytes = Files.readAllBytes(file);
      bytes[6] = 2;
      Files.write(file, bytes);
      NzTileException e = assertThrows(NzTileException.class, () -> NzTileReader.readLayer(file, 0, 0, 0, NzTileLayer.WATER));
      assertTrue(e.getMessage().contains("format version 2"), e.getMessage());
   }

   @Test
   void refusesCorruptData() throws Exception {
      Path file = NzTileTestFiles.writeTile(this.temp, 0, 0, 0, Map.of(NzTileLayer.WATER, NzTileTestFiles.filled(3)));
      byte[] bytes = Files.readAllBytes(file);
      bytes[bytes.length - 1] ^= 0x55;
      Files.write(file, bytes);
      assertThrows(NzTileException.class, () -> NzTileReader.readLayer(file, 0, 0, 0, NzTileLayer.WATER));
   }

   @Test
   void refusesATruncatedFile() throws Exception {
      Path file = NzTileTestFiles.writeTile(this.temp, 0, 0, 0, Map.of(NzTileLayer.WATER, NzTileTestFiles.filled(3)));
      byte[] bytes = Files.readAllBytes(file);
      Files.write(file, Arrays.copyOf(bytes, bytes.length - 4));
      NzTileException e = assertThrows(NzTileException.class, () -> NzTileReader.readLayer(file, 0, 0, 0, NzTileLayer.WATER));
      assertTrue(e.getMessage().contains("too short"), e.getMessage());
   }

   /** Like the pipeline's expected.json: SHA-256 of the 512 × 512 values as little-endian bytes in row order. */
   private static String sha256(NzLayerData data) throws NoSuchAlgorithmException {
      int bytes = data.layer().type().bytes();
      ByteBuffer buffer = ByteBuffer.allocate(NzTileFormat.TILE_AREA * bytes).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < NzTileFormat.TILE_AREA; i++) {
         if (bytes == 1) {
            buffer.put((byte) data.get(i));
         } else {
            buffer.putShort((short) data.get(i));
         }
      }
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.array()));
   }
}
