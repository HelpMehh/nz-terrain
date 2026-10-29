package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTileTestFiles;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzLodSurfaceTest {
   private static final long CACHE = 64L * 1024 * 1024;
   /** Game blocks per metre: the NZ preset's 1.5 terrestrial scale over its vertical world scale of 3. */
   private static final double BLOCKS_PER_METRE = 0.5;
   private static final int Y = 113;

   @TempDir
   Path temp;

   private NzSeaFloor floor;
   private NzSurfacePalette palette;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   /**
    * Tile (0, 0) at levels 0 and 2 (levels listed in the manifest), indigenous forest (69) at 100 m. Level 0: gravel
    * and rock (16) in columns 200–299, a river in rows 400–429. Level 2 (pixels of 4 × 4 blocks): forest rising 20 m
    * per pixel in pixel columns 200–299 (blocks 800–1199), gravel and rock rising 4 m per pixel in pixel columns
    * 300–399 (blocks 1200–1599).
    */
   @BeforeEach
   void writeTiles() throws Exception {
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, """
         {"format": "nz-tiles", "format_version": 1, "name": "main", "data_version": "v1", "priority": 0,
          "crs": "EPSG:2193", "e0": 1600000, "n0": 5470000, "metres_per_block": 3,
          "layers": {"height": [0, 2], "water": [0, 2], "water_level": [0, 2], "landcover": [0, 2]}}
         """);
      int[] height = NzTileTestFiles.filled(800);
      int[] water = NzTileTestFiles.filled(NzTerrainSampler.WATER_LAND);
      int[] landcover = NzTileTestFiles.filled(69);
      int[] coarseHeight = NzTileTestFiles.filled(800);
      int[] coarseLandcover = NzTileTestFiles.filled(69);
      for (int pz = 0; pz < 512; pz++) {
         for (int px = 0; px < 512; px++) {
            int index = pz * 512 + px;
            if (px >= 200 && px < 300) {
               landcover[index] = 16;
               coarseHeight[index] = (100 + (px - 200) * 20) * 8;
            }
            if (px >= 300 && px < 400) {
               coarseLandcover[index] = 16;
               coarseHeight[index] = (100 + (px - 300) * 4) * 8;
            }
            if (pz >= 400 && pz < 430) {
               water[index] = NzTerrainSampler.WATER_RIVER;
            }
         }
      }
      NzTileTestFiles.writeTile(set, 0, 0, 0, layers(height, water, landcover));
      NzTileTestFiles.writeTile(set, 2, 0, 0, layers(coarseHeight, NzTileTestFiles.filled(NzTerrainSampler.WATER_LAND), coarseLandcover));
      this.floor = new NzSeaFloor(NzTiles.open(this.temp, CACHE));
      this.palette = NzSurfacePalette.bundled();
   }

   @Test
   void onlyNzWorldsGetNzLodSurfaces() {
      assertNull(NzLodSurface.forSettings(EarthGeneratorSettings.DEFAULT));
   }

   @Test
   void flatForestIsForestFloorTopAndSides() {
      NzSurfacePalette.Surface forest = this.palette.land(69, 0.0, 100.0);
      for (int x = 0; x < 64; x++) {
         EarthChunkGenerator.LodSurface lod = this.surface(0, 32 + x, 32, false);
         assertTrue(contains(forest.top(), lod.top()), lod.top().toString());
         // The sides of a forest LOD column show the forest floor, not the dirt under it.
         assertTrue(contains(forest.top(), lod.filler()), lod.filler().toString());
      }
   }

   @Test
   void landRulesUseTheLevelsClass() {
      NzSurfacePalette.Surface flats = this.palette.land(16, 0.0, 100.0);
      assertTrue(contains(flats.top(), this.surface(0, 250, 32, false).top()));
   }

   @Test
   void slopeComesFromTheLodsTileLevel() {
      // Level 2, 20 m per 4-block pixel: 5 m per block, 2.5 blocks per block in game (68°): a rock face in the bush.
      NzSurfacePalette.Surface mossyRock = this.palette.land(69, 68.0, 300.0);
      EarthChunkGenerator.LodSurface steep = this.surface(2, 4 * 210 + 2, 32, false);
      assertTrue(contains(mossyRock.top(), steep.top()), steep.top().toString());
      assertTrue(contains(mossyRock.face(), steep.filler()), steep.filler().toString());
      // Level 2, 4 m per pixel: 1 m per block, 26.6° in game: scree. Without the 4-block pixel size it would be 63°: rock.
      NzSurfacePalette.Surface scree = this.palette.land(16, 26.6, 150.0);
      assertTrue(contains(scree.top(), this.surface(2, 4 * 320 + 2, 32, false).top()));
   }

   @Test
   void coarserThanTheTopLevelReadsTheTopLevel() {
      NzSurfacePalette.Surface scree = this.palette.land(16, 26.6, 150.0);
      assertTrue(contains(scree.top(), this.surface(7, 4 * 320 + 2, 32, false).top()));
   }

   @Test
   void underWaterTheWaterLayerPicksTheFloor() {
      assertTrue(contains(this.palette.river().top(), this.surface(0, 32, 410, true).top()));
      // Water on a column the water layer calls land: a stream (level 0 only).
      assertTrue(contains(this.palette.stream().top(), this.surface(0, 32, 32, true).top()));
      // Outside the data: the sea floor.
      assertTrue(contains(this.palette.sea().top(), this.surface(0, -5000, -5000, true).top()));
   }

   @Test
   void snowCoverShowsOnTheTop() throws Exception {
      // Make every pixel steep permanent snow: snowy rock, whose cover is snow on about 60 % of the columns.
      Path set = this.temp.resolve("main");
      int[] ramp = new int[512 * 512];
      for (int index = 0; index < ramp.length; index++) {
         ramp[index] = (2000 + (index % 512) * 4) * 8;
      }
      NzTileTestFiles.writeTile(set, 0, 0, 0, layers(ramp, NzTileTestFiles.filled(NzTerrainSampler.WATER_LAND), NzTileTestFiles.filled(14)));
      this.floor = new NzSeaFloor(NzTiles.open(this.temp, CACHE));
      NzSurfacePalette.Surface snowyRock = this.palette.land(14, 63.4, 2100.0);
      boolean snow = false;
      boolean rock = false;
      for (int x = 0; x < 200; x++) {
         BlockState top = this.surface(0, 32 + x, 32 + 3 * x % 50, false).top();
         snow |= top.is(Blocks.SNOW);
         rock |= contains(snowyRock.top(), top);
         assertTrue(top.is(Blocks.SNOW) || contains(snowyRock.top(), top), top.toString());
      }
      assertTrue(snow && rock);
      assertFalse(this.surface(0, 32, 32, false).filler().is(Blocks.SNOW));
   }

   private EarthChunkGenerator.LodSurface surface(int level, int x, int z, boolean underwater) {
      return NzLodSurface.surface(this.palette, this.floor, BLOCKS_PER_METRE, level, x, z, Y, underwater);
   }

   private static Map<NzTileLayer, int[]> layers(int[] height, int[] water, int[] landcover) {
      Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
      layers.put(NzTileLayer.HEIGHT, height);
      layers.put(NzTileLayer.WATER, water);
      layers.put(NzTileLayer.WATER_LEVEL, NzTileTestFiles.filled(-32768));
      layers.put(NzTileLayer.LANDCOVER, landcover);
      return layers;
   }

   private static boolean contains(NzSurfaceMix mix, BlockState state) {
      return Arrays.asList(mix.blocks()).contains(state);
   }
}
