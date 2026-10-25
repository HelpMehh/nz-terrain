package com.yucareux.tellus.nz.terrain;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTileTestFiles;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzLedgesTest {
   private static final long CACHE = 64L * 1024 * 1024;
   /** The NZ preset's height values, as in {@code NzWaterTest}. */
   private static final EarthGeneratorSettings SETTINGS = NzWorldSettings.withNz(
      EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
         {"world_scale": 3.0, "terrestrial_height_scale": 1.5, "oceanic_height_scale": 1.5, "height_offset": 63,
          "automatic_height_scaling": false, "experimental_increase_height": false}
         """)).getOrThrow(),
      Optional.of(new NzWorldSettings(Map.of()))
   );
   private static final int NODATA = -32768;
   /** The cliff: rows 100 to 300, 6 m per block (3 blocks per block in game, 72°), from 1,300 m down to 100 m. */
   private static final int CLIFF_TOP_ROW = 100;
   private static final int CLIFF_FOOT_ROW = 300;
   private static final int LAKE_X = 300;
   private static final int CREEK_X = 450;

   @TempDir
   Path temp;

   private NzSeaFloor floor;
   private NzSurfacePalette palette;
   private NzLedges ledges;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   /**
    * Tile (0, 0) at level 0, gravel-or-rock (16) everywhere: flat at 1,300 m north of the cliff and at 100 m south of
    * it, with a small lake on the cliff (x 300–302, rows 200–202) and a creek (class 40) straight down it at x 450.
    */
   @BeforeEach
   void writeTiles() throws Exception {
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      int[] height = new int[512 * 512];
      int[] water = NzTileTestFiles.filled(NzTerrainSampler.WATER_LAND);
      int[] waterLevel = NzTileTestFiles.filled(NODATA);
      int[] streams = NzTileTestFiles.filled(0);
      int[] level = NzTileTestFiles.filled(NODATA);
      for (int pz = 0; pz < 512; pz++) {
         for (int px = 0; px < 512; px++) {
            height[pz * 512 + px] = (int) Math.round(rawMetres(pz) * 8.0);
         }
      }
      for (int pz = 200; pz <= 202; pz++) {
         for (int px = LAKE_X; px <= LAKE_X + 2; px++) {
            water[pz * 512 + px] = NzTerrainSampler.WATER_LAKE;
            waterLevel[pz * 512 + px] = (int) Math.round(rawMetres(202) * 8.0);
         }
      }
      for (int pz = CLIFF_TOP_ROW + 20; pz <= CLIFF_FOOT_ROW + 20; pz++) {
         streams[pz * 512 + CREEK_X] = 40;
         level[pz * 512 + CREEK_X] = (int) Math.round(rawMetres(pz) * 8.0);
      }
      Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
      layers.put(NzTileLayer.HEIGHT, height);
      layers.put(NzTileLayer.WATER, water);
      layers.put(NzTileLayer.WATER_LEVEL, waterLevel);
      layers.put(NzTileLayer.LANDCOVER, NzTileTestFiles.filled(16));
      layers.put(NzTileLayer.STREAMS, streams);
      layers.put(NzTileLayer.STREAM_LEVEL, level);
      NzTileTestFiles.writeTile(set, 0, 0, 0, layers);
      NzTiles tiles = NzTiles.open(this.temp, CACHE);
      this.floor = new NzSeaFloor(tiles);
      this.palette = NzSurfacePalette.bundled();
      this.ledges = new NzLedges(new NzStreams(new NzInlandWater(tiles, SETTINGS), SETTINGS), this.palette, SETTINGS);
   }

   private static double rawMetres(int row) {
      return 100.0 + 6.0 * (CLIFF_FOOT_ROW - Math.clamp(row, CLIFF_TOP_ROW, CLIFF_FOOT_ROW));
   }

   private int rawY(int z) {
      return NzTerrainSampler.blockY(SETTINGS, rawMetres(z), z);
   }

   @Test
   void levelsHaveGapsWithinTheSpacing() {
      int[] levels = NzLedges.levels(5, 12);
      assertEquals(NzLedges.LOWEST_LEVEL, levels[0]);
      assertTrue(levels[levels.length - 1] >= NzLedges.HIGHEST_LEVEL);
      boolean shortest = false;
      boolean longest = false;
      for (int i = 1; i < levels.length; i++) {
         int gap = levels[i] - levels[i - 1];
         assertTrue(gap >= 5 && gap <= 12, "gap " + gap);
         shortest |= gap == 5;
         longest |= gap == 12;
      }
      assertTrue(shortest && longest);
   }

   @Test
   void aColumnMovesAtMostHalfTheSpacingAndOnlyOnSteepGround() {
      int moved = 0;
      for (int x = 0; x < 200; x += 3) {
         for (int y = 100; y < 400; y++) {
            assertEquals(NzLedges.NONE, this.ledges.treadY(x, 0, y, 56.0));
            assertEquals(NzLedges.NONE, this.ledges.treadY(x, 0, y, 40.0));
            int tread = this.ledges.treadY(x, 0, y, 80.0);
            if (tread != NzLedges.NONE) {
               assertTrue(Math.abs(tread - y) <= 12 / 2 + 1, "moved " + (tread - y));
               moved++;
            }
         }
      }
      assertTrue(moved > 1000, "moved " + moved);
   }

   @Test
   void theCliffGetsFlatLedges() {
      int treads = 0;
      int level = 0;
      int changed = 0;
      for (int x = 20; x < 280; x++) {
         for (int z = CLIFF_TOP_ROW + 5; z <= CLIFF_FOOT_ROW - 5; z++) {
            int offset = this.ledges.offset(x, z);
            assertTrue(Math.abs(offset) <= 7, "offset " + offset + " at " + x + ", " + z);
            changed += offset != 0 ? 1 : 0;
            if (this.ledges.isTread(x, z)) {
               treads++;
               // A tread runs along the cliff: the column beside it across the slope is on the same level.
               int y = this.rawY(z) + offset;
               int east = this.rawY(z) + this.ledges.offset(x + 1, z);
               int west = this.rawY(z) + this.ledges.offset(x - 1, z);
               level += y == east || y == west ? 1 : 0;
            }
         }
      }
      int columns = 260 * 191;
      assertTrue(treads > columns / 10 && treads < columns * 2 / 3, "treads " + treads + " of " + columns);
      assertTrue(changed > columns / 10, "changed " + changed);
      assertTrue(level > treads * 9 / 10, "level " + level + " of " + treads);
   }

   @Test
   void flatGroundWaterAndStreamsGetNoLedges() {
      for (int x = 0; x < 512; x++) {
         for (int z : new int[] {50, CLIFF_TOP_ROW - 2, CLIFF_FOOT_ROW + 2, 400}) {
            assertFalse(this.ledges.isTread(x, z), "flat at " + x + ", " + z);
            assertEquals(0, this.ledges.offset(x, z));
         }
      }
      int k = NzLedges.KEEP_AWAY_BLOCKS;
      for (int z = 200 - k; z <= 202 + k; z++) {
         for (int x = LAKE_X - k; x <= LAKE_X + 2 + k; x++) {
            assertFalse(this.ledges.isTread(x, z), "lake at " + x + ", " + z);
         }
      }
      boolean beside = false;
      for (int z = CLIFF_TOP_ROW + 20; z <= CLIFF_FOOT_ROW; z++) {
         for (int x = CREEK_X - k; x <= CREEK_X + k; x++) {
            assertFalse(this.ledges.isTread(x, z), "creek at " + x + ", " + z);
         }
         beside |= this.ledges.isTread(CREEK_X - k - 1, z) || this.ledges.isTread(CREEK_X + k + 1, z);
      }
      assertTrue(beside, "ledges right beyond the keep-away distance");
   }

   @Test
   void theSamplerAndTheSurfaceSeeTheLedges() {
      int checked = 0;
      for (int x = 32; x < 48; x++) {
         for (int z = 192; z < 208; z++) {
            int expected = this.rawY(z) + this.ledges.offset(x, z);
            assertEquals(expected, NzTerrainSampler.sample(this.floor, SETTINGS, x, z, 0).terrainHeight(), "at " + x + ", " + z);
         }
      }
      NzSurface surfaces = NzSurface.columns(this.palette, SETTINGS, this.floor, this.ledges, 32, 192);
      for (int localX = 0; localX < 16; localX++) {
         for (int localZ = 0; localZ < 16; localZ++) {
            String name = surfaces.surfaceAt(localX, localZ, false, false).name();
            assertEquals(this.ledges.isTread(32 + localX, 192 + localZ) ? "rock_ledge" : "rock", name);
            checked += name.equals("rock_ledge") ? 1 : 0;
         }
      }
      assertTrue(checked > 0);
   }

   @Test
   void dilateReachesASquare() {
      boolean[] set = new boolean[49];
      set[3 * 7 + 3] = true;
      boolean[] near = NzLedges.dilate(set, 7, 2);
      for (int z = 0; z < 7; z++) {
         for (int x = 0; x < 7; x++) {
            assertEquals(Math.abs(x - 3) <= 2 && Math.abs(z - 3) <= 2, near[z * 7 + x], x + ", " + z);
         }
      }
   }
}
