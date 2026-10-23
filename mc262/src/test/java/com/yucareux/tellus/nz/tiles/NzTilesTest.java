package com.yucareux.tellus.nz.tiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzTilesTest {
   private static final long CACHE = 64L * 1024 * 1024;

   @TempDir
   Path temp;

   @Test
   void opensTheFixtureAndSamplesByBlock() throws Exception {
      NzTiles tiles = NzTiles.open(NzTileTestFiles.fixtureDir(), CACHE);

      assertTrue(tiles.problems().isEmpty(), tiles.problems().toString());
      assertEquals(Map.of("nz-main", "2026-10-23"), tiles.dataVersions());
      // Block of pixel (192, 256) in level-0 tile (-157, 175); expected.json says height 29 (3.625 m).
      int x = -157 * 512 + 192;
      int z = 175 * 512 + 256;
      assertEquals(29, tiles.sample(NzTileLayer.HEIGHT, 0, x, z));
      assertEquals(0, tiles.sample(NzTileLayer.WATER, 0, x, z));
      // The same block at level 1: tile (-79, 87), pixel (352, 384).
      NzLayerData coarse = NzTileReader.readLayer(
         NzTileTestFiles.fixtureDir().resolve("nz-main/L1/x-79/t.-79.87.nzt"), 1, -79, 87, NzTileLayer.HEIGHT
      );
      assertEquals(coarse.get(352, 384), tiles.sample(NzTileLayer.HEIGHT, 1, x, z));
   }

   @Test
   void noDataGivesTheFillValue() {
      NzTiles tiles = NzTiles.open(NzTileTestFiles.fixtureDir(), CACHE);

      assertNull(tiles.layer(NzTileLayer.HEIGHT, 0, 0, 0));
      assertEquals(-32768, tiles.sample(NzTileLayer.HEIGHT, 0, 0, 0));
      assertEquals(255, tiles.sample(NzTileLayer.WATER, 0, 0, 0));
      assertEquals(0, tiles.sample(NzTileLayer.STREAMS, 0, 0, 0));
      // The manifest lists canopy at level 0 only, so level 1 isn't even looked up.
      assertNull(tiles.layer(NzTileLayer.CANOPY, 1, -79, 87));
   }

   @Test
   void findsTheFinestLevelThatHasTheTile() {
      NzTiles tiles = NzTiles.open(NzTileTestFiles.fixtureDir(), CACHE);
      int x = -157 * 512 + 192;
      int z = 175 * 512 + 256;
      // Level-1 tile (-79, 87) also covers level-0 tile (-158, 174), which the fixture lacks.
      int coarseOnlyX = -158 * 512 + 10;
      int coarseOnlyZ = 174 * 512 + 10;

      assertEquals(0, tiles.finestLevel(NzTileLayer.HEIGHT, 0, x, z));
      assertEquals(1, tiles.finestLevel(NzTileLayer.HEIGHT, 1, x, z));
      assertEquals(1, tiles.finestLevel(NzTileLayer.HEIGHT, 0, coarseOnlyX, coarseOnlyZ));
      assertEquals(-1, tiles.finestLevel(NzTileLayer.HEIGHT, 0, 0, 0));
      // The manifest lists height only up to level 1.
      assertEquals(-1, tiles.finestLevel(NzTileLayer.HEIGHT, 2, x, z));
      assertEquals(0, tiles.finestLevel(NzTileLayer.CANOPY, 0, x, z));
      assertEquals(-1, tiles.finestLevel(NzTileLayer.CANOPY, 1, x, z));
   }

   @Test
   void finestLevelWithoutLevelsInTheManifest() throws Exception {
      // No "layers" in the manifest: every level may exist, so each is looked up.
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      NzTileTestFiles.writeTile(set, 3, 0, 0, Map.of(NzTileLayer.HEIGHT, NzTileTestFiles.filled(8)));
      NzTiles tiles = NzTiles.open(this.temp, CACHE);

      assertEquals(3, tiles.finestLevel(NzTileLayer.HEIGHT, 0, 100, 100));
      assertEquals(-1, tiles.finestLevel(NzTileLayer.HEIGHT, 0, -100, 100));
      assertEquals(-1, tiles.finestLevel(NzTileLayer.HEIGHT, 4, 100, 100));
   }

   @Test
   void readsPixelsAcrossTiles() {
      NzTiles tiles = NzTiles.open(NzTileTestFiles.fixtureDir(), CACHE);

      assertEquals(29, tiles.pixel(NzTileLayer.HEIGHT, 0, -157 * 512 + 192, 175 * 512 + 256));
      assertEquals(tiles.sample(NzTileLayer.HEIGHT, 1, -157 * 512 + 192, 175 * 512 + 256), tiles.pixel(NzTileLayer.HEIGHT, 1, -79 * 512 + 352, 87 * 512 + 384));
      // The pixel left of the tile's first column lies in tile -158, which doesn't exist.
      assertEquals(-32768, tiles.pixel(NzTileLayer.HEIGHT, 0, -157 * 512 - 1, 175 * 512 + 256));
   }

   @Test
   void aMissingFolderHasNoSets() {
      NzTiles tiles = NzTiles.open(this.temp.resolve("nowhere"), CACHE);

      assertTrue(tiles.isEmpty());
      assertTrue(tiles.problems().isEmpty());
      assertEquals(-32768, tiles.sample(NzTileLayer.HEIGHT, 0, 5, 5));
   }

   @Test
   void mergesSetsByPriorityPixelByPixel() throws Exception {
      // Folder order is the opposite of priority order, so the sort is tested too.
      Path low = this.temp.resolve("a-fill");
      Path high = this.temp.resolve("b-main");
      NzTileTestFiles.writeManifest(low, "fill", "v1", 1);
      NzTileTestFiles.writeManifest(high, "main", "v2", 0);

      int[] halfHeight = NzTileTestFiles.filled(-32768);
      for (int pz = 0; pz < 512; pz++) {
         for (int px = 0; px < 256; px++) {
            halfHeight[pz * 512 + px] = 100;
         }
      }
      Map<NzTileLayer, int[]> highLayers = new LinkedHashMap<>();
      highLayers.put(NzTileLayer.HEIGHT, halfHeight);
      highLayers.put(NzTileLayer.STREAMS, NzTileTestFiles.filled(1));
      NzTileTestFiles.writeTile(high, 0, -1, 0, highLayers);
      Map<NzTileLayer, int[]> lowLayers = new LinkedHashMap<>();
      lowLayers.put(NzTileLayer.HEIGHT, NzTileTestFiles.filled(200));
      lowLayers.put(NzTileLayer.STREAMS, NzTileTestFiles.filled(0));
      lowLayers.put(NzTileLayer.WATER, NzTileTestFiles.filled(2));
      NzTileTestFiles.writeTile(low, 0, -1, 0, lowLayers);

      NzTiles tiles = NzTiles.open(this.temp, CACHE);

      assertEquals(List.of("main", "fill"), tiles.sets().stream().map(NzTileSet::name).toList());
      NzLayerData height = tiles.layer(NzTileLayer.HEIGHT, 0, -1, 0);
      assertEquals(100, height.get(0, 0));
      assertEquals(100, height.get(255, 511));
      assertEquals(200, height.get(256, 0));
      assertEquals(200, tiles.sample(NzTileLayer.HEIGHT, 0, -1, 0));
      assertEquals(100, tiles.sample(NzTileLayer.HEIGHT, 0, -512, 0));
      // Streams has no nodata, so the higher-priority tile wins whole.
      assertEquals(1, tiles.sample(NzTileLayer.STREAMS, 0, -1, 0));
      // Only the fill set has water.
      assertEquals(2, tiles.sample(NzTileLayer.WATER, 0, -1, 0));
   }

   @Test
   void refusesSetsThatDoNotMatchTheFormatOrOrigin() throws Exception {
      NzTileTestFiles.writeManifest(this.temp.resolve("good"), "good", "v1", 0);
      NzTileTestFiles.writeManifest(this.temp.resolve("other-origin"), """
         {"format": "nz-tiles", "format_version": 1, "name": "x", "data_version": "v1", "e0": 1700000, "n0": 5470000, "metres_per_block": 3}
         """);
      NzTileTestFiles.writeManifest(this.temp.resolve("other-scale"), """
         {"format": "nz-tiles", "format_version": 1, "name": "y", "data_version": "v1", "e0": 1600000, "n0": 5470000, "metres_per_block": 1}
         """);
      NzTileTestFiles.writeManifest(this.temp.resolve("newer"), """
         {"format": "nz-tiles", "format_version": 2, "name": "z", "data_version": "v1", "e0": 1600000, "n0": 5470000, "metres_per_block": 3}
         """);
      NzTileTestFiles.writeManifest(this.temp.resolve("not-tiles"), """
         {"format": "something-else", "format_version": 1}
         """);
      NzTileTestFiles.writeManifest(this.temp.resolve("broken"), "{\"format\": ");
      NzTileTestFiles.writeManifest(this.temp.resolve("same-name"), "good", "v2", 0);
      Files.createDirectories(this.temp.resolve("no-manifest"));

      NzTiles tiles = NzTiles.open(this.temp, CACHE);

      assertEquals(Map.of("good", "v1"), tiles.dataVersions());
      List<String> problems = tiles.problems();
      assertEquals(6, problems.size(), problems.toString());
      assertContains(problems, "e0 is 1700000, this NZ Terrain needs 1600000");
      assertContains(problems, "metres_per_block is 1");
      assertContains(problems, "format version 2; this NZ Terrain reads only version 1 (the tiles are newer");
      assertContains(problems, "format is \"something-else\"");
      assertContains(problems, "can't read the manifest");
      assertContains(problems, "another set is also named \"good\"");
   }

   @Test
   void aBrokenTileCountsAsMissing() throws Exception {
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      Path file = NzTileTestFiles.writeTile(set, 0, 0, 0, Map.of(NzTileLayer.HEIGHT, NzTileTestFiles.filled(8)));
      Files.write(file, new byte[] {1, 2, 3});

      NzTiles tiles = NzTiles.open(this.temp, CACHE);

      assertNull(tiles.layer(NzTileLayer.HEIGHT, 0, 0, 0));
      assertEquals(-32768, tiles.sample(NzTileLayer.HEIGHT, 0, 0, 0));
   }

   @Test
   void cachedLayersAreShared() throws Exception {
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      NzTileTestFiles.writeTile(set, 0, 0, 0, Map.of(NzTileLayer.HEIGHT, NzTileTestFiles.filled(8)));
      NzTiles tiles = NzTiles.open(this.temp, CACHE);

      NzLayerData first = tiles.layer(NzTileLayer.HEIGHT, 0, 0, 0);
      assertNotNull(first);
      assertTrue(first == tiles.layer(NzTileLayer.HEIGHT, 0, 0, 0));
   }

   @Test
   void describesDataVersionChanges() {
      assertEquals(List.of(), NzTiles.describeDataVersionChanges(Map.of("nz-main", "a"), Map.of("nz-main", "a")));
      assertEquals(
         List.of(
            "nz-main: world created with data a, the tiles are now b",
            "nz-seafloor: new since the world was created (data c)",
            "old: the world was created with it (data d), but it is missing now"
         ),
         NzTiles.describeDataVersionChanges(Map.of("nz-main", "a", "old", "d"), Map.of("nz-main", "b", "nz-seafloor", "c"))
      );
   }

   private static void assertContains(List<String> problems, String text) {
      assertTrue(problems.stream().anyMatch(problem -> problem.contains(text)), "no problem mentions \"" + text + "\": " + problems);
   }
}
