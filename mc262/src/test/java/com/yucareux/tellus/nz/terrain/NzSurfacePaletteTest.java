package com.yucareux.tellus.nz.terrain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzSurfacePaletteTest {
   private static final String MINIMAL = """
      {
        // comment
        "format": 1,
        "face": "stone",
        "land": [{"lcdb": [16], "min_slope": 30, "surface": "rock"}, {"surface": "soil"}],
        "water": {"sea": "rock", "lake": "rock", "river": "rock"},
        "surfaces": {"rock": {"top": "stone"}, "soil": {"top": "grass_block", "under": [{"block": "dirt", "depth": [1, 3]}]}}
      }
      """;

   @TempDir
   Path temp;

   @BeforeAll
   static void bootstrap() {
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @Test
   void bundledPaletteFollowsTheRulesOfSurfacePaletteMd() {
      NzSurfacePalette palette = NzSurfacePalette.bundled();
      // Gravel or rock (16) by slope: flats below 22° game, scree to 46°, rock above.
      assertEquals("gravel_flats", palette.land(16, 10.0, 100.0).name());
      assertEquals("scree", palette.land(16, 22.0, 100.0).name());
      assertEquals("scree", palette.land(16, 45.9, 1500.0).name());
      assertEquals("rock", palette.land(16, 46.0, 1500.0).name());
      // Snow and ice (14): rock above 56°, glacier ice below 1,800 m, snow above.
      assertEquals("snowy_rock", palette.land(14, 60.0, 2500.0).name());
      assertEquals("glacier_ice", palette.land(14, 20.0, 400.0).name());
      assertEquals("snow", palette.land(14, 20.0, 1800.0).name());
      // Vegetation steeper than 2-block steps: mossy rock below the treeline, bare above.
      assertEquals("mossy_rock", palette.land(69, 70.0, 500.0).name());
      assertEquals("rock", palette.land(43, 70.0, 1300.0).name());
      assertEquals("tall_tussock", palette.land(43, 62.0, 1300.0).name());
      assertEquals("forest_floor", palette.land(69, 30.0, 300.0).name());
      assertEquals("subalpine_shrubland", palette.land(55, 30.0, 1100.0).name());
      assertEquals("alpine_herbfield", palette.land(15, 30.0, 1600.0).name());
      // Not vegetated: no rock face, whatever the slope.
      assertEquals("beach", palette.land(10, 70.0, 3.0).name());
      assertEquals("landslide", palette.land(12, 70.0, 500.0).name());
      // Rewilded human cover and "no polygon" get the forest floor.
      assertEquals("forest_floor", palette.land(40, 5.0, 50.0).name());
      assertEquals("forest_floor", palette.land(0, 5.0, 50.0).name());
      assertEquals("sea_floor", palette.sea().name());
      assertEquals("lake_floor", palette.lake().name());
      assertEquals("riverbed", palette.river().name());
      assertEquals("stream_bed", palette.stream().name());
   }

   @Test
   void bundledSurfacesHaveTheirBlocks() {
      NzSurfacePalette palette = NzSurfacePalette.bundled();
      NzSurfacePalette.Surface forest = palette.land(69, 0.0, 100.0);
      assertEquals(
         Set.of(Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MOSS_BLOCK, Blocks.ROOTED_DIRT, Blocks.GRASS_BLOCK),
         blocks(forest.top())
      );
      assertEquals(1, forest.under().size());
      assertEquals(3, forest.under().getFirst().depth(5, 7));
      assertNull(forest.cover());
      // The top-level face (schist) for surfaces without their own.
      assertEquals(Set.of(Blocks.STONE, Blocks.ANDESITE, Blocks.TUFF), blocks(forest.face()));
      NzSurfacePalette.Surface snowyRock = palette.land(14, 60.0, 2500.0);
      assertEquals(Set.of(Blocks.SNOW, Blocks.AIR), blocks(snowyRock.cover()));
   }

   @Test
   void depthRangesVaryPerColumnWithinTheRange() {
      NzSurfacePalette.Layer layer = NzSurfacePalette.parse(MINIMAL).land(0, 0.0, 0.0).under().getFirst();
      int[] counts = new int[5];
      for (int x = 0; x < 300; x++) {
         counts[layer.depth(x, x * 7)]++;
      }
      assertEquals(0, counts[0]);
      assertEquals(0, counts[4]);
      assertTrue(counts[1] > 50 && counts[2] > 50 && counts[3] > 50, Arrays.toString(counts));
   }

   @Test
   void limitsIncludeMinAndExcludeMax() {
      NzSurfacePalette palette = NzSurfacePalette.parse(MINIMAL);
      assertEquals("soil", palette.land(16, 29.99, 0.0).name());
      assertEquals("rock", palette.land(16, 30.0, 0.0).name());
      assertEquals("soil", palette.land(15, 80.0, 0.0).name());
   }

   @Test
   void streamsTakeTheRiverFloorWhenTheFileNamesNone() {
      NzSurfacePalette palette = NzSurfacePalette.parse(MINIMAL);
      assertSame(palette.river(), palette.stream());
      NzSurfacePalette named = NzSurfacePalette.parse(MINIMAL.replace("\"river\": \"rock\"}", "\"river\": \"rock\", \"stream\": \"soil\"}"));
      assertEquals("soil", named.stream().name());
   }

   @Test
   void mistakesNameTheirPlaceInTheFile() {
      assertError("land[0].max_slop is not a known key", MINIMAL.replace("\"min_slope\"", "\"max_slop\""));
      assertError("surfaces.rock.top \"stoen\" is neither a mix nor a block", MINIMAL.replace("{\"top\": \"stone\"}", "{\"top\": \"stoen\"}"));
      assertError("land[1].surface names surface \"soli\"", MINIMAL.replace("{\"surface\": \"soil\"}", "{\"surface\": \"soli\"}"));
      assertError("land must end with a rule that matches every column", MINIMAL.replace("{\"surface\": \"soil\"}", "{\"lcdb\": [1], \"surface\": \"soil\"}"));
      assertError("format is 2", MINIMAL.replace("\"format\": 1", "\"format\": 2"));
      assertError("surfaces.soil.under[0].depth must be 0 to 64", MINIMAL.replace("[1, 3]", "[3, 1]"));
      assertError("not valid JSON", MINIMAL.replace("\"face\": \"stone\",", "\"face\": \"stone\""));
   }

   @Test
   void tuningCopyInConfigWins() throws Exception {
      NzSurfacePalette.Loaded none = NzSurfacePalette.load(this.temp);
      assertEquals("forest_floor", none.palette().land(69, 0.0, 0.0).name());
      assertEquals(0, none.warnings().size());

      Files.writeString(this.temp.resolve(NzSurfacePalette.OVERRIDE_FILE), MINIMAL);
      NzSurfacePalette.Loaded copy = NzSurfacePalette.load(this.temp);
      assertEquals("soil", copy.palette().land(69, 0.0, 0.0).name());
      assertTrue(copy.warnings().getFirst().contains("tuning copy"), copy.warnings().toString());

      Files.writeString(this.temp.resolve(NzSurfacePalette.OVERRIDE_FILE), MINIMAL.replace("\"stone\"}", "\"stoen\"}"));
      NzSurfacePalette.Loaded broken = NzSurfacePalette.load(this.temp);
      assertEquals("forest_floor", broken.palette().land(69, 0.0, 0.0).name());
      assertTrue(broken.warnings().getFirst().contains("\"stoen\""), broken.warnings().toString());
   }

   @Test
   void namedMixesAreShared() {
      NzSurfacePalette palette = NzSurfacePalette.bundled();
      assertSame(palette.land(16, 50.0, 100.0).top(), palette.land(16, 50.0, 100.0).face());
   }

   private static Set<Object> blocks(NzSurfaceMix mix) {
      return Arrays.stream(mix.blocks()).map(BlockState::getBlock).collect(Collectors.toSet());
   }

   private static void assertError(String start, String text) {
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> NzSurfacePalette.parse(text));
      assertTrue(error.getMessage().startsWith(start), error.getMessage());
   }
}
