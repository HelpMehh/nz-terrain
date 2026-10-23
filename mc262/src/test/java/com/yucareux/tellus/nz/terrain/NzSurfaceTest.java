package com.yucareux.tellus.nz.terrain;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTileTestFiles;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzSurfaceTest {
   private static final long CACHE = 64L * 1024 * 1024;
   /** The NZ preset's height values (decoding its "nz" key would need a game folder). */
   private static final EarthGeneratorSettings SETTINGS = NzWorldSettings.withNz(
      EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
         {"world_scale": 3.0, "terrestrial_height_scale": 1.5, "oceanic_height_scale": 1.5, "height_offset": 63,
          "automatic_height_scaling": false, "experimental_increase_height": false, "underground_depth": 256}
         """)).getOrThrow(),
      Optional.of(new NzWorldSettings(Map.of()))
   );
   private static final int GRID = 24;
   private static final int STEP = 4;
   private static final int FLAT_Y = 113;

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
    * Tile (0, 0) at level 0, indigenous forest (69) 100 m high, with: a ramp rising 1 m per block eastwards in columns
    * 0–99, gravel and rock (16) in columns 200–299, and a river in rows 400–429.
    */
   @BeforeEach
   void writeTiles() throws Exception {
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      int[] height = NzTileTestFiles.filled(800);
      int[] water = NzTileTestFiles.filled(NzTerrainSampler.WATER_LAND);
      int[] landcover = NzTileTestFiles.filled(69);
      for (int pz = 0; pz < 512; pz++) {
         for (int px = 0; px < 512; px++) {
            int index = pz * 512 + px;
            if (px < 100) {
               height[index] = (100 + px) * 8;
            }
            if (px >= 200 && px < 300) {
               landcover[index] = 16;
            }
            if (pz >= 400 && pz < 430) {
               water[index] = NzTerrainSampler.WATER_RIVER;
            }
         }
      }
      Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
      layers.put(NzTileLayer.HEIGHT, height);
      layers.put(NzTileLayer.WATER, water);
      layers.put(NzTileLayer.WATER_LEVEL, NzTileTestFiles.filled(-32768));
      layers.put(NzTileLayer.LANDCOVER, landcover);
      NzTileTestFiles.writeTile(set, 0, 0, 0, layers);
      this.floor = new NzSeaFloor(NzTiles.open(this.temp, CACHE));
      this.palette = NzSurfacePalette.bundled();
   }

   @Test
   void onlyNzWorldsGetNzSurfaces() {
      assertNull(NzSurface.forChunk(EarthGeneratorSettings.DEFAULT, 0, 0, -64, 320, new int[GRID * GRID], GRID, STEP, (x, z, y, s) -> {
      }));
   }

   @Test
   void slopeIsInGameDegrees() {
      // 1 m per 3 m block is 18.4° real; the world is 1.5× steeper: atan(0.5) = 26.6°.
      NzSurface ramp = this.chunk(32, 32, flatGrid(), new Recorder());
      assertEquals(Math.toDegrees(Math.atan(0.5)), ramp.slopeDegrees(5, 5), 1.0e-9);
      NzSurface flat = this.chunk(144, 32, flatGrid(), new Recorder());
      assertEquals(0.0, flat.slopeDegrees(5, 5), 1.0e-9);
      // Gravel and rock: flat, so gravel flats; forest on the ramp stays forest (below 63°).
      assertEquals("gravel_flats", this.chunk(224, 32, flatGrid(), new Recorder()).surfaceAt(3, 3, false, false).name());
      assertEquals("forest_floor", ramp.surfaceAt(5, 5, false, false).name());
   }

   @Test
   void columnHasTopLayersAndAFaceDownToTheLowestNeighbour() {
      int[] grid = flatGrid();
      // The column east of local (5, 5) stands 8 blocks lower.
      grid[(5 + STEP) * GRID + 6 + STEP] = FLAT_Y - 8;
      Recorder recorder = new Recorder();
      this.chunk(144, 32, grid, recorder).apply(5, 5, FLAT_Y, false, false);

      NzSurfacePalette.Surface forest = this.palette.land(69, 0.0, 100.0);
      Map<Integer, BlockState> column = recorder.column(5, 5);
      assertEquals(FLAT_Y, column.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow());
      assertEquals(FLAT_Y - 7, column.keySet().stream().mapToInt(Integer::intValue).min().orElseThrow());
      assertTrue(contains(forest.top(), column.get(FLAT_Y)), column.get(FLAT_Y).toString());
      for (int y = FLAT_Y - 3; y < FLAT_Y; y++) {
         assertEquals(Blocks.DIRT.defaultBlockState(), column.get(y));
      }
      for (int y = FLAT_Y - 7; y < FLAT_Y - 3; y++) {
         assertTrue(contains(forest.face(), column.get(y)), y + ": " + column.get(y));
      }
   }

   @Test
   void flatColumnWritesOnlyItsLayers() {
      Recorder recorder = new Recorder();
      this.chunk(144, 32, flatGrid(), recorder).apply(5, 5, FLAT_Y, false, false);
      assertEquals(4, recorder.column(5, 5).size());
   }

   @Test
   void underWaterTheWaterLayerPicksTheFloor() {
      NzSurface river = this.chunk(144, 400, flatGrid(), new Recorder());
      assertEquals("riverbed", river.surfaceAt(5, 5, true, false).name());
      assertEquals("sea_floor", river.surfaceAt(5, 5, true, true).name());
      // Water on land the water layer calls dry (it can only be a lake there).
      assertEquals("lake_floor", this.chunk(144, 32, flatGrid(), new Recorder()).surfaceAt(5, 5, true, false).name());
   }

   @Test
   void neverWritesOutsideTheChunk() {
      Recorder recorder = new Recorder();
      // A chunk from 2 below the surface up to the surface: the third dirt block lies below it.
      NzSurface surface = new NzSurface(this.palette, SETTINGS, this.floor, 144, 32, FLAT_Y - 2, FLAT_Y + 1, flatGrid(), GRID, STEP, recorder);
      surface.apply(5, 5, FLAT_Y, false, false);
      assertEquals(3, recorder.column(5, 5).size());
   }

   private NzSurface chunk(int minX, int minZ, int[] grid, Recorder recorder) {
      return new NzSurface(this.palette, SETTINGS, this.floor, minX, minZ, -272, 2032, grid, GRID, STEP, recorder);
   }

   private static int[] flatGrid() {
      int[] grid = new int[GRID * GRID];
      Arrays.fill(grid, FLAT_Y);
      return grid;
   }

   private static boolean contains(NzSurfaceMix mix, BlockState state) {
      return Arrays.asList(mix.blocks()).contains(state);
   }

   private static final class Recorder implements NzSurface.BlockWriter {
      private final Map<Long, BlockState> blocks = new LinkedHashMap<>();

      @Override
      public void set(int localX, int localZ, int y, BlockState state) {
         this.blocks.put(key(localX, localZ, y), state);
      }

      Map<Integer, BlockState> column(int localX, int localZ) {
         Map<Integer, BlockState> column = new TreeMap<>();
         for (int y = -272; y < 2032; y++) {
            BlockState state = this.blocks.get(key(localX, localZ, y));
            if (state != null) {
               column.put(y, state);
            }
         }
         return column;
      }

      private static long key(int localX, int localZ, int y) {
         return ((long) y << 16) | (localZ << 8) | localX;
      }
   }
}
