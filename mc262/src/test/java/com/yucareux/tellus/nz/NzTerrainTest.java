package com.yucareux.tellus.nz;

import com.yucareux.tellus.nz.tiles.NzTiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NzTerrainTest {
   private static final long CACHE = 16L * 1024 * 1024;

   @TempDir
   Path temp;

   @Test
   void noWarningWhenTheTilesMatchTheWorld() throws IOException {
      writeManifest("nz-main", "2026-10-23");

      assertEquals(List.of(), NzTerrain.tileWarnings(new NzWorldSettings(Map.of("nz-main", "2026-10-23")), NzTiles.open(this.temp, CACHE)));
   }

   @Test
   void warnsWhenTheTilesChanged() throws IOException {
      writeManifest("nz-main", "2026-10-23");

      List<String> lines = NzTerrain.tileWarnings(new NzWorldSettings(Map.of("nz-main", "2026-10-22")), NzTiles.open(this.temp, CACHE));

      assertEquals(2, lines.size(), lines.toString());
      assertTrue(lines.get(0).contains("differ from those this world was created with"));
      assertTrue(lines.get(1).contains("nz-main: world created with data 2026-10-22, the tiles are now 2026-10-23"));
   }

   @Test
   void warnsWhenThereAreNoTiles() {
      List<String> lines = NzTerrain.tileWarnings(new NzWorldSettings(Map.of("nz-main", "2026-10-22")), NzTiles.open(this.temp, CACHE));

      assertEquals(1, lines.size(), lines.toString());
      assertTrue(lines.get(0).contains("no tile sets found in " + this.temp));
   }

   private void writeManifest(String name, String dataVersion) throws IOException {
      Path set = Files.createDirectories(this.temp.resolve(name));
      Files.writeString(set.resolve("tileset.json"), """
         {"format": "nz-tiles", "format_version": 1, "name": "%s", "data_version": "%s",
          "e0": 1600000, "n0": 5470000, "metres_per_block": 3}
         """.formatted(name, dataVersion));
   }
}
