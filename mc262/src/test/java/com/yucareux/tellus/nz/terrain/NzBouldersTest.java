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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzBouldersTest {
   private static final long CACHE = 64L * 1024 * 1024;
   private static final EarthGeneratorSettings SETTINGS = NzWorldSettings.withNz(
      EarthGeneratorSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
         {"world_scale": 3.0, "terrestrial_height_scale": 1.5, "oceanic_height_scale": 1.5, "height_offset": 63,
          "automatic_height_scaling": false, "experimental_increase_height": false, "underground_depth": 256}
         """)).getOrThrow(),
      Optional.of(new NzWorldSettings(Map.of()))
   );
   /** Every column below a cliff holds a 1-block cobblestone boulder; flat soil a few of 1–4 blocks; stream beds 1-block andesite ones. */
   private static final String PALETTE = """
      {
        "format": 1,
        "face": "stone",
        "land": [{"surface": "soil"}],
        "water": {"sea": "bed", "lake": "bed", "river": "bed", "stream": "bed"},
        "boulders": [
          {"below_cliff": true, "surface": ["soil"], "per_1000": 1000, "sizes": {"1": 1}, "block": "cobblestone"},
          {"surface": ["soil"], "max_slope": 30, "per_1000": 50, "sizes": {"1": 1, "2": 1, "3": 1, "4": 1}, "block": "stone", "top": "moss_block"},
          {"surface": ["bed"], "per_1000": 1000, "sizes": {"1": 1}, "block": "andesite", "top": "moss_block"}
        ],
        "surfaces": {"soil": {"top": "grass_block"}, "bed": {"top": "gravel"}}
      }
      """;
   /** Y of the flat land at 100 m. */
   private static final int FLAT_Y = 113;

   @TempDir
   Path temp;

   private NzSeaFloor floor;
   private NzStreams streams;
   private NzSurfacePalette palette;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   /** Tile (0, 0) at level 0, forest 100 m high, with a 20 m cliff up to the east from column 100 and a creek along row 300 at 98 m. */
   @BeforeEach
   void writeTiles() throws Exception {
      Path set = this.temp.resolve("main");
      NzTileTestFiles.writeManifest(set, "main", "v1", 0);
      int[] height = NzTileTestFiles.filled(800);
      int[] streams = NzTileTestFiles.filled(0);
      int[] level = NzTileTestFiles.filled(-32768);
      for (int pz = 0; pz < 512; pz++) {
         for (int px = 100; px < 512; px++) {
            height[pz * 512 + px] = 120 * 8;
         }
      }
      for (int px = 0; px <= 90; px++) {
         height[300 * 512 + px] = 98 * 8;
         streams[300 * 512 + px] = 40;
         level[300 * 512 + px] = 98 * 8;
      }
      Map<NzTileLayer, int[]> layers = new LinkedHashMap<>();
      layers.put(NzTileLayer.HEIGHT, height);
      layers.put(NzTileLayer.WATER, NzTileTestFiles.filled(NzTerrainSampler.WATER_LAND));
      layers.put(NzTileLayer.WATER_LEVEL, NzTileTestFiles.filled(-32768));
      layers.put(NzTileLayer.LANDCOVER, NzTileTestFiles.filled(69));
      layers.put(NzTileLayer.STREAMS, streams);
      layers.put(NzTileLayer.STREAM_LEVEL, level);
      NzTileTestFiles.writeTile(set, 0, 0, 0, layers);
      NzTiles tiles = NzTiles.open(this.temp, CACHE);
      this.floor = new NzSeaFloor(tiles);
      this.streams = new NzStreams(new NzInlandWater(tiles, SETTINGS), SETTINGS);
      this.palette = NzSurfacePalette.parse(PALETTE);
   }

   @Test
   void onlyNzWorldsGetBoulders() {
      // Returns before it touches the level or the chunk.
      NzBoulders.place(EarthGeneratorSettings.DEFAULT, null, null);
   }

   @Test
   void belowACliffMeansGroundRisingEightBlocksWithinFour() {
      // Columns 88–103: flat to 99, the cliff top (10 blocks higher) from 100.
      NzBoulders boulders = this.chunk(88, 32);
      for (int localX = 0; localX < 16; localX++) {
         boolean below = localX >= 8 && localX <= 11;
         assertEquals(below, boulders.isBelowCliff(localX, 5), "column " + (88 + localX));
         if (below) {
            NzBoulders.Boulder rockfall = boulders.boulderAt(localX, 5);
            assertNotNull(rockfall);
            assertTrue(contains(rockfall.rule().block(), Blocks.COBBLESTONE.defaultBlockState()));
            assertEquals(FLAT_Y + 1, rockfall.top()[0]);
         }
      }
   }

   @Test
   void bouldersAreDomesInsideTheirChunkAndTheSameEveryTime() {
      List<NzBoulders.Boulder> boulders = this.chunk(32, 32).boulders();
      assertFalse(boulders.isEmpty());
      boolean wide = false;
      for (NzBoulders.Boulder boulder : boulders) {
         int size = boulder.size();
         wide |= size > 1;
         assertTrue(boulder.minX() >= 32 && boulder.minX() + size <= 48, "x " + boulder.minX());
         assertTrue(boulder.minZ() >= 32 && boulder.minZ() + size <= 48, "z " + boulder.minZ());
         int covered = 0;
         for (int i = 0; i < size * size; i++) {
            assertEquals(FLAT_Y, boulder.ground()[i]);
            if (boulder.top()[i] != NzBoulders.NONE) {
               covered++;
               int height = boulder.top()[i] - FLAT_Y;
               assertTrue(height >= 1 && height <= Math.round(size * NzBoulders.MAX_HEIGHT_RATIO), "height " + height + " of size " + size);
            }
         }
         // Round: the corners of the bigger ones are cut off.
         assertTrue(covered >= 1);
         if (size >= 3) {
            assertEquals(NzBoulders.NONE, boulder.top()[0]);
            assertEquals(NzBoulders.NONE, boulder.top()[size * size - 1]);
         }
      }
      assertTrue(wide);
      List<NzBoulders.Boulder> again = this.chunk(32, 32).boulders();
      assertEquals(boulders.size(), again.size());
      for (int i = 0; i < boulders.size(); i++) {
         assertEquals(boulders.get(i).minX(), again.get(i).minX());
         assertEquals(boulders.get(i).minZ(), again.get(i).minZ());
         assertTrue(Arrays.equals(boulders.get(i).top(), again.get(i).top()));
      }
   }

   @Test
   void creekBouldersStandOnTheBedAndStayBareUnderWater() {
      // Creek at row 300 (local z 12 of the chunk from z 288): water Y 112 over a bed at 111.
      NzBoulders.Boulder boulder = this.chunk(32, 288).boulderAt(5, 12);
      assertNotNull(boulder);
      assertEquals(FLAT_Y - 2, boulder.ground()[0]);
      assertEquals(FLAT_Y - 1, boulder.water()[0]);
      assertEquals(FLAT_Y - 1, boulder.top()[0]);
      FakeWorld world = new FakeWorld();
      world.put(37, FLAT_Y - 1, 300, Blocks.WATER.defaultBlockState());
      assertTrue(NzBoulders.place(world, boulder));
      assertEquals(Blocks.ANDESITE.defaultBlockState(), world.get(37, FLAT_Y - 1, 300));
   }

   @Test
   void placingFillsAirAndPlantsWithAMossyTop() {
      FakeWorld world = new FakeWorld();
      // A tall plant on the column whose top is 114: its top half would stand on the rock.
      world.put(32, FLAT_Y + 1, 33, Blocks.TALL_GRASS.defaultBlockState());
      world.put(32, FLAT_Y + 2, 33, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
      world.put(33, FLAT_Y + 1, 33, Blocks.SHORT_GRASS.defaultBlockState());
      assertTrue(NzBoulders.place(world, this.threeWide(NzBoulders.NONE)));
      // The middle column is 2 high: stone under a moss top; the edges 1 high, all moss.
      assertEquals(Blocks.STONE.defaultBlockState(), world.get(33, FLAT_Y + 1, 33));
      assertEquals(Blocks.MOSS_BLOCK.defaultBlockState(), world.get(33, FLAT_Y + 2, 33));
      assertEquals(Blocks.MOSS_BLOCK.defaultBlockState(), world.get(32, FLAT_Y + 1, 33));
      assertTrue(world.get(32, FLAT_Y + 2, 33).isAir());
      // Corners stay as they were.
      assertTrue(world.get(32, FLAT_Y + 1, 32).isAir());
   }

   @Test
   void underWaterTheTopIsTheBoulderBlock() {
      FakeWorld world = new FakeWorld();
      assertTrue(NzBoulders.place(world, this.threeWide(FLAT_Y + 1)));
      // Edges end at the water's surface: no moss there; the middle sticks out of the water and has its moss.
      assertEquals(Blocks.STONE.defaultBlockState(), world.get(32, FLAT_Y + 1, 33));
      assertEquals(Blocks.MOSS_BLOCK.defaultBlockState(), world.get(33, FLAT_Y + 2, 33));
   }

   @Test
   void treesOtherBouldersAndHolesKeepBouldersOut() {
      FakeWorld trunk = new FakeWorld();
      trunk.put(33, FLAT_Y + 2, 33, Blocks.OAK_LOG.defaultBlockState());
      assertFalse(NzBoulders.place(trunk, this.threeWide(NzBoulders.NONE)));
      assertTrue(trunk.get(33, FLAT_Y + 1, 33).isAir());
      FakeWorld leaves = new FakeWorld();
      leaves.put(34, FLAT_Y + 1, 33, Blocks.OAK_LEAVES.defaultBlockState());
      assertFalse(NzBoulders.place(leaves, this.threeWide(NzBoulders.NONE)));
      FakeWorld boulder = new FakeWorld();
      boulder.put(32, FLAT_Y + 1, 33, Blocks.STONE.defaultBlockState());
      assertFalse(NzBoulders.place(boulder, this.threeWide(NzBoulders.NONE)));
      assertTrue(boulder.get(33, FLAT_Y + 1, 33).isAir());
      FakeWorld hole = new FakeWorld();
      hole.put(33, FLAT_Y, 34, Blocks.AIR.defaultBlockState());
      assertFalse(NzBoulders.place(hole, this.threeWide(NzBoulders.NONE)));
      assertTrue(hole.get(33, FLAT_Y + 1, 33).isAir());
   }

   @Test
   void replaceableMeansAirWaterAndPlants() {
      assertTrue(NzBoulders.isReplaceable(Blocks.AIR.defaultBlockState()));
      assertTrue(NzBoulders.isReplaceable(Blocks.WATER.defaultBlockState()));
      assertTrue(NzBoulders.isReplaceable(Blocks.FERN.defaultBlockState()));
      assertTrue(NzBoulders.isReplaceable(Blocks.POPPY.defaultBlockState()));
      assertFalse(NzBoulders.isReplaceable(Blocks.STONE.defaultBlockState()));
      assertFalse(NzBoulders.isReplaceable(Blocks.OAK_LOG.defaultBlockState()));
      assertFalse(NzBoulders.isReplaceable(Blocks.GRASS_BLOCK.defaultBlockState()));
   }

   private NzBoulders chunk(int minX, int minZ) {
      return new NzBoulders(this.palette, SETTINGS, this.floor, this.streams, minX, minZ);
   }

   /** A 3 × 3 boulder from block (32, 32) on flat ground: the middle 2 high, the edges 1, the corners left out. */
   private NzBoulders.Boulder threeWide(int water) {
      int none = NzBoulders.NONE;
      int[] ground = new int[9];
      Arrays.fill(ground, FLAT_Y);
      int[] top = {none, FLAT_Y + 1, none, FLAT_Y + 1, FLAT_Y + 2, FLAT_Y + 1, none, FLAT_Y + 1, none};
      int[] waters = new int[9];
      Arrays.fill(waters, water);
      return new NzBoulders.Boulder(32, 32, 3, ground, top, waters, this.palette.boulders("soil", 0.0, 100.0, false));
   }

   private static boolean contains(NzSurfaceMix mix, BlockState state) {
      return Arrays.asList(mix.blocks()).contains(state);
   }

   /** Grass ground at {@link #FLAT_Y}, stone below, air above, unless set. */
   private static final class FakeWorld implements NzBoulders.World {
      private final Map<BlockPos, BlockState> blocks = new HashMap<>();

      void put(int x, int y, int z, BlockState state) {
         this.blocks.put(new BlockPos(x, y, z), state);
      }

      @Override
      public BlockState get(int x, int y, int z) {
         BlockState state = this.blocks.get(new BlockPos(x, y, z));
         if (state != null) {
            return state;
         }
         return y > FLAT_Y ? Blocks.AIR.defaultBlockState() : y == FLAT_Y ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
      }

      @Override
      public void set(int x, int y, int z, BlockState state) {
         this.put(x, y, z, state);
      }
   }
}
